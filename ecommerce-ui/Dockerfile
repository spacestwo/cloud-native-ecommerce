FROM node:22-alpine

# Define build arguments for environment variables
ARG VITE_PUBLIC_API_URL
ARG VITE_PRODUCT_API_URL
ARG VITE_ORDER_API_URL
ARG VITE_KEYCLOAK_URL
ARG VITE_REALM
ARG VITE_CLIENT_ID

# Set environment variables during the build process
ENV VITE_PUBLIC_API_URL=$VITE_PUBLIC_API_URL
ENV VITE_PRODUCT_API_URL=$VITE_PRODUCT_API_URL
ENV VITE_ORDER_API_URL=$VITE_ORDER_API_URL


WORKDIR /app

COPY package.json .

RUN npm install

COPY . .

RUN npm run build

EXPOSE 4173

CMD [ "npm", "run", "preview" ]