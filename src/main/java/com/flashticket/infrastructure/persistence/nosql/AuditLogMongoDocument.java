package com.flashticket.infrastructure.persistence.nosql;

import com.flashticket.domain.model.AuditLog;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * TÓPICO (Fase 4): Bancos Não Relacionais (MongoDB) + Índices no NoSQL
 * - @Document(collection = "audit_logs"): Mapeia este objeto para uma Coleção de Documentos BSON/JSON no MongoDB.
 * - @CompoundIndex: Cria um índice composto no MongoDB (1 = ASC, -1 = DESC).
 * - @Indexed(expireAfter = "90d"): Índice TTL nativo do MongoDB! Apaga documentos antigos automaticamente após 90 dias.
 */
@Document(collection = "audit_logs")
@CompoundIndex(name = "idx_audit_action_created", def = "{'actionType': 1, 'createdAt': -1}")
public class AuditLogMongoDocument {

    @Id
    private String id;

    private String actionType;

    @Indexed(name = "idx_audit_resource_id")
    private String resourceId;

    private String actor;

    @Indexed(name = "idx_audit_ttl_90d", expireAfter = "90d")
    private Instant createdAt;

    /**
     * Schema Flexível (Schema-less):
     * Cada documento na mesma coleção 'audit_logs' pode ter chaves e sub-objetos
     * completamente diferentes dentro deste Map sem precisar de Migrations!
     */
    private Map<String, Object> flexiblePayload;

    public AuditLogMongoDocument() {
    }

    public AuditLogMongoDocument(
            String id,
            String actionType,
            String resourceId,
            String actor,
            Instant createdAt,
            Map<String, Object> flexiblePayload) {
        this.id = id;
        this.actionType = actionType;
        this.resourceId = resourceId;
        this.actor = actor;
        this.createdAt = createdAt;
        this.flexiblePayload = flexiblePayload;
    }

    public static AuditLogMongoDocument fromDomain(AuditLog domain) {
        return new AuditLogMongoDocument(
                domain.id(),
                domain.actionType(),
                domain.resourceId(),
                domain.actor(),
                domain.createdAt(),
                domain.flexiblePayload()
        );
    }

    public AuditLog toDomain() {
        return new AuditLog(
                this.id,
                this.actionType,
                this.resourceId,
                this.actor,
                this.createdAt,
                this.flexiblePayload
        );
    }
}
