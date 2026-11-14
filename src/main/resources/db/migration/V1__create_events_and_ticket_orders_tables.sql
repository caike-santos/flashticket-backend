-- =============================================================================
-- TÓPICO: MIGRATIONS (Flyway) - V1: Criação das Tabelas Principais e Constraints ACID
-- =============================================================================

CREATE TABLE events (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    category VARCHAR(50) NOT NULL,
    event_date TIMESTAMP NOT NULL,
    total_capacity INT NOT NULL CHECK (total_capacity >= 1),
    available_tickets INT NOT NULL CHECK (available_tickets >= 0),
    base_price NUMERIC(12, 2) NOT NULL CHECK (base_price >= 0),
    platform_fee NUMERIC(12, 2) NOT NULL CHECK (platform_fee >= 0),
    -- Coluna 'version' usada pelo JPA (@Version) para Lock Otimista contra venda duplicada
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE ticket_orders (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    customer_email VARCHAR(255) NOT NULL,
    quantity INT NOT NULL CHECK (quantity >= 1),
    unit_price NUMERIC(12, 2) NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ticket_orders_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE
);
