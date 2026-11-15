package com.flashticket.api.controller;

import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TÓPICO (Fase 4): Consulta de Documentos NoSQL (MongoDB)
 * Retorna a trilha de auditoria onde cada documento possui um 'flexiblePayload' (JSON) diferente.
 */
@RestController
@RequestMapping("/api/v2/audit-logs")
public class AuditLogController {

    private final AuditLogRepositoryPort auditLogRepository;

    public AuditLogController(AuditLogRepositoryPort auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public ResponseEntity<List<AuditLog>> listAuditLogs(
            @RequestParam(required = false) String resourceId) {
        List<AuditLog> logs = (resourceId == null || resourceId.isBlank())
                ? auditLogRepository.findAllRecent()
                : auditLogRepository.findByResourceId(resourceId);
        return ResponseEntity.ok(logs);
    }
}
