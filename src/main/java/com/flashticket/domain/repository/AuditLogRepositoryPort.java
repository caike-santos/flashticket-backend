package com.flashticket.domain.repository;

import com.flashticket.domain.model.AuditLog;
import java.util.List;

public interface AuditLogRepositoryPort {

    AuditLog save(AuditLog auditLog);

    List<AuditLog> findByResourceId(String resourceId);

    List<AuditLog> findAllRecent();
}
