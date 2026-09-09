# leap-laugh-love

## Team Members
1. Software Developer - Chris Musselman
2. Team Lead - Nikhil Akula
3. Developer - Yahia Elsaad
4. Database Manager - Lauren Sanday
5. Scrum Master - Elisa Paul

## Branching Strategy
We are using the Trunk branching strategy because it best fits our development strategy and schedule.

---

## Architecture Overview

The repository is structured as a **Multi-Module Maven Project** splitting identity and trading domains into independently deployable microservices:

1. **`iam-app` (Identity Access Management)**: Handles client registration, sign-in, and JWT token issuance. Runs on port `8081`.
2. **`trading-app` (Trading Services)**: Handles account balances, deposits, withdrawals, order management, and trade history. Runs on port `8082`.

```mermaid
flowchart TB
    UI["Static test UI / API Client"]

    subgraph PARENT["Parent Aggregator POM (leap-laugh-love-app)"]
        subgraph IAM["iam-app (Port 8081)"]
            IamApp["IamApplication"]
            IamSec["IamSecurityConfig"]
            JwtS["JwtService & JwtAuthenticationFilter"]
            AuthC["AuthController\n/api/iam/auth"]
            RegC["ClientRegistrationController\n/api/iam/v1/clients"]
        end

        subgraph TRADING["trading-app (Port 8082)"]
            TradeApp["TradingApplication @Import(JwtService)"]
            TradeSec["TradingSecurityConfig (CORS Enabled)"]
            TradeEx["TradingGlobalExceptionHandler"]
            BalC["BalanceController\n/api/trading/balance"]
            OrderC["OrderHistoryController\n/api/trading/orders/history"]
        end
    end

    subgraph PG["PostgreSQL"]
        IAM_DB[("iam schema")]
        TRADING_DB[("trading schema")]
    end

    UI -->|"HTTP / JSON (8081)"| IamSec
    UI -->|"HTTP / JSON (8082)"| TradeSec

    IamSec --> AuthC
    IamSec --> RegC
    TradeSec --> BalC
    TradeSec --> OrderC

    TRADING -- "Classpath Dependency (JwtService)" --> IAM

    AuthC --> IAM_DB
    RegC --> IAM_DB
    BalC --> TRADING_DB
    OrderC --> TRADING_DB
```

---

## API Endpoints Summary

| Module | Endpoint | Description | Permitted / Auth |
|---|---|---|---|
| **IAM** | `POST /api/iam/auth/login` | Client login, returns JWT token | Permitted |
| **IAM** | `POST /api/iam/v1/clients/register` | Client registration | Permitted |
| **IAM** | `GET /actuator/health` | IAM service health check | Permitted |
| **Trading** | `GET /api/trading/balance` | Get balances for authenticated client | Bearer JWT required |
| **Trading** | `POST /api/trading/balance/accounts/{id}/deposit` | Deposit funds | Bearer JWT required |
| **Trading** | `POST /api/trading/balance/accounts/{id}/withdrawal` | Withdraw funds | Bearer JWT required |
| **Trading** | `GET /api/trading/orders/history` | Paginated order history | Bearer JWT required |
| **Trading** | `GET /actuator/health` | Trading service health check | Permitted |

---

## Building and Running

### 1. Build and Run Tests (Maven)

Build all modules and execute the full test suite from the repository root:

```bash
mvn clean verify
```

To build or test individual modules:

```bash
# Test only IAM module
mvn -pl iam-app test

# Test Trading module (with dependency building)
mvn -pl trading-app -am test
```

### 2. Launch Services via Docker Compose (Recommended)

Set your environment variables and launch all containers (`db`, `iam-app`, `trading-app`):

```bash
export JWT_SECRET="your_jwt_secret_key_here_minimum_32_chars"
export DB_PASSWORD="changeme"

docker-compose up -d --build
```

Services will be exposed on:
- **IAM Application**: `http://localhost:8081`
- **Trading Application**: `http://localhost:8082`
- **PostgreSQL Database**: `localhost:5432`

To stop containers and clean up volumes:

```bash
docker-compose down -v
```

### 3. Launch Services Locally (Spring Boot)

Ensure PostgreSQL is running locally on port `5432` with the database `paysprint` (or use active Spring profiles).

Run IAM App:
```bash
mvn -pl iam-app spring-boot:run
```

Run Trading App (in a separate terminal):
```bash
mvn -pl trading-app spring-boot:run
```

---

## ER Diagram

```mermaid
erDiagram
    CLIENTS ||--|| CLIENT_PROFILE : has
    CLIENTS ||--|| CLIENT_CREDENTIALS : has
    CLIENTS ||--o{ ACCOUNTS : owns
    ACCOUNTS ||--o{ ORDERS : places
    ACCOUNTS ||--o{ CASH_LEDGER : records
    ACCOUNTS ||--o{ POSITIONS : holds
    ACCOUNTS ||--o{ POSITION_MOVEMENTS : tracks
    INSTRUMENTS ||--o{ ORDERS : "traded in"
    INSTRUMENTS ||--o{ POSITIONS : represents
    INSTRUMENTS ||--o{ POSITION_MOVEMENTS : affects
    ORDERS ||--o{ EXECUTIONS : fills
    ORDERS ||--o{ CASH_LEDGER : settles
    ORDERS ||--o{ POSITION_MOVEMENTS : generates
    EXECUTIONS ||--o{ CASH_LEDGER : settles
    EXECUTIONS ||--o{ POSITION_MOVEMENTS : generates

    CLIENTS {
        uuid client_id PK
        text email UK
        text phone
        text status
        timestamptz created_at
    }
    CLIENT_PROFILE {
        uuid client_id "PK, FK"
        text full_name
        date date_of_birth
        char ssn UK
        text address_line_1
        text city
        text postal_code
        char country_code
        text experience_level
        numeric initial_deposit_amount
    }
    CLIENT_CREDENTIALS {
        uuid client_id "PK, FK"
        text password_hash
        int failed_sign_in_attempts
        timestamptz locked_until
    }
    ACCOUNTS {
        uuid account_id PK
        uuid client_id FK
        text account_number UK
        text status
        char base_currency
        boolean trading_enabled
    }
    INSTRUMENTS {
        uuid instrument_id PK
        text symbol
        text instrument_name
        text asset_class
        text market
        char currency
        boolean is_tradable
    }
    ORDERS {
        uuid order_id PK
        uuid account_id FK
        uuid instrument_id FK
        text side
        bigint quantity
        text status
        timestamptz submitted_at
    }
    EXECUTIONS {
        uuid execution_id PK
        uuid order_id FK
        bigint fill_quantity
        numeric fill_price
        text status
        timestamptz executed_at
    }
    CASH_LEDGER {
        uuid cash_ledger_id PK
        uuid account_id FK
        uuid order_id FK
        uuid execution_id FK
        text entry_type
        numeric amount
        char currency
    }
    POSITIONS {
        uuid account_id "PK, FK"
        uuid instrument_id "PK, FK"
        bigint quantity
        numeric avg_cost
    }
    POSITION_MOVEMENTS {
        uuid movement_id PK
        uuid account_id FK
        uuid instrument_id FK
        uuid order_id FK
        uuid execution_id FK
        text movement_type
        bigint quantity_delta
        numeric cost_delta
    }
```

Schema source of truth: `iam-app/src/main/resources/db/leap_laugh_love_schema.sql`. Tables live in two Postgres schemas — `iam` (clients, profiles, credentials) and `trading` (accounts, instruments, orders, executions, cash ledger, positions). Records in `orders`, `executions`, `cash_ledger`, and `position_movements` are append-only/immutable at the database level (delete/update-blocking triggers) to satisfy audit and compliance retention requirements.
