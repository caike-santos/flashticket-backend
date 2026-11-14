package com.flashticket.api.controller;

import com.flashticket.application.service.DatabaseDiagnosticsService;
import com.flashticket.domain.model.EventCategory;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/diagnostics")
public class DatabaseDiagnosticsController {

    private final DatabaseDiagnosticsService diagnosticsService;

    public DatabaseDiagnosticsController(DatabaseDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping("/flyway")
    public ResponseEntity<List<Map<String, Object>>> getAppliedMigrations() {
        return ResponseEntity.ok(diagnosticsService.getAppliedFlywayMigrations());
    }

    @GetMapping("/query-plan")
    public ResponseEntity<Map<String, Object>> explainQueryPlan(
            @RequestParam(defaultValue = "CONCERT") EventCategory category) {
        List<String> plan = diagnosticsService.explainIndexedEventQuery(category);
        return ResponseEntity.ok(Map.of(
                "query", "SELECT * FROM events WHERE category = '" + category + "' ORDER BY event_date ASC",
                "indexExpected", "idx_events_category_event_date",
                "executionPlan", plan
        ));
    }
}
