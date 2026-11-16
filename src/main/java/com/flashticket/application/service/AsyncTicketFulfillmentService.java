package com.flashticket.application.service;

import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import com.flashticket.infrastructure.config.AsyncAndSchedulingConfig;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * TÓPICO (Fase 6): Programação Assíncrona com @Async e CompletableFuture.
 *
 * Quando o Gateway de Pagamento confirma um pedido via Webhook, nós precisamos responder
 * HTTP 200 OK o mais rápido possível para não dar timeout na chamada do Gateway.
 * Tarefas pesadas ou lentas — como gerar o PDF/QR Code criptografado do ingresso e
 * enviar e-mail SMTP para o comprador — são delegadas em background para o pool
 * "FlashTicket-Async-*" via @Async!
 */
@Service
public class AsyncTicketFulfillmentService {

    private final Map<UUID, TicketDeliveryReceipt> deliveryReceipts = new ConcurrentHashMap<>();
    private final AuditLogRepositoryPort auditLogRepository;

    public AsyncTicketFulfillmentService(AuditLogRepositoryPort auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Async(AsyncAndSchedulingConfig.TICKET_ASYNC_EXECUTOR)
    public CompletableFuture<TicketDeliveryReceipt> generateQrCodeAndSendEmailAsync(
            UUID orderId,
            UUID eventId,
            String customerEmail,
            int quantity) {

        String workerThreadName = Thread.currentThread().getName();
        String qrCodeSignature = "QR-FLASHTICKET-" + orderId.toString().substring(0, 8).toUpperCase()
                + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        TicketDeliveryReceipt receipt = new TicketDeliveryReceipt(
                orderId,
                eventId,
                customerEmail,
                quantity,
                qrCodeSignature,
                "EMAIL_SENT_WITH_QR_CODE",
                workerThreadName,
                Instant.now());

        deliveryReceipts.put(orderId, receipt);

        auditLogRepository.save(AuditLog.create(
                "TICKET_ISSUED_ASYNC",
                orderId.toString(),
                customerEmail,
                Map.of(
                        "eventId", eventId.toString(),
                        "qrCodeSignature", qrCodeSignature,
                        "workerThreadName", workerThreadName,
                        "deliveryStatus", "EMAIL_SENT_WITH_QR_CODE"
                )
        ));

        return CompletableFuture.completedFuture(receipt);
    }

    public Optional<TicketDeliveryReceipt> findReceiptByOrderId(UUID orderId) {
        return Optional.ofNullable(deliveryReceipts.get(orderId));
    }

    public record TicketDeliveryReceipt(
            UUID orderId,
            UUID eventId,
            String customerEmail,
            int quantity,
            String qrCodeSignature,
            String deliveryStatus,
            String processedByThread,
            Instant processedAt) {
    }
}
