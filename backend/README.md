# backend

Spring Boot 4.1 / Java 21 REST API, scheduler, and engine blocks.

## Prerequisites
- Java 21
- Maven 3.9+
- Docker & Docker Compose

## Local Run Instructions
1. Copy environment template at repository root:
   ```bash
   cp .env.example .env
   ```
2. Start local PostgreSQL database via Docker Compose:
   ```bash
   cd backend
   docker compose up -d
   docker compose --env-file ../.env up -d
   ```
3. Run the Spring Boot application with the `local` profile:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```
4. Verify endpoints:
   - Health check: `curl http://localhost:8080/health`
   - Swagger UI: `http://localhost:8080/swagger-ui.html`
   - OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Running Tests
Ensure the local PostgreSQL container is running (`docker compose up -d`), then run:
```bash
mvn test -Dspring.profiles.active=local
```
