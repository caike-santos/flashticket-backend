package com.flashticket.infrastructure.resilience;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * TÓPICO (Fase 7): Timeouts (Connect Timeout vs Read Timeout) em Clientes HTTP.
 *
 * Por que NUNCA usar um cliente HTTP sem configurar Timeouts?
 * - Connect Timeout: Tempo máximo para estabelecer o handshake TCP com o servidor externo.
 *   Se o servidor externo estiver desligado ou bloqueado por firewall, sem Connect Timeout
 *   a thread pode ficar travada por minutos!
 * - Read Timeout (Socket Timeout): Tempo máximo esperando o servidor externo responder depois
 *   que a conexão TCP já abriu. Se o Gateway de Pagamento travar num deadlock, sem Read Timeout
 *   as threads da nossa API ficam presas eternamente até derrubar o servidor!
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public RestClient paymentGatewayRestClient(
            @Value("${flashticket.resilience.http.connect-timeout-ms:1500}") long connectTimeoutMs,
            @Value("${flashticket.resilience.http.read-timeout-ms:2000}") long readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
