# leap-laugh-love

## Team Members
1. Software Developer- Chris Musselman
2. Team Lead - Nikhil Akula
3. Developer - Yahia Elsaad
4. Database Manager - Lauren Sanday
5. Scrum Master - Elisa Paul
   
## Branching Strategy
We are using the Trunk branching strategy because it best fits our development strategy and schedule.

## Architecture Diagram

```mermaid
flowchart TB
    UI["Static test UI (index.html) / API client"]

    subgraph APP["Spring Boot app (leap-laugh-love)"]
        direction TB
        SEC["SecurityConfig + JwtAuthenticationFilter"]

        subgraph Controllers
            AuthC["AuthController\n/api/auth"]
            RegC["ClientRegistrationController\n/api/v1/clients"]
            BalC["BalanceController\n/api/balance"]
        end

        subgraph Services
            AuthS["AuthService"]
            BalS["BalanceService"]
            JwtS["JwtService"]
        end

        subgraph Repositories
            ClientRepo["ClientRepository"]
            CredRepo["ClientCredentialsRepository"]
            AcctRepo["AccountRepository"]
            LedgerRepo["CashLedgerRepository"]
        end
    end

    subgraph PG["PostgreSQL"]
        IAM[("iam schema")]
        TRADING[("trading schema")]
    end

    UI -->|"HTTPS / JSON"| SEC
    SEC --> AuthC
    SEC --> RegC
    SEC --> BalC

    AuthC --> AuthS
    RegC --> AuthS
    BalC --> BalS

    AuthS --> JwtS
    AuthS --> ClientRepo
    AuthS --> CredRepo
    BalS --> AcctRepo
    BalS --> LedgerRepo

    ClientRepo --> IAM
    CredRepo --> IAM
    AcctRepo --> TRADING
    LedgerRepo --> TRADING
```

`SecurityConfig` permits `/api/auth/**`, `/actuator/health`, and the static test UI without a token; every other route requires a valid JWT bearer token, which `JwtAuthenticationFilter` validates via `JwtService` before the request reaches a controller.

For local development and CI (see `docker-compose.yml` and `Jenkinsfile`), the app and a `postgres:16-alpine` database run as separate Docker containers on a shared bridge network — only the database port is published to the host, and the schema/seed SQL under `src/main/resources/db` is loaded into Postgres automatically on first init.

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

Schema source of truth: [leap_laugh_love_schema.sql](src/main/resources/db/leap_laugh_love_schema.sql). Tables live in two Postgres schemas — `iam` (clients, profiles, credentials) and `trading` (accounts, instruments, orders, executions, cash ledger, positions). Records in `orders`, `executions`, `cash_ledger`, and `position_movements` are append-only/immutable at the database level (delete/update-blocking triggers) to satisfy audit and compliance retention requirements; `clients` rows can never be deleted either, though profile fields and status may still be updated.
