# 🤖 AI Chatbot — Backend

> **Production-grade Spring Boot backend** cho hệ thống AI Chatbot sử dụng **Agentic RAG** (Retrieval-Augmented Generation) với Google Gemini, vector search (Qdrant), Cohere cross-encoder rerank, **Semantic Cache** (Redis HNSW + local MiniLM embedding), và kiến trúc event-driven qua Redis Streams. Hệ thống được thiết kế với multi-layer resilience, full observability stack, và multi-environment deployment.

[![Java](https://img.shields.io/badge/Java-24-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-blue?logo=spring)](https://docs.spring.io/spring-ai/reference/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 🏗️ Architecture Overview

```
┌─────────────┐     ┌─────────────────────────────────────────────────────────┐
│   Frontend   │────▶│                 Spring Boot Backend                     │
│  (Vite + TS) │◀────│                                                         │
└─────────────┘     │  ┌──────────┐ ┌───────────┐ ┌────────────────┐         │
                    │  │   Auth   │ │   Chat    │ │  Admin (RBAC)  │         │
                    │  │ (Google  │ │ (SSE      │ │  - Dashboard   │         │
                    │  │  OAuth2) │ │  Stream)  │ │  - Crawler     │         │
                    │  └────┬─────┘ └─────┬─────┘ │  - Documents   │         │
                    │       │             │        │  - Jobs        │         │
                    │       ▼             ▼        └───────┬────────┘         │
                    │  ┌───────────────────────────────────────────────────┐  │
                    │  │              Service Layer                         │  │
                    │  │  Agentic RAG (LLM Tool Calling → Search → Rerank) │  │
                    │  │  LLM (Gemini 2.5 Flash + 2-Phase Timeout)        │  │
                    │  │  Resilience (CB + RateLimiter + Bulkhead)         │  │
                    │  │  Redis Streams (Consumer Group + DLQ + Recovery)  │  │
                    │  │  Session Activity (ZSET + Background Sync)        │  │
                    │  │  Rate Limit (Token Bucket + Post-flight Quota)     │  │
                    │  │  Semantic Cache (Redis HNSW + MiniLM-L6-v2 local) │  │
                    │  │  Observability (Micrometer → Prometheus)          │  │
                    │  └────┬──────────┬──────────┬──────────┬────────────┘  │
                    └───────┼──────────┼──────────┼──────────┼──────────────┘
                            ▼          ▼          ▼          ▼
                       ┌────────┐ ┌────────┐ ┌────────┐ ┌──────────┐
                       │Postgres│ │ Redis  │ │ Qdrant │ │  Gemini  │
                       │  (DB)  │ │(Cache/ │ │(Vector │ │  (LLM)   │
                       │        │ │Stream/ │ │ Store) │ │          │
                       │        │ │ ZSET)  │ │        │ │          │
                       └────────┘ └────────┘ └────────┘ └──────────┘
                            ▲          ▲
                       ┌────┴──────────┴────┐
                       │   Monitoring Stack  │
                       │  Prometheus → Grafana│
                       │  Redis Exporter      │
                       │  PG Exporter         │
                       └─────────────────────┘
```

---

## ⚡ Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Runtime** | Java 24 (Virtual Threads) | High-concurrency async processing |
| **Framework** | Spring Boot 3.5.8 | Core framework |
| **AI / LLM** | Spring AI 1.1.0 + Google Gemini 2.5 Flash | Chat streaming & embedding |
| **Embedding** | Gemini Embedding 001 (Matryoshka 768d) | Dual task-type embeddings (RETRIEVAL_DOCUMENT / RETRIEVAL_QUERY) |
| **Vector DB** | Qdrant 1.13 | Semantic search & RAG retrieval |
| **Reranking** | Cohere Rerank v3.5 | Cross-encoder reranking (improve RAG quality) |
| **Local Embedding** | LangChain4j MiniLM-L6-v2-Q (ONNX) | 384-dim local embeddings for semantic cache (~1ms, zero API cost) |
| **Database** | PostgreSQL 16 | Persistent storage (Flyway migrations) |
| **Cache/Stream** | Redis 8 (RediSearch) | Caching, Streams, ZSET session tracking, Lua rate limiting, HNSW vector search |
| **Migration** | Flyway | Database schema versioning (table + index migrations) |
| **Resilience** | Resilience4j 2.3 | Circuit Breaker, RateLimiter, Bulkhead (LLM + Redis) |
| **Security** | Spring Security + JWT RS256 | Stateless authentication & RBAC authorization |
| **Auth** | Google OAuth2 | Social login (ID token verification) |
| **Doc Parser** | Apache Tika + Spring AI PDF Reader | Multi-format document ingestion (PDF, DOCX, TXT, HTML, ODT) |
| **Text Splitting** | LangChain4j `DocumentSplitters.recursive()` | Recursive character-based chunking |
| **Redis Client** | Jedis 6.x | FT.CREATE / FT.SEARCH for semantic cache (alongside Lettuce) |
| **Observability** | Micrometer + Prometheus + Grafana | Custom metrics, dashboards, alerting |
| **Load Testing** | k6 | Stress testing & concurrency benchmarks |
| **ID Generation** | UUIDv7 (uuid-creator) | Time-ordered sortable unique IDs |
| **Container** | Docker + Docker Compose (multi-env) | Infrastructure orchestration (Dev / Prod) |
| **SAST** | SpotBugs + FindSecBugs + PMD + OWASP Dep-Check | Static analysis, CVE scanning (failOnCVSS ≥ 7) |
| **CI/CD** | GitHub Actions (6-stage) + Trivy + SonarQube | Secret scan → Test → SAST → Quality Gate → Docker |
| **DB Optimization** | LazyConnectionDataSourceProxy | Defer DB connection until first SQL (cache-hit optimization) |

---

## 🎯 Key Features

### 🧠 Agentic RAG Pipeline

- **Agentic approach**: LLM (Gemini) tự quyết định khi nào cần tra cứu knowledge base thông qua **Tool Calling** — thay vì naive RAG (mọi câu hỏi đều qua Qdrant)
- **Semantic Cache layer** (inside tool): Redis HNSW + MiniLM-L6-v2 local embedding (384-dim, ~1ms). Chỉ trigger khi LLM gọi tool → cache hit → ~10ms thay vì ~2-5s (skip Qdrant + Cohere hoàn toàn). Câu hỏi không cần tool → bypass cache hoàn toàn
- **3-stage retrieval** (khi cache miss + tool được gọi): Vector Search (Qdrant) → Cross-Encoder Rerank (Cohere) → Context Assembly
- **Event-driven cache invalidation**: Upload tài liệu mới → tự động evict toàn bộ semantic cache (FT.DROPINDEX DD + re-create)
- **Tool resilience**: Timeout 10s + graceful fallback — nếu Qdrant down/chậm, LLM vẫn trả lời bằng general knowledge. Cache failure cũng fail-open
- **Dual VectorStore architecture**: Separate `RETRIEVAL_DOCUMENT` embeddings for indexing and `RETRIEVAL_QUERY` embeddings for search — follows Gemini's recommended task-type separation
- **Matryoshka embedding**: `gemini-embedding-001` giảm từ 3072d → 768d, tiết kiệm 4x RAM Qdrant với ~95% chất lượng retrieval
- **Smart document parser**: PDF → page-level reader (PagePdfDocumentReader), non-PDF → Apache Tika auto-detect
- **Configurable pipeline**: chunk size, overlap, candidate topK, rerank topK, similarity threshold — tất cả qua `application.yaml`

### 💬 Chat Streaming

- **SSE (Server-Sent Events)** real-time streaming response
- **Agentic tool execution**: LLM tự gọi `searchJavaSpringBootDocs` tool khi cần — không cần pre-load RAG context
- **Auto title generation**: Session tự động đặt tên qua default model (dùng chung config)
- **Conversation history**: Redis cache với LRU (20 messages) + PostgreSQL persistence

### 🛡️ Multi-Layer Resilience

#### LLM (Gemini) — Full Resilience4j Stack
- **RateLimiter**: 90 RPM (headroom 10% từ Gemini's 100 RPM limit)
- **Bulkhead**: Max 15 concurrent LLM streams
- **CircuitBreaker**: COUNT_BASED sliding window, 50% failure threshold
- **2-Phase Timeout**: 60s cho first token (cold start + tool execution), 5s idle giữa các token
- **Smart Retry**: Chỉ retry `TimeoutException` + `IOException`, KHÔNG retry nếu đã emit data (tránh duplicate text)
- **Stack order**: Subscribe path: RL → BH → CB → Gemini stream

#### Redis — SafeRedisExecutor + Circuit Breaker
- **`SafeRedisExecutor`**: Centralized CB wrapper cho mọi Redis operation
- **Fail-open rate limiting**: Khi Redis down → cho phép request đi tiếp
- **Direct DB fallback**: Redis Stream push fail → insert trực tiếp vào PostgreSQL
- **Prometheus metrics**: Tự động đếm `redis.fallback` và `redis.skip` counters

### 🔒 Rate Limiting (2 Layer)

- **Layer 1 — Token Bucket** (anti-spam): Redis Lua script, atomic refill + consume, configurable capacity & refill rate
- **Layer 2 — Daily Token Quota** (post-flight):
  - **Pre-flight check**: Chỉ kiểm tra xem user đã vượt quota chưa (Redis GET) — không increment
  - **Post-flight consume**: Sau khi stream xong, cộng `totalTokens` thực tế từ **Gemini response metadata** (bao gồm prompt + RAG context + output tokens) — mapping 1:1 với billing Gemini API
  - Fire-and-forget trên virtual thread, 25h TTL (midnight edge-case buffer)
- **HTTP headers**: `Retry-After`, `X-RateLimit-Daily-Limit`, `X-RateLimit-Daily-Used`

### 📨 Event-Driven Message Pipeline

- **Redis Streams Consumer Group**: Multi-consumer parallel processing (configurable concurrency)
- **Batch insert**: Messages batched → PostgreSQL via native SQL `ON CONFLICT DO NOTHING` (idempotent)
- **Dead Letter Queue (DLQ)**: Messages exceeding max retry → moved to `chat:messages:dlq`
- **Pending Message Recovery**: Scheduler reclaim stale pending messages (XCLAIM) → retry processing
- **Stream trimming**: Auto-trim sau mỗi consume cycle (approx trimming, 50K max)

### 📊 Session Activity Tracking

- **Dual ZSET architecture**:
  - ZSET 1 (`chat:user:{userId}:sessions`): Fast session list API (cursor pagination)
  - ZSET 2 (`system:dirty_sessions`): Background scheduler sync to DB
- **Cold start warm-up**: Auto populate ZSET từ DB khi cache trống
- **Background sync**: `SessionSyncScheduler` ZPOPMIN dirty sessions → batch UPDATE PostgreSQL (mỗi 10s)

### 📈 Observability & Monitoring

- **Custom Prometheus metrics** (via `ChatMetricsService`):
  - `chat_streams_active` — Gauge: số SSE stream đang mở
  - `chat_requests_total` — Counter: tổng số chat request
  - `chat_stream_errors_total` — Counter: tổng lỗi stream
  - `chat_stream_duration_seconds` — Timer: p50/p95/p99 stream duration
  - `redis_stream_pending` — Gauge: pending messages trong Redis Stream
  - `redis_stream_pushed_total` — Counter: messages pushed to stream
  - `redis_batch_insert_seconds` — Timer: batch insert duration
  - `llm.stream.ttfb` — Timer: Time To First Byte từ Gemini
  - `llm.stream.status` — Counter: stream success/error by type
- **Resilience4j health indicators**: CB state, RL metrics, BH metrics exposed via Actuator
- **Lettuce pool metrics** (via `LettucePoolMetricsConfig` — reflection-based extraction):
  - `lettuce_pool_active` — Gauge: connections currently borrowed
  - `lettuce_pool_idle` — Gauge: idle connections in pool
  - `lettuce_pool_pending` — Gauge: threads waiting for connection (⚠️ if > 0)
  - `lettuce_pool_max` — Gauge: max pool size
  - `lettuce_pool_created_total` / `lettuce_pool_destroyed_total` — connection churn detection
- **Prometheus exporters**: Redis Exporter + PostgreSQL Exporter
- **Grafana dashboards**: Pre-configured datasource provisioning
- **Structured logging**: Topic-based Slf4j loggers, client disconnect vs infrastructure error classification

### 🛡️ Security

- **Authentication**: Google OAuth2 → JWT RS256 (asymmetric key pair)
- **Access Token**: Short-lived (15 min), stored in frontend JS memory
- **Refresh Token**: Long-lived (7 days), `HttpOnly` + `Secure` + `SameSite=Lax` cookie
- **Authorization**: Role-based (`USER`, `ADMIN`) với `@PreAuthorize` method-level
- **HTTP Security Headers**: X-Frame-Options: DENY, X-Content-Type-Options: nosniff, HSTS (1 year)
- **Input Validation**: Bean Validation (`@Valid`, `@NotNull`, `@Size`) trên mọi DTO
- **Global Exception Handler**: Catch-all handler, KHÔNG lộ stack trace ra client
- **CORS**: Configurable origins qua environment variable
- **UUIDv7**: Time-ordered IDs — không expose sequence numbers

### 🔧 Admin Panel (RBAC)

- **Dashboard**: System statistics, topic coverage analysis, crawl history
- **Document Management**: List/delete documents + associated vectors
- **Web Crawler**: Source management, page review (approve/reject), scheduled crawling
- **Job Management**: List scheduled jobs, trigger manual execution

---

## 📋 Prerequisites

- **Java 24** (JDK) — [Download](https://adoptium.net/)
- **Maven 3.9+** (hoặc dùng Maven Wrapper `./mvnw` đi kèm)
- **Docker & Docker Compose** — cho PostgreSQL, Redis, Qdrant, Monitoring
- **OpenSSL** — để generate RSA keys cho JWT
- **Google Cloud Console** — tạo OAuth2 Client ID
- **Gemini API Key** — từ [Google AI Studio](https://aistudio.google.com/)
- **Cohere API Key** — từ [Cohere Dashboard](https://dashboard.cohere.com/)

---

## 🚀 Quick Start

### 1. Clone & Copy Environment

```bash
git clone https://github.com/Thanhlovecode/chatbot-ai-backend.git
cd chatbot-ai-backend

# Copy template → fill in your real values
cp .env.example .env
```

> ⚠️ **Mở `.env` và điền đầy đủ API keys, passwords trước khi tiếp tục.**

### 🔑 2. Generate RSA Keys for JWT

Spring Boot cần cặp RSA key để ký và xác minh JWT. Chạy các lệnh sau để tự sinh key:

```bash
mkdir -p src/main/resources/keys
openssl genrsa -out src/main/resources/keys/private.pem 2048
openssl rsa -in src/main/resources/keys/private.pem -pubout -out src/main/resources/keys/public.pem
```

> 🔒 Thư mục `keys/` đã có trong `.gitignore` — key KHÔNG được commit lên Git.

### 3. Start Infrastructure (Dev Mode)

```bash
# Option 1: Sử dụng run script (Windows)
run-dev.bat

# Option 2: Docker Compose trực tiếp
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --remove-orphans
```

Services khởi động:
- **PostgreSQL** — `localhost:5432`
- **Redis** — `localhost:6379`
- **Qdrant** — `localhost:6333` (HTTP), `localhost:6334` (gRPC)
- **Prometheus** — `localhost:9090`
- **Grafana** — `localhost:3001` (admin/admin)

### 4. Run the Application

```bash
# Sử dụng Maven Wrapper (khuyến nghị)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Hoặc nếu đã install Maven globally
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Ứng dụng sẽ khởi động tại **http://localhost:8080**.

### 5. Verify

```bash
# Health check
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Resilience4j status
curl http://localhost:8080/actuator/circuitbreakers
curl http://localhost:8080/actuator/ratelimiters
curl http://localhost:8080/actuator/bulkheads
```

---

## 🔑 Environment Variables

| Variable | Description | Example |
|----------|-----------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `dev` / `prod` |
| `GEMINI_API_KEY` | Google Gemini API key | `AIza...` |
| `COHERE_API_KEY` | Cohere Rerank API key | `RO1M...` |
| `GOOGLE_CLIENT_ID` | Google OAuth2 Client ID | `4841...apps.googleusercontent.com` |
| `NVD_API_KEY` | NVD API key (OWASP Dependency-Check) | `c27c...` |
| `POSTGRES_USER` | PostgreSQL user (Docker init) | `postgres` |
| `POSTGRES_PASSWORD` | PostgreSQL password (Docker init) | `***` |
| `POSTGRES_DB` | PostgreSQL database (Docker init) | `chatbot` |
| `POSTGRES_PORT` | PostgreSQL port (Docker init) | `5432` |
| `DB_HOST` | PostgreSQL host (Spring app) | `localhost` |
| `DB_PORT` | PostgreSQL port (Spring app) | `5432` |
| `DB_NAME` | Database name (Spring app) | `chatbot` |
| `DB_USERNAME` | Database username (Spring app) | `postgres` |
| `DB_PASSWORD` | Database password (Spring app) | `***` |
| `REDIS_HOST` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | `***` |
| `QDRANT_HOST` | Qdrant host | `localhost` |
| `QDRANT_PORT` | Qdrant gRPC port | `6334` |
| `APP_COOKIE_SECURE` | Cookie Secure flag | `false` (dev) / `true` (prod) |
| `CORS_ALLOWED_ORIGINS` | Allowed CORS origins | `https://yourdomain.com` |
| `GF_ADMIN_USER` | Grafana admin username | `admin` |
| `GF_ADMIN_PASSWORD` | Grafana admin password | `admin` |
| `ADMIN_EMAILS` | Comma-separated admin emails (auto-seed on startup) | `admin@example.com` |

---

## 📁 Project Structure

```
chatbot-ai-backend/
├── src/main/java/dev/thanh/spring_ai/
│   ├── SpringAiApplication.java          # Entry point
│   ├── components/                        # Spring components
│   │   └── UuidV7Generator.java          # Time-ordered UUID generator
│   ├── config/                            # Configuration classes
│   │   ├── security/                      # Security config (JWT, CORS, OAuth2)
│   │   │   ├── SecurityConfig.java        # Main security filter chain
│   │   │   ├── CustomJwtDecoder.java      # JWT token validation
│   │   │   ├── JwtEncoderConfig.java      # RSA key pair encoder
│   │   │   └── ...
│   │   ├── AiConfig.java                  # ChatClient bean
│   │   ├── EmbeddingConfig.java           # Dual embedding models (Document/Query)
│   │   ├── VectorStoreConfig.java         # Dual Qdrant VectorStores
│   │   ├── DocumentSplitterConfig.java    # LangChain4j recursive splitter
│   │   ├── HybridRagProperties.java       # RAG pipeline properties
│   │   ├── RedisConfig.java               # Redis + Lettuce pool
│   │   ├── RedisStreamProperties.java     # Stream consumer config
│   │   ├── RateLimitProperties.java       # Token bucket + daily quota config
│   │   ├── SemanticCacheConfig.java       # Jedis pool + local embedding model beans
│   │   ├── SemanticCacheProperties.java   # HNSW tuning + cache behavior config
│   │   ├── CohereConfig.java             # Cohere Rerank client
│   │   ├── VirtualThreadConfig.java       # Virtual thread executor
│   │   ├── LazyDataSourceConfig.java      # Defer DB connection (BeanPostProcessor)
│   │   ├── LettucePoolMetricsConfig.java  # Lettuce pool → Prometheus metrics
│   │   ├── AdminSeeder.java              # Auto-seed admin users on startup
│   │   ├── AdminProperties.java          # Admin email configuration
│   │   ├── AsyncConfig.java              # Async executor configuration
│   │   ├── SchedulerConfig.java          # Scheduler thread pool
│   │   ├── RetryProperties.java          # Retry configuration
│   │   └── ...
│   ├── constants/                         # Application constants
│   │   └── TokenConstants.java            # JWT token constants
│   ├── controller/                        # REST API controllers
│   │   ├── AuthController.java            # Google OAuth login/refresh/logout
│   │   ├── ChatController.java            # Chat streaming (SSE)
│   │   ├── RagController.java             # RAG file upload
│   │   ├── AdminDashboardController.java  # Admin stats
│   │   ├── AdminCrawlerController.java    # Web crawler management
│   │   ├── AdminDocumentController.java   # Document management
│   │   └── AdminJobController.java        # Job management
│   ├── dto/                               # Request/Response DTOs
│   ├── entity/                            # JPA entities
│   │   ├── User.java                      # User (Google OAuth)
│   │   ├── Session.java                   # Chat session
│   │   ├── ChatMessage.java              # Chat message
│   │   ├── Document.java                 # RAG document metadata
│   │   ├── CrawlSource.java             # Web crawl source
│   │   ├── CrawlerPage.java             # Crawled page (pending/approved/rejected)
│   │   ├── JobExecution.java            # Scheduled job execution log
│   │   └── BaseEntity.java              # Audit fields (createdAt, updatedAt)
│   ├── enums/                             # Enum types
│   ├── event/                             # Application events
│   │   ├── ChatMessageEvent.java          # Message event payload
│   │   ├── SessionCreatedEvent.java       # Session creation event
│   │   └── SessionEventListener.java      # Event listener (session → ZSET)
│   ├── exception/                         # Global exception handling
│   │   ├── GlobalExceptionHandler.java    # Central error handler (no stack trace leak)
│   │   ├── RateLimitException.java        # 429 with Retry-After header
│   │   ├── ServiceDegradedException.java  # 503 for CB/Bulkhead rejection
│   │   ├── ResourceNotFoundException.java # 404
│   │   ├── BadRequestException.java       # 400
│   │   ├── SecurityAuthException.java     # Authentication errors
│   │   └── SessionException.java          # Session-specific errors
│   ├── repository/                        # Spring Data JPA repos
│   │   ├── BatchMessageRepository.java    # Native SQL batch insert (ON CONFLICT)
│   │   ├── BatchSessionRepository.java    # Batch session timestamp update
│   │   └── ...
│   ├── scheduler/                         # Scheduled tasks
│   │   ├── StreamConsumerScheduler.java   # Redis Stream consumer + recovery
│   │   ├── SessionSyncScheduler.java      # ZSET dirty sessions → DB sync
│   │   └── CrawlJobScheduler.java         # Periodic web crawling
│   ├── service/                           # Business logic
│   │   ├── security/                      # Auth & JWT services
│   │   │   ├── AuthenticationService.java # Google login + token rotation
│   │   │   ├── GoogleTokenVerifierService.java
│   │   │   └── TokenService.java          # JWT encode/decode
│   │   ├── ChatService.java               # Chat orchestration (main pipeline)
│   │   ├── LlmService.java               # Gemini streaming + Resilience4j
│   │   ├── RagService.java                # Hybrid RAG (index + search)
│   │   ├── RerankService.java             # Cohere cross-encoder reranking
│   │   ├── DocumentParserService.java     # Smart file parser (PDF/Tika)
│   │   ├── RedisStreamService.java        # Event-driven message pipeline
│   │   ├── ChatSessionService.java        # Session CRUD + history management
│   │   ├── SessionActivityService.java    # Dual ZSET activity tracking
│   │   ├── RateLimitService.java          # 2-layer rate limiting (pre-flight check + post-flight consume)
│   │   ├── SemanticCacheService.java      # Redis HNSW semantic cache (fail-open)
│   │   ├── ChatMetricsService.java        # Centralized Prometheus metrics
│   │   ├── SessionCacheService.java       # Session metadata cache
│   │   ├── DeadLetterQueueService.java    # DLQ handler
│   │   ├── PendingMessageRecoveryService.java  # Stale message recovery (XCLAIM)
│   │   ├── MessageProcessorService.java   # Stream entry → entity transform
│   │   ├── CrawlerService.java            # Web crawler logic
│   │   ├── CrawlSchedulerService.java     # Crawler job orchestration
│   │   ├── AdminDashboardService.java     # Dashboard statistics
│   │   ├── AdminDocumentService.java      # Document management
│   │   ├── AdminCrawlerService.java       # Crawler admin operations
│   │   ├── JobRegistryService.java        # Scheduled job registry
│   │   ├── LlmServicePort.java           # LLM interface (for mock injection)
│   │   └── RagServicePort.java           # RAG interface (for mock injection)
│   ├── test/                              # In-app test infrastructure
│   │   ├── MockLlmService.java            # LLM mock for load testing
│   │   ├── MockRagService.java            # RAG mock for load testing
│   │   ├── LoadTestController.java        # Load test auth bypass endpoint
│   │   └── LoadTestSecurityConfig.java    # Security config for load test profile
│   ├── tools/                             # AI function calling tools
│   │   └── JavaKnowledgeTools.java        # Agentic RAG tool (LLM-driven search)
│   └── utils/                             # Utility classes
│       ├── SafeRedisExecutor.java         # Centralized Redis CB wrapper
│       └── SecurityUtils.java             # Auth context helpers
├── src/main/resources/
│   ├── application.yaml                   # Base config (all environments)
│   ├── application-dev.yaml               # Dev profile
│   ├── application-prod.yaml              # Production profile
│   ├── application-test.yaml              # Test/load test profile
│   ├── db/migration/                      # Flyway migration scripts
│   │   ├── table/                         # Table creation migrations
│   │   └── index/                         # Index creation migrations
│   ├── lua/                               # Redis Lua scripts
│   │   ├── token_bucket.lua               # Atomic token bucket rate limiting
│   │   └── daily_quota.lua                # Atomic daily token quota check
│   ├── prompts/                           # AI prompt templates (StringTemplate)
│   │   └── agentic-system-prompt.st       # Agentic RAG system instructions
│   └── keys/                              # ⛔ Git-ignored RSA keys
├── src/test/                              # Test suites
│   ├── java/                              # JUnit 5 + Mockito tests
│   └── resources/                         # Test configurations
├── monitoring/                            # Observability stack
│   ├── prometheus.dev.yml                 # Prometheus config (dev targets)
│   ├── prometheus.prod.yml                # Prometheus config (prod targets)
│   └── grafana/provisioning/              # Grafana datasource auto-provisioning
├── loadtest/                              # Load testing
│   ├── stress-test-find-limit.js          # k6 stress test script
│   ├── seed-test-users.sql                # Test user seeding SQL
│   └── results/                           # Load test results
├── benchmark/                             # Performance benchmarks
│   └── README.md                          # Benchmark documentation
├── database/
│   └── init_schema.sql                    # Reference schema
├── Dockerfile                             # Multi-stage Docker build (layered JAR)
├── docker-compose.yml                     # Base infrastructure (PG, Redis, Qdrant)
├── docker-compose.dev.yml                 # Dev override (ports exposed, IDE mode)
├── docker-compose.prod.yml                # Prod override (app + monitoring + tuned PG)
├── run-dev.bat                            # Windows: start dev environment
├── run-prod.bat                           # Windows: start prod environment
├── pom.xml                                # Maven dependencies
├── .env.example                           # Environment variable template
├── .github/workflows/ci.yml               # CI pipeline (6-stage)
├── scripts/                               # Helper scripts
│   └── generate-test-keys.sh              # Generate ephemeral RSA keys for CI
└── README.md
```

---

## 🐳 Docker Deployment

### Multi-Environment Architecture

Dự án sử dụng **Docker Compose override pattern** (base + environment overlay):

```
docker-compose.yml        ← Base: PG, Redis, Qdrant (no ports exposed)
  └─ docker-compose.dev.yml   ← Dev: ports exposed, monitoring (host.docker.internal)
  └─ docker-compose.prod.yml  ← Prod: spring-ai app, monitoring, tuned PG/Redis
```

### Development (App chạy trên IDE)

```bash
# Windows
run-dev.bat

# Linux/macOS
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --remove-orphans
```

App chạy trên IDE (IntelliJ), infra trong Docker. Ports expose ra localhost.

### Production (Tất cả trong Docker)

```bash
# Windows
run-prod.bat

# Linux/macOS
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --remove-orphans
```

### Service Ports

| Service | Dev Port | Prod Port | Healthcheck |
|---------|----------|-----------|-------------|
| Spring Boot | IDE (8080) | `8080` | `/actuator/health` |
| PostgreSQL 16 | `5432` | Internal only | `pg_isready` |
| Redis 8 | `6379` | Internal only | `redis-cli ping` |
| Qdrant 1.13 | `6333`/`6334` | Internal only | TCP check |
| Prometheus | `9090` | Internal only | — |
| Grafana | `3001` | `3001` | — |
| Redis Exporter | `9121` | Internal only | — |
| PG Exporter | `9187` | Internal only | — |

### Production PostgreSQL Tuning

```
shared_buffers=512MB    effective_cache_size=1536MB    work_mem=8MB
maintenance_work_mem=256MB    wal_buffers=16MB    max_wal_size=1GB
checkpoint_completion_target=0.9    random_page_cost=1.1
jit=off    log_min_duration_statement=250ms
```

### Production Redis Tuning

```
maxmemory 768mb    maxmemory-policy allkeys-lru    appendonly yes
appendfsync everysec    tcp-backlog 1024    hz 50
```

### Resource Limits

| Service | Dev (CPU/RAM) | Prod (CPU/RAM) |
|---------|---------------|----------------|
| Spring Boot | IDE | 1.0 core / 1024 MB |
| PostgreSQL | 0.25 / 256 MB | 1.0 core / 2048 MB |
| Redis | 0.50 / 768 MB | 0.50 / 1024 MB |
| Qdrant | 0.25 / 256 MB | 1.0 core / 1024 MB |
| Prometheus | 0.25 / 256 MB | 0.50 / 512 MB |
| Grafana | 0.25 / 256 MB | 0.50 / 512 MB |

---

## 📖 API Endpoints

### 🔐 Authentication (`/api/v1/auth`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| `POST` | `/auth/google` | Login with Google OAuth2 ID token | ❌ |
| `POST` | `/auth/refresh` | Rotate tokens (uses HttpOnly cookie) | 🍪 |
| `POST` | `/auth/logout` | Logout + clear cookie | 🔑 |

### 💬 Chat (`/api/v1/chat`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| `POST` | `/chat/stream` | Send message, receive SSE stream response | 🔑 |
| `GET` | `/chat/sessions` | List user sessions (cursor pagination) | 🔑 |
| `GET` | `/chat/{sessionId}/messages` | Get messages (cursor pagination) | 🔑 |
| `DELETE` | `/chat/sessions/{sessionId}` | Delete a session | 🔑 |
| `PUT` | `/chat/sessions/{sessionId}/title` | Rename session | 🔑 |

### 📄 RAG (`/api/v1/rag`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| `POST` | `/rag/file` | Upload document for RAG ingestion (max 50MB) | 🔑 |

Supported file types: `PDF`, `DOC`, `DOCX`, `TXT`, `CSV`, `RTF`, `HTML`, `HTM`, `ODT`

### 🛡️ Admin (`/api/v1/admin/*`) — ADMIN role required

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/admin/dashboard/stats` | Dashboard statistics |
| `GET` | `/admin/dashboard/topics` | Topic coverage analysis |
| `GET` | `/admin/dashboard/crawl-history` | Crawl history |
| `GET` | `/admin/documents` | List documents (paginated) |
| `DELETE` | `/admin/documents/{id}` | Delete document + vectors |
| `GET` | `/admin/crawler/pages` | List crawler pages |
| `POST` | `/admin/crawler/pages/{id}/approve` | Approve crawled page |
| `POST` | `/admin/crawler/pages/{id}/reject` | Reject crawled page |
| `POST` | `/admin/crawler/pages/approve-all` | Approve all pending |
| `GET` | `/admin/crawler/sources` | List crawl sources |
| `POST` | `/admin/crawler/sources` | Add crawl source |
| `PUT` | `/admin/crawler/sources/{id}` | Update crawl source |
| `POST` | `/admin/crawler/sources/{id}/crawl-now` | Trigger immediate crawl |
| `GET` | `/admin/jobs` | List scheduled jobs |
| `POST` | `/admin/jobs/{jobId}/run` | Trigger a job manually |

### 📊 Actuator Endpoints

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Application health (including CB, RL, BH) |
| `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `/actuator/circuitbreakers` | Circuit Breaker states |
| `/actuator/ratelimiters` | RateLimiter states |
| `/actuator/bulkheads` | Bulkhead states |

---

## 🧪 Testing

```bash
# Run all unit tests
./mvnw test

# Run with Testcontainers (requires Docker)
./mvnw verify
```

### Test Frameworks

| Framework | Purpose |
|-----------|---------|
| **JUnit 5 + Mockito** | Unit tests |
| **Testcontainers** | PostgreSQL, Redis integration tests |
| **WireMock 3.10** | Mock external HTTP APIs (Gemini, Cohere) |
| **Spring Security Test** | `@WithMockUser` for auth tests |
| **Reactor Test** | `StepVerifier` for reactive Flux/Mono tests |

### Mock Services for Load Testing

Dự án cung cấp **mock implementations** cho LLM và RAG services, activated qua profile:

```yaml
# application-test.yaml
llm.mock.enabled: true        # → MockLlmService (simulated streaming)
rag.mock.enabled: true         # → MockRagService (simulated search)
```

Cho phép stress test toàn bộ pipeline (Auth → Rate Limit → Redis Stream → DB) mà không tốn API quota.

### Load Testing (k6)

```bash
# Seed test users
psql -f loadtest/seed-test-users.sql

# Run stress test
k6 run loadtest/stress-test-find-limit.js
```

---

## 🔄 CI/CD Pipeline

Dự án sử dụng **GitHub Actions** với pipeline 6 stages:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                        CI Pipeline — Spring AI Chatbot                     │
├──────────┬─────────────────────────────────────────────────────────────────┤
│ Stage 0  │ 🔒 Secret Scanning (TruffleHog — block if credentials leaked)  │
├──────────┼─────────────────────────────────────────────────────────────────┤
│ Stage 1  │ 🧪 Unit Tests (JUnit 5, parallel, JaCoCo coverage)             │
├──────────┼─────────────────────────────────────────────────────────────────┤
│ Stage 2  │ 🔗 Integration Tests (Testcontainers: PostgreSQL + Redis)      │
├──────────┼─────────────────────────────────────────────────────────────────┤
│ Stage 3  │ 🔍 SAST — parallel:                                            │
│          │   • SpotBugs + FindSecBugs (static code analysis)              │
│          │   • PMD (code quality rules)                                    │
│          │   • OWASP Dependency-Check (CVE scan, failOnCVSS ≥ 7)          │
├──────────┼─────────────────────────────────────────────────────────────────┤
│ Stage 4  │ 📊 SonarQube Quality Gate (coverage + code smells)             │
├──────────┼─────────────────────────────────────────────────────────────────┤
│ Stage 5  │ 🐳 Docker Build + Trivy Scan (PR: local only / Push: push)     │
└──────────┴─────────────────────────────────────────────────────────────────┘
```

**PR workflow**: Stage 0 → 1 → 2 + 3 (parallel) → 4 → 5 (build + scan, no push)
**Merge to main**: Stage 0 → 6 (rebuild + Trivy scan + Docker Hub push)

### CI Secrets Required

| Secret | Purpose |
|--------|---------|
| `NVD_API_KEY` | OWASP Dependency-Check NVD database |
| `SONAR_TOKEN` | SonarQube authentication |
| `SONAR_HOST_URL` | SonarQube server URL |
| `DOCKER_USERNAME` | Docker Hub registry login |
| `DOCKER_PASSWORD` | Docker Hub registry password |

---

## 🏗️ Design Patterns Applied

| Pattern | Implementation |
|---------|----------------|
| **Circuit Breaker** | `SafeRedisExecutor` (Redis), `LlmService` (Gemini) — Resilience4j |
| **Bulkhead** | DB fallback isolation (HikariCP protection), LLM concurrent limit |
| **4-Tier Priority Strategy** | CRITICAL → DB fallback, NON-CRITICAL → reject 503, FIRE-AND-FORGET → skip, FAIL-OPEN → return null |
| **Event-Driven (Producer-Consumer)** | Redis Streams Consumer Group → batch insert PostgreSQL |
| **Dead Letter Queue** | Failed messages → `chat:messages:dlq` → manual investigation |
| **Token Bucket (Lua atomic)** | Redis Lua script — atomic refill + consume, zero race conditions |
| **Cache-Aside + Write-Behind** | Session ZSET (fast reads) + dirty tracking ZSET → background scheduler batch sync to DB |
| **Port/Adapter** | `LlmServicePort`, `RagServicePort` → mock injection for load testing |
| **Lazy Initialization** | `LazyConnectionDataSourceProxy` — defer DB connection until first SQL (saves pool for cache-hit paths) |
| **BeanPostProcessor** | Wrap HikariDataSource transparently without overriding auto-config |
| **Agentic Tool Calling** | LLM decides when to invoke RAG search via function calling (vs naive "always search") |
| **Dual Embedding** | Separate `RETRIEVAL_DOCUMENT` / `RETRIEVAL_QUERY` task-type embeddings for optimal retrieval |

---

## 🔄 Chat Request Data Flow

```
Client (SSE Request)
  │
  ▼
┌─────────────────────────────────────────────────────────────────┐
│ 1. Rate Limit Check                                              │
│    ├── Token Bucket (Lua) → anti-spam                           │
│    └── Daily Quota (pre-flight GET only, no increment)          │
├─────────────────────────────────────────────────────────────────┤
│ 2. Gemini LLM Stream (SSE)                                      │
│    └── LLM tự quyết định: cần tra cứu knowledge base?          │
│        │                                                         │
│        ├── KHÔNG cần tool → LLM trả lời bằng general knowledge  │
│        │                                                         │
│        └── CẦN tool → gọi searchJavaSpringBootDocs()            │
│            │                                                     │
│            ├── 2a. Semantic Cache Lookup (inside tool)           │
│            │   ├── MiniLM-L6-v2 embed query (~1ms)              │
│            │   └── Redis HNSW FT.SEARCH                         │
│            │       ├── HIT → return cached (~10ms) ← skip RAG  │
│            │       └── MISS → continue ▼                        │
│            │                                                     │
│            ├── 2b. Full RAG Pipeline                             │
│            │   ├── Qdrant Vector Search (top-K candidates)      │
│            │   └── Cohere Rerank (cross-encoder, top-N)         │
│            │                                                     │
│            └── 2c. Cache Store (fire-and-forget)                │
│                └── Embed & index result → Redis HNSW            │
├─────────────────────────────────────────────────────────────────┤
│ 3. Async Post-Processing (fire-and-forget on virtual threads)   │
│    ├── Redis Stream XADD (user + assistant messages)            │
│    ├── ZSET touch session activity                               │
│    ├── Daily Quota consume (actual tokens from Gemini metadata) │
│    └── History cache update (LRU 20 messages)                   │
├─────────────────────────────────────────────────────────────────┤
│ 4. Background Workers (scheduled)                                │
│    ├── Stream Consumer Group → batch INSERT PostgreSQL           │
│    ├── Session Sync Scheduler → ZPOPMIN dirty → batch UPDATE DB │
│    └── Pending Recovery → XCLAIM stale messages → retry          │
└─────────────────────────────────────────────────────────────────┘
```

> **Lưu ý**: Semantic Cache nằm **bên trong tool** (`JavaKnowledgeTools`), KHÔNG phải bước riêng trước LLM.
> Nếu câu hỏi không cần tool (casual, non-Java), LLM trả lời trực tiếp — hoàn toàn bypass RAG + cache.

---

## ⚡ Performance Benchmarks

> Đầy đủ chi tiết xem tại [`benchmark/README.md`](benchmark/README.md)

| Metric | Value | Condition |
|--------|-------|-----------|
| **Throughput** | ~390 rps | 2,000 VU, Mock LLM |
| **Latency p50** | 2.09s | Mock LLM (TTFB 300-2000ms) |
| **Semantic Cache hit** | ~10ms | vs ~2-5s full RAG pipeline |
| **Local embedding** | ~1ms | MiniLM-L6-v2 ONNX, 384-dim |
| **Saturation point** | ~2,800 VU | k6 tách máy, single instance |
| **Capacity estimate** | ~10,000 users online | single instance, think-time 60s |
| **System CPU** | 78.3% | @ 2,000 VU (k6 tách máy) |
| **HikariCP pending** | 0 | with Bulkhead protection |
| **CB state** | CLOSED ✅ | @ 2,000 VU (k6 tách máy) |

### Key Findings

- **Bulkhead** bảo vệ DB pool hoàn hảo: HikariCP pending = 0, timeout = 0
- **4-Tier Priority** hoạt động đúng: 0 message loss, non-critical rejected gracefully
- **k6 cùng máy** tranh ~20% CPU → tách ra tăng capacity **+55%**
- **Recovery tự động** ~30s khi load giảm (CB HALF_OPEN → CLOSED)

---

## 🔗 Related Repositories

| Repository | Description |
|-----------|-------------|
| [chatbot-ai-frontend](https://github.com/Thanhlovecode/chatbot-ai-frontend) | Vite + TypeScript frontend |

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
