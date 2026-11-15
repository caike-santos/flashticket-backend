package com.flashticket.infrastructure.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * TÓPICO (Fase 4): CACHE (Padrão Cache-Aside, TTL e Invalidação)
 * - @EnableCaching: Habilita os interceptadores (Proxies) para @Cacheable, @CacheEvict e @CachePut.
 * - TTL (Time-To-Live) de 5 minutos: Garante que dados em cache expirem sozinhos mesmo se ninguém invalidar.
 * - Quando rodamos com Docker (profile 'docker' ou 'postgres'), usa o Redis Distribuído (RedisCacheManager).
 * - Quando rodamos sem Docker (profile default/test), usa o Caffeine em memória RAM com o mesmo TTL e métricas!
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String EVENTS_LIST_CACHE = "events-list";
    public static final String EVENT_BY_ID_CACHE = "event-by-id";

    @Bean
    @Primary
    @Profile("!docker & !postgres")
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(EVENTS_LIST_CACHE, EVENT_BY_ID_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1000)
                .recordStats());
        return cacheManager;
    }

    @Bean
    @Profile({"docker", "postgres"})
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues();

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
