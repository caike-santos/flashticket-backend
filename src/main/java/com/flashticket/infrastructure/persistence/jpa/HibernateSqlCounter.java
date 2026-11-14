package com.flashticket.infrastructure.persistence.jpa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

/**
 * TÓPICO (Fase 3): Diagnóstico de Queries N+1
 * Intercepta cada SQL gerado pelo Hibernate para contarmos exatamente quantos SELECTs
 * foram disparados na abordagem com N+1 vs na abordagem otimizada com @BatchMapping (DataLoader).
 */
@Component
public class HibernateSqlCounter implements StatementInspector {

    private static final AtomicInteger selectCount = new AtomicInteger(0);
    private static final List<String> executedQueries = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String inspect(String sql) {
        if (sql != null && sql.trim().toLowerCase().startsWith("select")) {
            selectCount.incrementAndGet();
            executedQueries.add(sql);
        }
        return sql;
    }

    public static void reset() {
        selectCount.set(0);
        executedQueries.clear();
    }

    public static int getSelectCount() {
        return selectCount.get();
    }

    public static List<String> getExecutedQueries() {
        return new ArrayList<>(executedQueries);
    }
}
