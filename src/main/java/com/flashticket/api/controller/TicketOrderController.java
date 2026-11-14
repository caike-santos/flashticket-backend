package com.flashticket.api.controller;

import com.flashticket.api.dto.TicketOrderResponse;
import com.flashticket.api.dto.TicketPurchaseRequest;
import com.flashticket.application.service.TicketPurchaseService;
import com.flashticket.domain.model.TicketOrder;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v2/orders")
public class TicketOrderController {

    private final TicketPurchaseService ticketPurchaseService;

    public TicketOrderController(TicketPurchaseService ticketPurchaseService) {
        this.ticketPurchaseService = ticketPurchaseService;
    }

    @PostMapping
    public ResponseEntity<TicketOrderResponse> purchaseTickets(
            @Valid @RequestBody TicketPurchaseRequest request) {
        TicketOrder order = ticketPurchaseService.purchaseTickets(
                request.eventId(),
                request.customerEmail(),
                request.quantity(),
                request.usePessimisticLock(),
                request.simulateFailureAfterStockDeduction()
        );

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(order.getId())
                .toUri();

        return ResponseEntity.created(location).body(TicketOrderResponse.fromDomain(order));
    }

    @GetMapping
    public ResponseEntity<List<TicketOrderResponse>> listOrdersByCustomer(
            @RequestParam String customerEmail) {
        List<TicketOrderResponse> orders = ticketPurchaseService.listOrdersByCustomer(customerEmail)
                .stream()
                .map(TicketOrderResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(orders);
    }
}
