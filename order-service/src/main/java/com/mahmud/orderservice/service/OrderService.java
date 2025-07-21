package com.mahmud.orderservice.service;

import java.util.List;

import com.mahmud.orderservice.dto.OrderDTO;
import com.mahmud.orderservice.dto.StripeResponse;
import com.stripe.model.checkout.Session;

public interface OrderService {
    StripeResponse createCheckoutSession(String userId);

    OrderDTO getOrder(Long id, String userId);

    void handleCheckoutSessionCompleted(Session session);

    List<OrderDTO> getAllOrders(String userId);
}