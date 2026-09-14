# leap-laugh-love

## Team Members
1. Software Developer - Chris Musselman
2. Software Developer - Nikhil Akula
3. Tech Lead - Yahia Elsaad
4. Scrum Master - Lauren Sanday
5. Software Developer - Elisa Paul

## Branching Strategy
We are using the Trunk branching strategy because it best fits our development strategy and schedule.

---

## Architecture Overview

The repository is structured as a **Multi-Module Maven Project** splitting identity and trading domains into independently deployable microservices along with a shared security library:

1. **`common-security` (Shared Security Library)**: Contains reusable JWT token handling (`JwtService`, `JwtAuthenticationFilter`), 401 JSON error formatting (`JwtAuthenticationEntryPoint`), and shared CORS configuration (`CommonCorsConfiguration`). Packaged as a standard library JAR.
2. **`iam-app` (Identity Access Management)**: Handles client registration, sign-in, and JWT token issuance. Runs on port `8081`.
3. **`trading-app` (Trading Services)**: Handles account balances, deposits, withdrawals, order management, and trade history. Runs on port `8082`.
4. **`market-data-app` (Market Simulation)**: Simulates instrument prices with Geometric Brownian Motion (no external market-data API), keeping a live in-memory price per instrument plus OHLC candle history, exposed via REST and an SSE push stream. Runs on port `8083`.

```mermaid
flowchart TB
    UI["Static test UI / API Client"]

    subgraph PARENT["Parent Aggregator POM (leap-laugh-love-app)"]
        subgraph SEC["common-security (Shared Library)"]
            JwtS["JwtService & JwtAuthenticationFilter"]
            JwtEP["JwtAuthenticationEntryPoint"]
            CorsCfg["CommonCorsConfiguration"]
        end

        subgraph IAM["iam-app (Port 8081)"]
            IamApp["IamApplication @Import(JwtService)"]
            IamSec["IamSecurityConfig"]
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

        subgraph MARKETDATA["market-data-app (Port 8083)"]
            MdApp["MarketDataApplication @Import(JwtService) @EnableScheduling"]
            MdSec["MarketDataSecurityConfig (CORS Enabled)"]
            SimEngine["MarketSimulationEngine\n(GBM @Scheduled tick)"]
            CandleAcc["PriceCandleAccumulator\n(ticks -> OHLC candles)"]
            PriceC["PriceController\n/api/marketdata/prices"]
            StreamC["PriceStreamController\n/api/marketdata/stream (SSE)"]
        end
    end

    subgraph PG["PostgreSQL"]
        IAM_DB[("iam schema")]
        TRADING_DB[("trading schema")]
        MARKETDATA_DB[("marketdata schema")]
    end

    UI -->|"HTTP / JSON (8081)"| IamSec
    UI -->|"HTTP / JSON (8082)"| TradeSec
    UI -->|"HTTP / JSON / SSE (8083)"| MdSec

    IamSec --> AuthC
    IamSec --> RegC
    TradeSec --> BalC
    TradeSec --> OrderC
    MdSec --> PriceC
    MdSec --> StreamC
    SimEngine --> PriceC
    SimEngine --> StreamC
    SimEngine --> CandleAcc

    IAM -- "Library Dependency" --> SEC
    TRADING -- "Library Dependency" --> SEC
    MARKETDATA -- "Library Dependency" --> SEC

    AuthC --> IAM_DB
    RegC --> IAM_DB
    BalC --> TRADING_DB
    OrderC --> TRADING_DB
    CandleAcc --> MARKETDATA_DB
```

---

## UML Class Diagrams

Entities, repositories, services and controllers for each module (test sources and DTO getters omitted for legibility). Microservices (`iam-app`, `trading-app`, and `market-data-app`) share `JwtService`, `JwtAuthenticationFilter`, `JwtAuthenticationEntryPoint`, and `CommonCorsConfiguration` from the `common-security` module — those classes are marked `<<from common-security>>` where they appear.

**Legend:** solid arrow (`-->`) = association / field reference · dashed arrow (`..>`) = dependency (calls / uses) · `<<interface>>` = Spring Data repository.

### Reactor overview

```mermaid
flowchart LR
    classDef mod fill:transparent,stroke-width:1.4px;
    CS["common-security<br/>(jwt · auth filter · cors)"]:::mod
    IAM["iam-app<br/>(clients · auth · jwt)"]:::mod
    MD["market-data-app<br/>(instruments · simulation · candles)"]:::mod
    TR["trading-app<br/>(accounts · orders · positions)"]:::mod
    IAM -- "depends on" --> CS
    MD -- "depends on" --> CS
    TR -- "depends on" --> CS
```

### Common Security — `common-security`

`com.leap.leaplaughlove.common.security` — lightweight shared security library providing JWT creation and validation, request authentication filtering, entry point 401 error response handling, and centralized CORS configuration across all services.

```mermaid
classDiagram
    direction LR

    class JwtService {
        +generateToken(UUID, String) String
        +parseAndValidate(String) UUID
        +getExpirationSeconds() long
    }
    class JwtAuthenticationFilter {
        +doFilterInternal(...)
    }
    class JwtAuthenticationEntryPoint {
        +commence(...)
    }
    class CommonCorsConfiguration {
        +applyDefaults(CorsConfiguration) CorsConfiguration$
    }

    JwtAuthenticationFilter --> JwtService : uses
```

### Identity & Access — `iam-app`

`com.leap.leaplaughlove.iam.{client, auth, security, common}` — registers clients (`PB-02`), authenticates with BCrypt plus a failed-attempt lockout, and mints the JWTs every other module verifies.

```mermaid
classDiagram
    direction LR

    class Client {
        -UUID clientId
        -String email
        -String phone
        -String status
        -String fullName
        -LocalDate dateOfBirth
        -String ssn
        -BigDecimal initialDepositAmount
        +getStatus() String
        +setStatus(String)
    }
    class ClientCredentials {
        -UUID clientId
        -String passwordHash
        -int failedAttempts
        -OffsetDateTime lastLoginAt
        +incrementFailedAttempts()
        +resetFailedAttempts()
    }
    class ClientRepository {
        <<interface>>
        +findByEmail(String) Optional~Client~
        +existsByEmail(String) boolean
        +existsBySsn(String) boolean
    }
    class ClientCredentialsRepository {
        <<interface>>
        +findByClientId(UUID) Optional~ClientCredentials~
    }
    class ClientRegistrationController {
        +register(RegistrationRequest) RegistrationResponse
    }
    class AuthController {
        +login(LoginRequest) LoginResponse
    }
    class AuthService {
        +authenticate(String, String) LoginResponse
    }
    class JwtService {
        +generateToken(UUID, String) String
        +parseAndValidate(String) UUID
        +getExpirationSeconds() long
    }
    class JwtAuthenticationFilter {
        +doFilterInternal(...)
    }
    class IamSecurityConfig {
        +filterChain(...) SecurityFilterChain
    }
    class GlobalExceptionHandler {
        <<@RestControllerAdvice>>
    }
    class AccountLockedException
    class InvalidCredentialsException
    class LoginRequest {
        <<record>>
        +String email
        +String password
    }
    class LoginResponse {
        <<record>>
        +String accessToken
        +String tokenType
        +long expiresInSeconds
    }

    Client "1" --> "1" ClientCredentials : secures
    ClientRepository ..> Client : manages
    ClientCredentialsRepository ..> ClientCredentials : manages
    ClientRegistrationController --> ClientRepository : uses
    ClientRegistrationController ..> Client : creates
    AuthController --> AuthService : uses
    AuthController ..> LoginRequest : accepts
    AuthController ..> LoginResponse : returns
    AuthService --> ClientRepository : uses
    AuthService --> ClientCredentialsRepository : uses
    AuthService --> JwtService : uses
    AuthService ..> AccountLockedException : throws
    AuthService ..> InvalidCredentialsException : throws
    JwtAuthenticationFilter --> JwtService : uses
    IamSecurityConfig ..> JwtAuthenticationFilter : registers
    GlobalExceptionHandler ..> AccountLockedException : handles
    GlobalExceptionHandler ..> InvalidCredentialsException : handles
```

### Market Data — `market-data-app`

`com.leap.leaplaughlove.marketdata.{instrument, simulation, history, stream, api}` — simulates prices with discretized Geometric Brownian Motion on a scheduler, publishes ticks as Spring events, streams them over SSE, and rolls them up into OHLC candles.

```mermaid
classDiagram
    direction LR

    class SimulatedInstrument {
        -UUID instrumentId
        -String symbol
        -String displayName
        -BigDecimal initialPrice
        -BigDecimal drift
        -BigDecimal volatility
        -long rngSeed
        -boolean active
    }
    class PriceCandle {
        -UUID candleId
        -OffsetDateTime bucketStart
        -BigDecimal open
        -BigDecimal high
        -BigDecimal low
        -BigDecimal close
    }
    class SimulatedInstrumentRepository {
        <<interface>>
        +findByActiveTrue() List~SimulatedInstrument~
    }
    class PriceCandleRepository {
        <<interface>>
        +findByInstrument_SymbolAndBucketStartBetween(...) Page~PriceCandle~
    }
    class MarketSimulationEngine {
        +tick()
        +latest(String) Optional~PriceState~
        +latestAll() List~PriceState~
    }
    class GbmPriceGenerator {
        <<utility>>
        +nextPrice(double, double, double, double, RandomGenerator)$ double
    }
    class PriceState {
        <<record>>
        +String symbol
        +BigDecimal price
        +OffsetDateTime asOf
    }
    class PriceTickEvent {
        <<record>>
        +PriceState priceState
    }
    class PriceCandleAccumulator {
        +onPriceTick(PriceTickEvent)
        +flushStaleBuckets()
    }
    class PriceStreamBroadcaster {
        +subscribe(Set~String~) SseEmitter
        +onPriceTick(PriceTickEvent)
    }
    class PriceStreamController {
        +stream(String) SseEmitter
    }
    class PriceController {
        +getLatestPrices() List~PriceResponse~
        +getLatestPrice(String) PriceResponse
        +getHistory(...) Page~PriceCandleResponse~
    }
    class PriceResponse { <<record>> }
    class PriceCandleResponse { <<record>> }
    class MarketDataSecurityConfig {
        +filterChain(...) SecurityFilterChain
    }
    class JwtService { <<from common-security>> }
    class JwtAuthenticationFilter { <<from common-security>> }
    class JwtAuthenticationEntryPoint { <<from common-security>> }

    PriceCandle "many" --> "1" SimulatedInstrument : instrument
    SimulatedInstrumentRepository ..> SimulatedInstrument : manages
    PriceCandleRepository ..> PriceCandle : manages
    MarketSimulationEngine --> SimulatedInstrumentRepository : uses
    MarketSimulationEngine --> GbmPriceGenerator : uses
    MarketSimulationEngine ..> PriceState : produces
    MarketSimulationEngine ..> PriceTickEvent : publishes
    PriceCandleAccumulator --> SimulatedInstrumentRepository : uses
    PriceCandleAccumulator --> PriceCandleRepository : uses
    PriceCandleAccumulator ..> PriceTickEvent : listens
    PriceCandleAccumulator ..> PriceCandle : creates
    PriceStreamBroadcaster ..> PriceTickEvent : listens
    PriceStreamController --> PriceStreamBroadcaster : uses
    PriceController --> MarketSimulationEngine : uses
    PriceController --> PriceCandleRepository : uses
    PriceController ..> PriceResponse : returns
    PriceController ..> PriceCandleResponse : returns
    MarketDataSecurityConfig ..> JwtService : uses
    MarketDataSecurityConfig ..> JwtAuthenticationFilter : registers
    MarketDataSecurityConfig ..> JwtAuthenticationEntryPoint : registers
```

### Trading — `trading-app`

`com.leap.leaplaughlove.trading.{account, balance, ledger, order, position, security}` — owns brokerage accounts, an append-only cash ledger, order/execution history, and per-account positions; every endpoint resolves the client from the JWT principal already on the security context.

```mermaid
classDiagram
    direction LR

    class Account {
        -UUID accountId
        -UUID clientId
        -String accountNumber
        -String status
        -String baseCurrency
        -boolean tradingEnabled
    }
    class CashLedgerEntry {
        -UUID cashLedgerId
        -UUID accountId
        -String entryType
        -BigDecimal amount
        -String currency
        -OffsetDateTime createdAt
        -String description
    }
    class Instrument {
        -UUID instrumentId
        -String symbol
        -String name
        -String assetClass
    }
    class Order {
        -UUID orderId
        -BigDecimal quantity
        -BigDecimal limitPrice
        -OffsetDateTime submittedAt
        -OffsetDateTime filledAt
    }
    class Side { <<enumeration>> BUY SELL }
    class Type { <<enumeration>> MARKET LIMIT }
    class Status { <<enumeration>> PENDING FILLED PARTIALLY_FILLED CANCELLED REJECTED }
    class Execution {
        -UUID executionId
        -BigDecimal quantity
        -BigDecimal price
        -OffsetDateTime executedAt
    }
    class Position {
        -long quantity
        -BigDecimal avgCost
        -OffsetDateTime updatedAt
    }
    class PositionId {
        <<composite key>>
        -UUID accountId
        -UUID instrumentId
    }
    class AccountRepository {
        <<interface>>
        +findByClientIdAndStatus(UUID, String) List~Account~
        +findByAccountIdAndClientId(UUID, UUID) Optional~Account~
    }
    class CashLedgerRepository {
        <<interface>>
        +sumAmountsByAccountIds(List~UUID~) List~AccountTotal~
        +sumAmountByAccountIdAndCurrency(UUID, String) BigDecimal
    }
    class OrderRepository {
        <<interface>>
        +findByAccount_ClientIdOrderBySubmittedAtDesc(...) Page~Order~
    }
    class PositionRepository {
        <<interface>>
        +findPositionsByAccountId(UUID) List~PositionRow~
    }
    class BalanceService {
        +getBalanceForClient() BalanceResponse
        +deposit(UUID, CashMovementRequest) CashTransactionResponse
        +withdraw(UUID, CashMovementRequest) CashTransactionResponse
    }
    class BalanceController {
        +getBalance() BalanceResponse
        +deposit(UUID, CashMovementRequest)
        +withdraw(UUID, CashMovementRequest)
    }
    class OrderHistoryService {
        +getOrderHistory(UUID, int, int) Page~OrderHistoryItem~
    }
    class OrderHistoryController {
        +getOrderHistory(int, int) Page~OrderHistoryItem~
    }
    class PositionService {
        +getPositionsForAuthenticatedClientAccount(UUID) PositionsResponse
    }
    class PositionController {
        +getPositionsForAccount(UUID) PositionsResponse
    }
    class TradingSecurityConfig {
        +filterChain(...) SecurityFilterChain
    }
    class JwtService { <<from common-security>> }
    class JwtAuthenticationFilter { <<from common-security>> }
    class JwtAuthenticationEntryPoint { <<from common-security>> }

    Account "1" --> "many" Order : places
    Account "1" --> "many" CashLedgerEntry : ledger
    Order "many" --> "1" Instrument : instrument
    Order "1" --> "many" Execution : fills
    Order --> Side
    Order --> Type
    Order --> Status
    Position "many" --> "1" Instrument : instrument
    Position ..> PositionId : identified by
    AccountRepository ..> Account : manages
    CashLedgerRepository ..> CashLedgerEntry : manages
    OrderRepository ..> Order : manages
    PositionRepository ..> Position : manages
    BalanceService --> AccountRepository : uses
    BalanceService --> CashLedgerRepository : uses
    BalanceController --> BalanceService : uses
    OrderHistoryService --> OrderRepository : uses
    OrderHistoryController --> OrderHistoryService : uses
    PositionService --> AccountRepository : uses
    PositionService --> PositionRepository : uses
    PositionController --> PositionService : uses
    TradingSecurityConfig ..> JwtService : uses
    TradingSecurityConfig ..> JwtAuthenticationFilter : registers
    TradingSecurityConfig ..> JwtAuthenticationEntryPoint : registers
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
| **Market Data** | `GET /api/marketdata/prices` | Latest simulated price for every active instrument | Bearer JWT required |
| **Market Data** | `GET /api/marketdata/prices/{symbol}` | Latest simulated price for one instrument | Bearer JWT required |
| **Market Data** | `GET /api/marketdata/prices/{symbol}/history` | Paginated OHLC candle history | Bearer JWT required |
| **Market Data** | `GET /api/marketdata/stream` | Server-Sent-Events push of live price ticks (optional `?symbols=` filter) | Bearer JWT required |
| **Market Data** | `GET /actuator/health` | Market data service health check | Permitted |

---

## Building and Running

### 1. Build and Run Tests (Maven)

Build all modules and execute the full test suite from the repository root:

```bash
mvn clean verify
```

To build or test individual modules:

```bash
# Test Common Security module
mvn -pl common-security test

# Test IAM module (with dependency building)
mvn -pl iam-app -am test

# Test Trading module (with dependency building)
mvn -pl trading-app -am test

# Test Market Data module (with dependency building)
mvn -pl market-data-app -am test
```

### 2. Javadoc Documentation Generation

Generate Javadoc documentation across all modules from the repository root:

```bash
mvn compile javadoc:javadoc
```

> [!NOTE]
> In a multi-module Maven project where `iam-app`, `trading-app`, and `market-data-app` depend on the sibling library module `common-security`, invoking `compile` prior to `javadoc:javadoc` (or ensuring artifacts are installed in the local repository) ensures that class files for dependencies in the reactor are built so the Javadoc compiler can resolve classpath types across modules.
> 
> To generate Javadocs for a single module:
> ```bash
> mvn compile javadoc:javadoc -pl iam-app -am
> ```

### 3. Launch Services via Docker Compose (Recommended)

Set your environment variables and launch all containers (`db`, `iam-app`, `trading-app`, `market-data-app`):

```bash
export JWT_SECRET="your_jwt_secret_key_here_minimum_32_chars"
export DB_PASSWORD="changeme"

docker-compose up -d --build
```

Services will be exposed on:
- **IAM Application**: `http://localhost:8081`
- **Trading Application**: `http://localhost:8082`
- **Market Data Application**: `http://localhost:8083`
- **PostgreSQL Database**: `localhost:5432`

To stop containers and clean up volumes:

```bash
docker-compose down -v
```

### 4. Launch Services Locally (Spring Boot)

Ensure PostgreSQL is running locally on port `5432` with the database `paysprint` (or use active Spring profiles).

Run IAM App:
```bash
mvn -pl iam-app spring-boot:run
```

Run Trading App (in a separate terminal):
```bash
mvn -pl trading-app spring-boot:run
```

Run Market Data App (in a separate terminal):
```bash
mvn -pl market-data-app spring-boot:run
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
        int failed_attempts
        timestamptz last_login_at
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

Schema source of truth: `iam-app/src/main/resources/db/leap_laugh_love_schema.sql`. Tables live in three Postgres schemas — `iam` (clients, profiles, credentials), `trading` (accounts, instruments, orders, executions, cash ledger, positions), and `marketdata` (simulated instruments and OHLC price candles — decoupled from `trading.instruments`, matched only by symbol). Records in `orders`, `executions`, `cash_ledger`, and `position_movements` are append-only/immutable at the database level (delete/update-blocking triggers) to satisfy audit and compliance retention requirements.
