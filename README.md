# 🎟️ FlashTicket Backend — Guia Prático de Engenharia Backend (Java + Spring Boot)

Plataforma completa de **Eventos, Ingressos e Pagamentos** construída passo a passo em **9 Fases** (`v0.1.0` a `v1.0.0`) para praticar **31 conceitos fundamentais e avançados de Engenharia Backend**.

---

## 🗺️ Mapa dos 31 Conceitos Implementados

| Fase | Versão (Tag) | Conceitos Praticados no Código |
| :--- | :---: | :--- |
| **Fase 1** | `v0.1.0` / `v0.2.0` | Git Flow, **SemVer**, **Dependency Hell** (`<dependencyManagement>`, `<exclusions>`, `maven-enforcer-plugin`), **SOLID** (Strategy + Ports & Adapters), **API RESTful** (RFC 7807 `ProblemDetail`) e **Versionamento de API** (`/v1`, `/v2` e Header `X-API-Version`). |
| **Fase 2** | `v0.3.0` | **Migrations com Flyway** (`V1`, `V2`), **Índices Compostos B-Tree** (`EXPLAIN`), **Transações ACID (`@Transactional`)**, **Lock Otimista (`@Version`)** e **Lock Pessimista (`SELECT ... FOR UPDATE`)** contra *overselling*. |
| **Fase 3** | `v0.4.0` | **GraphQL** (`schema.graphqls`, `@QueryMapping`), **Problema das Queries N+1** (`@SchemaMapping`) e solução com **DataLoader (`@BatchMapping`)** + `WHERE event_id IN (...)`. |
| **Fase 4** | `v0.5.0` | **NoSQL Orientado a Documentos (MongoDB)** para Trilha de Auditoria (`flexiblePayload`, `@CompoundIndex`, **TTL Index 90d**) e **Cache Distribuído/Local (Redis & Caffeine)** com padrão **Cache-Aside** (`@Cacheable`, `@CacheEvict` e TTL de 5 min). |
| **Fase 5** | `v0.6.0` | **JWT Stateless (HMAC-SHA256)**, **Refresh Token Rotation** com detecção de roubo em cadeia (`familyId`), **OAuth 2.0** (*Authorization Code + PKCE S256* e *Client Credentials M2M*), **CORS**, **CSRF (*Double-Submit Cookie*)**, **XSS (`XssSanitizer` + CSP)** e **Rate Limiter (*Token Bucket*)**. |
| **Fase 6** | `v0.7.0` | **Programação Assíncrona** (`@Async`, `CompletableFuture`, `ThreadPoolTaskExecutor`), **Webhooks Inbound** (`HMAC-SHA256` + **Idempotência**), **Webhooks Outbound** e **Cron Jobs (`@Scheduled`)** para expirar reservas `PENDING`. |
| **Fase 7** | `v0.8.0` | **Timeouts** (*Connect/Read/Execution*), **Retries com *Exponential Backoff + Jitter***, **Circuit Breaker** (`CLOSED` -> `OPEN` -> `HALF_OPEN`), **Bulkhead (`Semaphore`)** e **Fallback** (Fila de Contingência `202 Accepted`). |
| **Fase 8** | `v0.9.0` | **Logs Estruturados SLF4J + MDC (`Correlation-ID`)**, **Health Checks** (*Liveness* vs *Readiness*), **Memória JVM (*Stack* vs *Heap*: Eden/Survivor/Old Gen)**, **Garbage Collection**, laboratório de **Memory Leak** e **CLI com Picocli**. |
| **Fase 9** | `v1.0.0` | **CI/CD com GitHub Actions** (`.github/workflows/ci-cd.yml`) automatizando validação de dependências, testes de integração, empacotamento e publicação de Release SemVer `v1.0.0`. |

---

## 🚀 Como Executar Localmente

### Opção 1: Modo Rápido (Sem precisar de Docker aberto)
Usa H2 em modo de compatibilidade PostgreSQL + Caffeine Cache + NoSQL Adapter em memória:
```powershell
.\mvnw.cmd spring-boot:run
```

### Opção 2: Modo Infraestrutura Completa com Docker (PostgreSQL + MongoDB + Redis)
```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

### Executar todos os testes automatizados
```powershell
.\mvnw.cmd test
```

### Testar todas as 46 requisições interativas
Abra o arquivo [`http/flashticket.http`](http/flashticket.http) no IntelliJ IDEA ou VS Code.
