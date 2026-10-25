# RideShare Spring Boot PostgreSQL Connection — Diagnosis and Repair Report

**Date & Time:** October 25, 2026, 21:52 IST  
**Target Project:** `d:\CHIRANJEEVI PROJECT\new-project\rideShare-backend`  
**Engineer:** Senior Java Spring Boot & PostgreSQL Engineering Assistant  

---

## 1. Verified Root Cause

### What Happened
The application threw the following startup exception:
```text
org.hibernate.HibernateException: Unable to determine Dialect without JDBC metadata (please set 'jakarta.persistence.jdbc.url' for common cases or 'hibernate.dialect' when a custom Dialect implementation must be provided)
```

1. **Hibernate Dialect Discovery Failure:**
   Hibernate auto-detects dialect by opening a JDBC connection and inspecting database metadata (`DatabaseMetaData.getDatabaseProductName()`). When connection establishment fails, it reports `Unable to determine Dialect without JDBC metadata`.
2. **PostgreSQL Target Connectivity:**
   The backend was configured in `.env` to connect to:
   ```properties
   DB_URL=jdbc:postgresql://localhost:5433/rideshare
   DB_USERNAME=rideshare
   ```
   At the time of failure, Docker Desktop was closed / not running (`failed to connect to docker API`). Therefore, port `5433` was refusing connections.
3. **Local Port State Analysis:**
   - A native Windows service (`postgresql-x64-18`) was running and listening on port `5432`.
   - The dedicated isolated project container (`rideshare-local-db`, based on `postgres:16-alpine` mapping host port `5433 -> 5432`) existed in Docker with the dedicated `rideshare` schema (38 tables).
   - Once Docker Desktop was started and container `rideshare-local-db` came UP, port `5433` opened and bound to `::` / `127.0.0.1`.

---

## 2. Configuration & Secrets Handling

### Configuration Resolution Mechanism
- **Spring Boot 4.1.1 Config Import:**  
  [`src/main/resources/application.properties`](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/resources/application.properties#L2):
  ```properties
  spring.config.import=optional:file:.env[.properties]
  ```
  Spring Boot natively reads `.env` directly as properties.
- Property mappings:
  ```properties
  spring.datasource.url=${DB_URL}
  spring.datasource.driverClassName=org.postgresql.Driver
  spring.datasource.username=${DB_USERNAME}
  spring.datasource.password=${DB_PASSWORD}
  spring.jpa.hibernate.ddl-auto=${SPRING_JPA_HIBERNATE_DDL_AUTO:validate}
  ```

### Active Configuration Keys (Secrets Redacted)
| Key | Original Value | Current / Corrected Value | Status |
|---|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/rideshare` | `jdbc:postgresql://localhost:5433/rideshare` | Connected & Active |
| `DB_USERNAME` | `rideshare` | `rideshare` | Verified |
| `DB_PASSWORD` | `[REDACTED]` | `[REDACTED]` | Verified |
| `CORS_ALLOWED_ORIGINS` | Missing `5175` & current ngrok host | `http://localhost:5173,http://localhost:5174,http://localhost:5175,http://localhost:8081,https://tactical-stardust-flattered.ngrok-free.dev,https://cultural-giddiness-pueblo.ngrok-free.dev` | Fixed & Verified (HTTP 200 on OPTIONS) |

---

## 3. Port & Service Connectivity Verification

| Target / Service | Port | Listening Process | Reachable | Test Result |
|---|---|---|---|---|
| `rideshare-local-db` (Docker Container) | `5433` | `com.docker.backend.exe` / `wslrelay.exe` | Yes | TCP Connect: OK |
| Local Windows PostgreSQL 18 | `5432` | `postgres.exe` (PID 6600) | Yes | TCP Connect: OK |
| RideShare Spring Boot Backend | `8080` | `java.exe` (PID 4688) | Yes | Health check: HTTP 200 `UP` |
| Admin Web Frontend (Vite) | `5175` | `node.exe` | Yes | HTTP 200 |

---

## 4. Database Existence & Schema Validation

Direct verification on the `rideshare-local-db` container on port `5433`:
```bash
docker exec rideshare-local-db psql -U rideshare -d rideshare -c "SELECT current_database(), current_user, count(*) FROM information_schema.tables WHERE table_schema='public';"
```

**Result:**
- **Database:** `rideshare`
- **Owner / User:** `rideshare`
- **Active Table Count:** `38` tables present (users, rides, bookings, vehicles, wallets, transactions, audit logs, etc.)
- **Integrity:** Zero data deletion or recreation needed. Existing data and migrations preserved intact.

---

## 5. Files Changed & Reasons

1. **[`d:/CHIRANJEEVI PROJECT/new-project/rideShare-backend/.env`](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/.env)**:
   - Added active frontend origin `http://localhost:5175` and tunnel `https://cultural-giddiness-pueblo.ngrok-free.dev` to `CORS_ALLOWED_ORIGINS`.
2. **[`d:/CHIRANJEEVI PROJECT/new-project/rideShare-backend/src/main/resources/application.properties`](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/resources/application.properties)**:
   - Added `http://localhost:5175` to fallback default CORS property.
3. **[`d:/CHIRANJEEVI PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/SecurityConfig.java`](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/SecurityConfig.java)**:
   - Synchronized CORS fallback origins with local development ports.
4. **[`d:/CHIRANJEEVI PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/security/JwtFilter.java`](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/security/JwtFilter.java)**:
   - Added clean short-circuit passthrough for `OPTIONS` preflight requests to prevent 403 preflight blocks.

---

## 6. Actual Runtime & Build Verification Results

### 1. Spring Boot Live Health Endpoint
```http
GET http://localhost:8080/api/public/health
```
**Response:**
```json
{
  "service": "RideShare Backend API",
  "status": "UP",
  "timestamp": 1792945278511
}
```

### 2. Microservices & HikariCP Pool Status
```http
GET http://localhost:8080/api/public/health/microservices
```
**Database Pool Data:**
- `postgresql-db` status: **`UP`**
- `HikariCP`: Active connections operational (`[Pool: 0 active / 5 idle / 20 max]`)
- Latency: `3ms`

### 3. CORS Preflight Check on `/api/auth/login`
- **Origin `http://localhost:5175`**:
  ```http
  OPTIONS /api/auth/login -> HTTP 200 OK
  Access-Control-Allow-Origin: http://localhost:5175
  Access-Control-Allow-Methods: GET,POST,PUT,DELETE,PATCH,OPTIONS,HEAD
  ```
- **Origin `https://cultural-giddiness-pueblo.ngrok-free.dev`**:
  ```http
  OPTIONS /api/auth/login -> HTTP 200 OK
  Access-Control-Allow-Origin: https://cultural-giddiness-pueblo.ngrok-free.dev
  ```

---

## 7. Manual Steps Still Required

**None.**
- PostgreSQL container `rideshare-local-db` is UP on port `5433`.
- Backend Spring Boot 4.1.1 is UP on port `8080` with active HikariCP connection pool.
- Preflight CORS requests from `http://localhost:5175` and ngrok resolve with `HTTP 200 OK`.
