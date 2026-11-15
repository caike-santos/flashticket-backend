package com.flashticket.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * TÓPICO (Fase 4): Bancos Não Relacionais (NoSQL Orientado a Documentos - MongoDB)
 * Diferente de uma tabela SQL com colunas fixas, um Documento NoSQL permite armazenar
 * um 'flexiblePayload' (JSON dinâmico) que muda de estrutura conforme o tipo de evento auditado.
 */
public record AuditLog(
        String id,
        String actionType,
        String resourceId,
        String actor,
        Instant createdAt,
        Map<String, Object> flexiblePayload
) {
    public static AuditLog create(
            String actionType,
            String resourceId,
            String actor,
            Map<String, Object> flexiblePayload) {
        return new AuditLog(
                UUID.randomUUID().toString(),
                actionType,
                resourceId,
                actor,
                Instant.now(),
                flexiblePayload
        );
    }
}
