package com.flashticket.api.controller;

import com.flashticket.application.service.AsyncTicketFulfillmentService;
import com.flashticket.application.service.ExpiredOrderCleanupScheduler;
import com.flashticket.application.service.PaymentWebhookService;
import com.flashticket.domain.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TÓPICOS (Fase 6):
 * - Webhooks Inbound (validação de assinatura HMAC-SHA256 + Chave de Idempotência)
 * - Webhooks Outbound (notificação de vendas para o Organizador)
 * - Processamento Assíncrono (@Async com ThreadPoolTaskExecutor "FlashTicket-Async-*")
 * - Cron Jobs / Tarefas Agendadas (@Scheduled para cancelar pedidos PENDING expirados e devolver estoque)
 */
@RestController
@RequestMapping("/api/v2")
public class AsyncWebhookAndCronController {

    private final PaymentWebhookService paymentWebhookService;
    private final AsyncTicketFulfillmentService asyncFulfillmentService;
    private final ExpiredOrderCleanupScheduler expiredOrderCleanupScheduler;

    public AsyncWebhookAndCronController(
            PaymentWebhookService paymentWebhookService,
            AsyncTicketFulfillmentService asyncFulfillmentService,
            ExpiredOrderCleanupScheduler expiredOrderCleanupScheduler) {
        this.paymentWebhookService = paymentWebhookService;
        this.asyncFulfillmentService = asyncFulfillmentService;
        this.expiredOrderCleanupScheduler = expiredOrderCleanupScheduler;
    }

    /**
     * Endpoint utilitário para gerar a assinatura HMAC-SHA256 de teste no flashticket.http.
     */
    @PostMapping("/webhooks/payment/sign-helper")
    public ResponseEntity<Map<String, String>> generateWebhookSignatureHelper(
            @RequestBody PaymentWebhookService.PaymentWebhookPayload payload) {
        String canonical = PaymentWebhookService.buildCanonicalPayload(
                payload.webhookEventId(),
                payload.orderId(),
                payload.paymentStatus());
        String signature = paymentWebhookService.computeSignatureHeader(canonical);

        return ResponseEntity.ok(Map.of(
                "canonicalPayload", canonical,
                "signatureHeaderValue", signature,
                "headerName", "X-Webhook-Signature"
        ));
    }

    /**
     * Webhook Inbound: Recebe confirmação do Gateway de Pagamento externo.
     * Exige header 'X-Webhook-Signature: sha256=...' e garante Idempotência por 'webhookEventId'.
     */
    @PostMapping("/webhooks/payment")
    public ResponseEntity<PaymentWebhookService.WebhookProcessingResult> receivePaymentWebhook(
            @RequestBody PaymentWebhookService.PaymentWebhookPayload payload,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signatureHeader) {
        PaymentWebhookService.WebhookProcessingResult result =
                paymentWebhookService.processInboundPaymentWebhook(payload, signatureHeader);
        return ResponseEntity.ok(result);
    }

    /**
     * Consulta o comprovante de emissão do ingresso processado em background pelo @Async.
     */
    @GetMapping("/async/fulfillments/{orderId}")
    public ResponseEntity<AsyncTicketFulfillmentService.TicketDeliveryReceipt> getAsyncFulfillmentStatus(
            @PathVariable UUID orderId) {
        return asyncFulfillmentService.findReceiptByOrderId(orderId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Comprovante assincrono ainda nao disponivel para o pedido: " + orderId));
    }

    /**
     * Lista os Webhooks Outbound disparados pelo FlashTicket para o sistema do Organizador.
     */
    @GetMapping("/webhooks/outbound-deliveries")
    public ResponseEntity<List<PaymentWebhookService.OutboundWebhookDelivery>> listOutboundDeliveries() {
        return ResponseEntity.ok(paymentWebhookService.listOutboundDeliveries());
    }

    /**
     * Utilitário de laboratório: força a data de expiração de um pedido PENDING para 20 minutos no passado
     * para podermos testar o Cron Job imediatamente sem esperar 15 minutos!
     */
    @PostMapping("/jobs/expire-order-for-test/{orderId}")
    public ResponseEntity<Map<String, String>> forceExpireOrder(@PathVariable UUID orderId) {
        expiredOrderCleanupScheduler.forceExpireOrderForTesting(orderId);
        return ResponseEntity.ok(Map.of(
                "orderId", orderId.toString(),
                "message", "Data expiresAt do pedido movida para 20 minutos no passado! Pronto para o Cron Job."
        ));
    }

    /**
     * Aciona imediatamente a rotina agendada (@Scheduled) de limpeza de pedidos PENDING expirados.
     */
    @PostMapping("/jobs/run-expired-orders-cleanup")
    public ResponseEntity<ExpiredOrderCleanupScheduler.CleanupExecutionReport> triggerExpiredOrdersCleanup() {
        ExpiredOrderCleanupScheduler.CleanupExecutionReport report =
                expiredOrderCleanupScheduler.cancelExpiredPendingOrders();
        return ResponseEntity.ok(report);
    }
}
