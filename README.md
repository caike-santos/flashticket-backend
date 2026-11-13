# 🎟️ FlashTicket Backend

Plataforma de venda de ingressos, gestão de eventos e processamento de pagamentos construída em **Java + Spring Boot** para treinamento prático de **31 conceitos fundamentais e avançados de Engenharia de Backend**.

## 📌 Minimundo e Regras de Negócio
1. **Organizadores** criam **Eventos** e **Lotes de Ingressos** com estoque limitado e estratégias de precificação/taxas (**SOLID**).
2. **Clientes** reservam ingressos gerando **Pedidos (`PENDING`)** com garantia transacional (**ACID**, locks otimistas/pessimistas) contra venda duplicada (*overselling*).
3. Pagamentos comunicam-se com um Gateway Externo Simulado aplicando **Timeouts**, **Retries**, **Circuit Breaker**, **Programação Assíncrona** e **Webhooks (HMAC)**.
4. **Cron Jobs** liberam reservas expiradas automaticamente.
5. **Redis** provê **Cache**, **Rate Limiting** e rotação/revogação de **Refresh Tokens (JWT)**.
6. **MongoDB** armazena logs de auditoria flexíveis (**NoSQL**).

## 🚀 Como diagnosticar a Árvore de Dependências (Dependency Hell)
```bash
./mvnw dependency:tree
./mvnw enforcer:enforce
```
