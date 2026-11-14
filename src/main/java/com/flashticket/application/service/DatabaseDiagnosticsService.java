package com.flashticket.application.service;

import com.flashticket.domain.model.EventCategory;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * TÓPICOS (Fase 2):
 * - Inspeção do histórico de Migrations aplicadas pelo Flyway (flyway_schema_history)
 * - Diagnóstico de Índices com EXPLAIN para comprovar o uso de Index Scan ao invés de Full Table Scan
 */
@Service
public class DatabaseDiagnosticsService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseDiagnosticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> getAppliedFlywayMigrations() {
        String sql = """
                SELECT installed_rank, version, description, script, installed_on, success
                FROM "flyway_schema_history"
                WHERE version IS NOT NULL
                ORDER BY installed_rank ASC
                """;
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception ex) {
            return jdbcTemplate.queryForList(
                    "SELECT installed_rank, version, description, script, installed_on, success "
                            + "FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank ASC");
        }
    }

    public List<String> explainIndexedEventQuery(EventCategory category) {
        String sql = "EXPLAIN SELECT * FROM events WHERE category = ? ORDER BY event_date ASC";
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString(1),
                category.name());
    }
}
