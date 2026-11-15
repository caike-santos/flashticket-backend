package com.flashticket.infrastructure.persistence.nosql;

import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryAuditLogRepositoryAdapter implements AuditLogRepositoryPort {

    private final List<AuditLog> documents = new CopyOnWriteArrayList<>();

    @Override
    public AuditLog save(AuditLog auditLog) {
        documents.add(auditLog);
        return auditLog;
    }

    @Override
    public List<AuditLog> findByResourceId(String resourceId) {
        return documents.stream()
                .filter(doc -> doc.resourceId().equals(resourceId))
                .sorted(Comparator.comparing(AuditLog::createdAt).reversed())
                .toList();
    }

    @Override
    public List<AuditLog> findAllRecent() {
        return documents.stream()
                .sorted(Comparator.comparing(AuditLog::createdAt).reversed())
                .limit(50)
                .toList();
    }
}
