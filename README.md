# Fitness Microservices App

Full-stack microservices project using Spring Boot and React.

## Services
- Gateway (API Gateway)
- Eureka (Service Discovery)
- Config Server
- Activity Service
- User Service
- AI Service
- Frontend (React + Vite)

## Tech Stack
- Java + Spring Boot
- Spring Cloud (Gateway, Eureka, Config)
- MongoDB
- RabbitMQ
- React + MUI

## How to Run

1. Start Config Server (8888)
2. Start Eureka (8761)
3. Start Gateway (8080)
4. Start all services
5. Start frontend:
   ```bash
   npm run dev