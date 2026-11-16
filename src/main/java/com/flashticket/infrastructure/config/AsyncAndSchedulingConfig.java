package com.flashticket.infrastructure.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * TÓPICOS (Fase 6):
 * 1. Programação Assíncrona (@EnableAsync + ThreadPoolTaskExecutor customizado)
 * 2. Tarefas Agendadas / Cron Jobs (@EnableScheduling)
 *
 * Por que configurar um ThreadPoolTaskExecutor próprio em vez de usar o padrão do Spring?
 * - Por padrão, o @Async sem pool configurado usa o SimpleAsyncTaskExecutor, que cria uma
 *   nova thread do zero a cada chamada sem limite máximo — podendo estourar a memória RAM
 *   (OutOfMemoryError: unable to create native thread) sob alto tráfego!
 * - Com o ThreadPoolTaskExecutor, controlamos o número de threads (corePoolSize, maxPoolSize)
 *   e o tamanho da fila de espera (queueCapacity), além de dar um prefixo claro ("FlashTicket-Async-")
 *   para facilitar o debug nos logs.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncAndSchedulingConfig {

    public static final String TICKET_ASYNC_EXECUTOR = "ticketAsyncExecutor";

    @Bean(name = TICKET_ASYNC_EXECUTOR)
    public Executor ticketAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("FlashTicket-Async-");
        executor.initialize();
        return executor;
    }
}
