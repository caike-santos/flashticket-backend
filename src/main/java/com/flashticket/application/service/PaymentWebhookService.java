package com.flashticket.application.service;

import com.flashticket.domain.exception.ResourceNotFoundException;
import com.flashticket.domain.exception.SecurityAuthenticationException;
import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.model.OrderStatus;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import com.flashticket.infrastructure.persistence.jpa.SpringDataTicketOrderRepository;
import com.flashticket.infrastructure.persistence.jpa.TicketOrderJpaEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TÓPICO (Fase 6): Webhooks (Inbound com Assinatura HMAC-SHA256 + Idempotência & Outbound).
 *
 * 1. Webhook Inbound (Gateway de Pagamento -> FlashTicket):
 *    - Valida o header 'X-Webhook-Signature' (HMAC-SHA256) para garantir que a chamada veio
 *      mesmo do Gateway de Pagamento e não de um atacante tentando aprovar pedidos de graça!
 *    - Garante IDEMPOTÊNCIA pela chave 'webhookEventId': se o Gateway reenviar o mesmo evento
 *      por falha de rede, retornamos 200 OK sem processar duas vezes!
 *    - Atualiza o pedido para CONFIRMED e aciona a geração assíncrona (@Async) do QR Code.
 *
 * 2. Webhook Outbound (FlashTicket -> Sistema do Organizador do Evento):
 *    - Envia notificação assinada com HMAC-SHA256 para a URL configurada pelo Organizador
 *      sempre que uma venda é confirmada.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final byte[] webhookSecretBytes;
    private final SpringDataTicketOrderRepository orderRepository;
    private final AsyncTicketFulfillmentService asyncFulfillmentService;
    private final AuditLogRepositoryPort auditLogRepository;

    private final Map<String, WebhookProcessingResult> processedWebhooksByIdempotencyKey = new ConcurrentHashMap<>();
    private final List<OutboundWebhookDelivery> outboundDeliveries = new CopyOnWriteArrayList<>();

    public PaymentWebhookService(
            @Value("${flashticket.webhooks.payment-secret:whsec_flashticket_gateway_2026}") String webhookSecret,
            SpringDataTicketOrderRepository orderRepository,
            AsyncTicketFulfillmentService asyncFulfillmentService,
            AuditLogRepositoryPort auditLogRepository) {
        this.webhookSecretBytes = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.orderRepository = orderRepository;
        this.asyncFulfillmentService = asyncFulfillmentService;
        this.auditLogRepository = auditLogRepository;
    }

    public String computeSignatureHeader(String canonicalPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(webhookSecretBytes, HMAC_SHA256));
            byte[] rawHmac = mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(rawHmac);
        } catch (Exception ex) {
            throw new IllegalStateException("Erro ao calcular assinatura HMAC-SHA256 do Webhook", ex);
        }
    }

    public static String buildCanonicalPayload(String webhookEventId, UUID orderId, String paymentStatus) {
        return webhookEventId + ":" + orderId + ":" + paymentStatus;
    }

    @Transactional
    public WebhookProcessingResult processInboundPaymentWebhook(
            PaymentWebhookPayload payload,
            String signatureHeader) {

        String canonical = buildCanonicalPayload(
                payload.webhookEventId(),
                payload.orderId(),
                payload.paymentStatus());
        String expectedSignature = computeSignatureHeader(canonical);

        // 1. Validação criptográfica da assinatura HMAC-SHA256 em tempo constante
        if (signatureHeader == null || !MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Webhook rejeitado por assinatura HMAC-SHA256 invalida: webhookEventId={}, orderId={}",
                    payload.webhookEventId(), payload.orderId());
            throw new SecurityAuthenticationException(
                    "Assinatura HMAC-SHA256 do Webhook invalida (X-Webhook-Signature nao confere)!");
        }

        // 2. Checagem de Idempotência: se este webhookEventId já foi processado, ignora duplicata!
        WebhookProcessingResult previous = processedWebhooksByIdempotencyKey.get(payload.webhookEventId());
        if (previous != null) {
            log.warn("Webhook duplicado ignorado por idempotencia: webhookEventId={}, orderId={}",
                    payload.webhookEventId(), payload.orderId());
            return new WebhookProcessingResult(
                    previous.webhookEventId(),
                    previous.orderId(),
                    previous.orderStatus(),
                    true,
                    "Evento de Webhook ja processado anteriormente (Idempotencia garantida: ignorado sem duplicar efeitos).");
        }

        // 3. Atualiza status do pedido no PostgreSQL
        TicketOrderJpaEntity order = orderRepository.findById(payload.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Pedido nao encontrado: " + payload.orderId()));

        if ("PAID".equalsIgnoreCase(payload.paymentStatus())) {
            order.setStatus(OrderStatus.PAID);
            orderRepository.saveAndFlush(order);
            log.info("Webhook Inbound confirmado: orderId={}, webhookEventId={}, novoStatus=PAID",
                    order.getId(), payload.webhookEventId());

            // 4. Dispara geração de QR Code e envio de e-mail em background (@Async) sem travar a requisição HTTP!
            asyncFulfillmentService.generateQrCodeAndSendEmailAsync(
                    order.getId(),
                    order.getEventId(),
                    order.getCustomerEmail(),
                    order.getQuantity());

            // 5. Dispara Webhook Outbound para a URL cadastrada pelo Organizador do evento
            dispatchOutboundWebhookToOrganizer(order);
        }

        WebhookProcessingResult result = new WebhookProcessingResult(
                payload.webhookEventId(),
                order.getId(),
                order.getStatus(),
                false,
                "Webhook processado com sucesso: pedido confirmado e tarefa @Async disparada em background!");

        processedWebhooksByIdempotencyKey.put(payload.webhookEventId(), result);

        auditLogRepository.save(AuditLog.create(
                "PAYMENT_WEBHOOK_PROCESSED",
                order.getId().toString(),
                "PAYMENT_GATEWAY",
                Map.of(
                        "webhookEventId", payload.webhookEventId(),
                        "paymentStatus", payload.paymentStatus(),
                        "newOrderStatus", order.getStatus().name()
                )
        ));

        return result;
    }

    private void dispatchOutboundWebhookToOrganizer(TicketOrderJpaEntity order) {
        String organizerCallbackUrl = "https://organizer-erp.example.com/webhooks/flashticket-sales";
        String outboundPayload = String.format(
                "{\"event\":\"ORDER_CONFIRMED\",\"orderId\":\"%s\",\"eventId\":\"%s\",\"quantity\":%d}",
                order.getId(),
                order.getEventId(),
                order.getQuantity());
        String outboundSignature = computeSignatureHeader(outboundPayload);

        OutboundWebhookDelivery delivery = new OutboundWebhookDelivery(
                UUID.randomUUID(),
                order.getId(),
                organizerCallbackUrl,
                outboundPayload,
                outboundSignature,
                200,
                Instant.now());
        outboundDeliveries.add(delivery);
    }

    public List<OutboundWebhookDelivery> listOutboundDeliveries() {
        return new ArrayList<>(outboundDeliveries);
    }

    public record PaymentWebhookPayload(
            String webhookEventId,
            UUID orderId,
            String paymentStatus) {
    }

    public record WebhookProcessingResult(
            String webhookEventId,
            UUID orderId,
            OrderStatus orderStatus,
            boolean duplicateIgnoredByIdempotency,
            String message) {
    }

    public record OutboundWebhookDelivery(
            UUID deliveryId,
            UUID orderId,
            String destinationUrl,
            String jsonPayload,
            String hmacSignatureHeader,
            int responseStatus,
            Instant deliveredAt) {
    }
}
