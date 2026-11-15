package com.flashticket.infrastructure.persistence.nosql;

import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * TÓPICO (Fase 4): Adapter Real do MongoDB (Ativo quando rodamos com Docker: profile 'docker' ou 'postgres')
 * Persiste os documentos BSON diretamente na coleção 'audit_logs' do MongoDB.
 */
@Profile({"docker", "postgres"})
@Primary
@Repository
public class MongoAuditLogRepositoryAdapter implements AuditLogRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoAuditLogRepositoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public AuditLog save(AuditLog auditLog) {
        AuditLogMongoDocument saved = mongoTemplate.save(AuditLogMongoDocument.fromDomain(auditLog));
        return saved.toDomain();
    }

    @Override
    public List<AuditLog> findByResourceId(String resourceId) {
        Query query = new Query(Criteria.where("resourceId").is(resourceId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, AuditLogMongoDocument.class).stream()
                .map(AuditLogMongoDocument::toDomain)
                .toList();
    }

    @Override
    public List<AuditLog> findAllRecent() {
        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(50);
        return mongoTemplate.find(query, AuditLogMongoDocument.class).stream()
                .map(AuditLogMongoDocument::toDomain)
                .toList();
    }
}
