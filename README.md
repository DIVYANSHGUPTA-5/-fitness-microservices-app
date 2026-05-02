# 🏋️ Fitness Microservices App

A full-stack microservices-based fitness tracking application built using **Spring Boot + React** with modern cloud architecture.

---

## 🚀 Architecture Overview

- API Gateway (Spring Cloud Gateway)
- Service Discovery (Eureka)
- Config Server (Centralized configuration)
- Microservices:
   - Activity Service
   - User Service
   - AI Recommendation Service
- Messaging: RabbitMQ
- Database: MongoDB
- Frontend: React + Vite + MUI

---

## 🔁 Request Flow

Frontend → Gateway → Eureka → Microservice → Database / RabbitMQ → AI Service → Response

---

## 🧩 Services Description

### 🔹 Gateway
- Routes all incoming requests
- Handles authentication (Keycloak/JWT)

### 🔹 Eureka
- Service registry for all microservices

### 🔹 Config Server
- Centralized configuration management

### 🔹 Activity Service
- Tracks workouts (type, duration, calories)

### 🔹 User Service
- Handles user registration and data

### 🔹 AI Service
- Consumes activity data via RabbitMQ
- Generates recommendations using AI

---

## 🛠️ Tech Stack

| Layer        | Technology |
|-------------|-----------|
| Backend     | Spring Boot, Spring Cloud |
| Frontend    | React, Vite, Material UI |
| Messaging   | RabbitMQ |
| Database    | MongoDB |
| Auth        | Keycloak (JWT) |

---

## ▶️ How to Run

### 1. Start Config Server