package com.flashticket.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashticket.application.service.AsyncTicketFulfillmentService;
import com.flashticket.application.service.EventService;
import com.flashticket.application.service.PaymentWebhookService;
import com.flashticket.application.service.TicketPurchaseService;
import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import com.flashticket.domain.model.TicketOrder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AsyncWebhookAndCronIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventService eventService;

    @Autowired
    private TicketPurchaseService ticketPurchaseService;

    @Autowired
    private PaymentWebhookService paymentWebhookService;

    @Autowired
    private AsyncTicketFulfillmentService asyncFulfillmentService;

    @Test
    @DisplayName("Fase 6.1 & 6.2: Deve validar assinatura HMAC-SHA256 do Webhook, garantir Idempotência, disparar @Async em thread separada e enviar Webhook Outbound")
    void shouldValidateWebhookHmacEnsureIdempotencyAndExecuteAsyncFulfillment() throws Exception {
        // 1. Cria um evento e reserva 2 ingressos (pedido nasce PENDING)
        Event event = eventService.createEvent(
                "Show Coldplay - Webhook & Async",
                "Teste de Webhook HMAC e emissao assincrona de QR Code",
                EventCategory.CONCERT,
                LocalDateTime.now().plusDays(15),
                100,
                new BigDecimal("300.00")
        );

        TicketOrder order = ticketPurchaseService.purchaseTickets(
                event.getId(),
                "comprador.webhook@flashticket.com",
                2,
                false,
                false
        );

        String webhookEventId = "evt_stripe_" + UUID.randomUUID();
        String webhookBody = String.format("""
                {
                  "webhookEventId": "%s",
                  "orderId": "%s",
                  "paymentStatus": "PAID"
                }
                """, webhookEventId, order.getId());

        // 2. Tentativa de ataque: envia Webhook com assinatura HMAC falsa -> 401 Unauthorized!
        mockMvc.perform(post("/api/v2/webhooks/payment")
                        .header("X-Webhook-Signature", "sha256=assinatura_falsa_do_hacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail", containsString("HMAC-SHA256")));

        // 3. Calcula a assinatura HMAC-SHA256 legítima e envia o Webhook (1ª entrega) -> 200 OK
        String canonical = PaymentWebhookService.buildCanonicalPayload(webhookEventId, order.getId(), "PAID");
        String validSignature = paymentWebhookService.computeSignatureHeader(canonical);

        mockMvc.perform(post("/api/v2/webhooks/payment")
                        .header("X-Webhook-Signature", validSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.duplicateIgnoredByIdempotency").value(false));

        // 4. IDEMPOTÊNCIA: O Gateway de Pagamento reenvia EXATAMENTE o mesmo webhook uma 2ª vez!
        // O servidor deve responder 200 OK com duplicateIgnoredByIdempotency = true sem duplicar efeitos!
        mockMvc.perform(post("/api/v2/webhooks/payment")
                        .header("X-Webhook-Signature", validSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.duplicateIgnoredByIdempotency").value(true));

        // 5. Aguarda brevemente a thread do pool @Async ("FlashTicket-Async-*") concluir a emissão do QR Code
        AsyncTicketFulfillmentService.TicketDeliveryReceipt receipt = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            var opt = asyncFulfillmentService.findReceiptByOrderId(order.getId());
            if (opt.isPresent()) {
                receipt = opt.get();
                break;
            }
            Thread.sleep(50);
        }

        assertThat(receipt).isNotNull();
        assertThat(receipt.processedByThread()).startsWith("FlashTicket-Async-");
        assertThat(receipt.qrCodeSignature()).startsWith("QR-FLASHTICKET-");

        // Comprova via endpoint HTTP que o comprovante assíncrono e o Webhook Outbound estão disponíveis
        mockMvc.perform(get("/api/v2/async/fulfillments/" + order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedByThread", containsString("FlashTicket-Async-")))
                .andExpect(jsonPath("$.deliveryStatus").value("EMAIL_SENT_WITH_QR_CODE"));

        mockMvc.perform(get("/api/v2/webhooks/outbound-deliveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].responseStatus").value(200));
    }

    @Test
    @DisplayName("Fase 6.3: O Cron Job (@Scheduled) deve cancelar pedidos PENDING expirados há mais de 15 min e devolver os ingressos ao estoque")
    void shouldCancelExpiredPendingOrdersAndRestoreEventTicketStock() throws Exception {
        // 1. Cria um evento com capacidade de 10 ingressos
        Event event = eventService.createEvent(
                "Workshop Arquitetura Assincrona",
                "Teste de Cron Job devolvendo estoque de reservas expiradas",
                EventCategory.WORKSHOP,
                LocalDateTime.now().plusDays(20),
                10,
                new BigDecimal("100.00")
        );

        // 2. Cliente reserva 3 ingressos (estoque cai de 10 para 7; pedido fica PENDING)
        TicketOrder pendingOrder = ticketPurchaseService.purchaseTickets(
                event.getId(),
                "cliente.esquecido@flashticket.com",
                3,
                false,
                false
        );

        assertThat(eventService.getEventById(event.getId()).getAvailableTickets()).isEqualTo(7);

        // 3. Simula que passaram 20 minutos sem pagamento (expira o pedido)
        mockMvc.perform(post("/api/v2/jobs/expire-order-for-test/" + pendingOrder.getId()))
                .andExpect(status().isOk());

        // 4. Executa o Cron Job (@Scheduled) de limpeza de reservas expiradas
        mockMvc.perform(post("/api/v2/jobs/run-expired-orders-cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledOrdersCount").value(1))
                .andExpect(jsonPath("$.totalTicketsRestoredToStock").value(3));

        // 5. Comprova que os 3 ingressos voltaram para o estoque do evento (7 + 3 = 10)!
        Event updatedEvent = eventService.getEventById(event.getId());
        assertThat(updatedEvent.getAvailableTickets()).isEqualTo(10);
    }
}
