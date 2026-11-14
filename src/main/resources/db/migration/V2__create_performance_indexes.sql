-- =============================================================================
-- TÓPICO: ÍNDICES NO BANCO DE DADOS - V2: Índices B-Tree Simples e Compostos
-- =============================================================================
-- Regra da Esquerda para a Direita (Leftmost Prefix Rule) em Índices Compostos:
-- O índice (category, event_date) acelera TANTO buscas apenas por 'category'
-- QUANTO buscas por 'category' + ordenação/filtro por 'event_date'!

CREATE INDEX idx_events_category_event_date
    ON events (category, event_date);

-- Índice para acelerar o Cron Job que busca pedidos PENDING vencidos (status + expires_at)
CREATE INDEX idx_ticket_orders_status_expires_at
    ON ticket_orders (status, expires_at);

-- Índice para busca rápida do histórico de compras de um cliente por e-mail
CREATE INDEX idx_ticket_orders_customer_email
    ON ticket_orders (customer_email);

-- Índice na Foreign Key (PostgreSQL não cria índice automático em FKs, apenas em PKs/UNIQUE!)
CREATE INDEX idx_ticket_orders_event_id
    ON ticket_orders (event_id);
