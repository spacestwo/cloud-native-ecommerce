package com.mahmud.orderservice.service;

import com.mahmud.orderservice.dto.*;
import com.mahmud.orderservice.entity.Order;
import com.mahmud.orderservice.exception.InsufficientStockException;
import com.mahmud.orderservice.exception.LockAcquisitionException;
import com.mahmud.orderservice.exception.ResourceNotFoundException;
import com.mahmud.orderservice.feign.InventoryServiceClient;
import com.mahmud.orderservice.feign.ProductServiceClient;
import com.mahmud.orderservice.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.checkout.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;
    private final InventoryServiceClient inventoryServiceClient;
    private final StripeService stripeService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final String inventoryApiKey;
    private final String productServiceApiKey;

    public OrderServiceImpl(OrderRepository orderRepository, ProductServiceClient productServiceClient,
            InventoryServiceClient inventoryServiceClient, StripeService stripeService,
            RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper,
            @Value("${inventory-service.api-key}") String inventoryApiKey,
            @Value("${inventory-service.api-key}") String productServiceApiKey) {
        this.orderRepository = orderRepository;
        this.productServiceClient = productServiceClient;
        this.inventoryServiceClient = inventoryServiceClient;
        this.stripeService = stripeService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.inventoryApiKey = inventoryApiKey;
        this.productServiceApiKey = productServiceApiKey;
    }

    @Override
    @Transactional
    public StripeResponse createCheckoutSession(String userId) {
        String lockKey = "lock:order:" + userId;
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", 30, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            throw new LockAcquisitionException("Failed to acquire lock for order: " + userId);
        }
        try {
            System.out.println("Fetching cart with userId: " + userId);

            // Fetch user's cart
            CartDTO cart = productServiceClient.getCart(userId, productServiceApiKey);
            if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
                throw new ResourceNotFoundException("Cart is empty or not found for user: " + userId);
            }
            System.out.println("Cart fetched: " + cart);

            // Validate stock and calculate total amount
            List<CartItemDTO> items = cart.getItems();
            double totalAmount = 0.0;
            for (CartItemDTO item : items) {
                ProductDTO product = inventoryServiceClient.getProductById(item.getProductId());
                if (product == null) {
                    throw new ResourceNotFoundException("Product not found with id: " + item.getProductId());
                }
                if (product.getStock() < item.getQuantity()) {
                    throw new InsufficientStockException("Insufficient stock for product " + item.getProductId() +
                            ". Available: " + product.getStock() + ", Requested: " + item.getQuantity());
                }
                totalAmount += product.getPrice() * item.getQuantity();
            }

            // Create order
            Order order = new Order();
            order.setUserId(userId);
            order.setItemsJson(objectMapper.writeValueAsString(items));
            order.setStatus("PENDING");
            order.setTotalAmount(totalAmount);
            orderRepository.save(order);

            // Create Stripe checkout session
            StripeResponse stripeResponse = stripeService.createCheckoutSession(items, userId, order.getId());
            order.setCheckoutSessionId(stripeResponse.getSessionId());
            orderRepository.save(order);

            return stripeResponse;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create checkout session: " + e.getMessage(), e);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDTO getOrder(Long id, String userId) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));
        if (!order.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order does not belong to user: " + userId);
        }
        try {
            OrderDTO orderDTO = new OrderDTO();
            orderDTO.setId(order.getId());
            orderDTO.setUserId(order.getUserId());
            orderDTO.setItems(objectMapper.readValue(order.getItemsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CartItemDTO.class)));
            orderDTO.setStatus(order.getStatus());
            orderDTO.setTotalAmount(order.getTotalAmount());
            orderDTO.setCheckoutSessionId(order.getCheckoutSessionId());
            return orderDTO;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse order items: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void handleCheckoutSessionCompleted(Session session) {
        System.out.println("-----EXECUTING-POST-PAYMENT-----");
        String checkoutSessionId = session.getId();
        System.out.println("Processing checkout session: " + checkoutSessionId);
        System.out.println("Payment status: " + session.getPaymentStatus());

        Order order = orderRepository.findByCheckoutSessionId(checkoutSessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found for session: " + checkoutSessionId));
        System.out.println("Found order: " + order.getId() + ", current status: " + order.getStatus());

        if ("paid".equals(session.getPaymentStatus())) {
            System.out.println("Payment status is 'paid', updating stock and order status");
            try {
                List<CartItemDTO> items = objectMapper.readValue(order.getItemsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, CartItemDTO.class));
                StockUpdateDTO stockUpdateDTO = new StockUpdateDTO();
                List<StockUpdateDTO.ProductUpdate> productUpdates = new ArrayList<>();

                for (CartItemDTO item : items) {
                    ProductDTO product = inventoryServiceClient.getProductById(item.getProductId());
                    if (product == null) {
                        System.err.println("Product not found: " + item.getProductId());
                        throw new ResourceNotFoundException("Product not found with id: " + item.getProductId());
                    }
                    System.out.println("Product: " + product.getId() + ", current stock: " + product.getStock());
                    if (product.getStock() < item.getQuantity()) {
                        System.err.println("Insufficient stock for product: " + item.getProductId());
                        throw new InsufficientStockException("Insufficient stock for product " + item.getProductId());
                    }

                    StockUpdateDTO.ProductUpdate update = new StockUpdateDTO.ProductUpdate();
                    update.setProductId(item.getProductId());
                    update.setQuantity(item.getQuantity());
                    update.setIncrement(false); // Decrease stock
                    productUpdates.add(update);
                }

                stockUpdateDTO.setProducts(productUpdates);
                System.out.println("Updating stock with: " + stockUpdateDTO);
                inventoryServiceClient.updateStock(stockUpdateDTO, inventoryApiKey);
                System.out.println("Stock updated successfully");

                // Update order status
                order.setStatus("PAID");
                orderRepository.save(order);
                System.out.println("Order status updated to PAID for order: " + order.getId());

                productServiceClient.deleteCart(order.getUserId(), productServiceApiKey);
            } catch (Exception e) {
                System.err.println("Failed to process checkout session: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to process checkout session: " + e.getMessage(), e);
            }
        } else {
            System.out.println("Payment status is not 'paid': " + session.getPaymentStatus());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTO> getAllOrders(String userId) {
        List<Order> orders = orderRepository.findByUserId(userId);

        return orders.stream()
                .map(order -> {
                    OrderDTO dto = new OrderDTO();
                    dto.setId(order.getId());
                    dto.setUserId(order.getUserId());
                    dto.setStatus(order.getStatus());
                    dto.setTotalAmount(order.getTotalAmount());
                    dto.setCheckoutSessionId(order.getCheckoutSessionId());
                    try {
                        dto.setItems(objectMapper.readValue(order.getItemsJson(),
                                objectMapper.getTypeFactory().constructCollectionType(List.class, CartItemDTO.class)));
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse items for order ID: " + order.getId(), e);
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

}
