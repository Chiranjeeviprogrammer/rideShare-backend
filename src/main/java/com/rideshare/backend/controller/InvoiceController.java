package com.rideshare.backend.controller;

import com.rideshare.backend.model.Invoice;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.InvoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Controller for passenger and driver invoice retrieval and printable tax receipt views.
 * Strictly enforces authorization: passengers/drivers can only access invoices for their own transactions.
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User getAuthenticatedUser(String authHeader, String queryToken) {
        String token = authHeader;
        if ((token == null || !token.startsWith("Bearer ")) && queryToken != null && !queryToken.isBlank()) {
            token = "Bearer " + queryToken.trim();
        }
        if (token == null || !token.startsWith("Bearer ")) return null;
        try {
            String email = jwtUtil.extractEmail(token.substring(7));
            return userRepository.findByEmail(email);
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isAuthorizedForInvoice(User user, Invoice invoice) {
        if (user == null || invoice == null) return false;
        if ("ADMIN".equalsIgnoreCase(user.getRole()) || "SUPPORT".equalsIgnoreCase(user.getRole())) {
            return true;
        }
        if (invoice.getPassenger() != null && invoice.getPassenger().getId().equals(user.getId())) {
            return true;
        }
        if (invoice.getDriver() != null && invoice.getDriver().getId().equals(user.getId())) {
            return true;
        }
        return false;
    }

    /**
     * Retrieve tax invoice JSON by invoice number.
     */
    @GetMapping("/{invoiceNumber}")
    public ResponseEntity<?> getInvoiceByNumber(
            @PathVariable("invoiceNumber") String invoiceNumber,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getAuthenticatedUser(authHeader, null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }

        Optional<Invoice> opt = invoiceService.getInvoiceByNumber(invoiceNumber);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Invoice not found."));
        }

        Invoice invoice = opt.get();
        if (!isAuthorizedForInvoice(user, invoice)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to view this invoice."));
        }

        return ResponseEntity.ok(invoice);
    }

    /**
     * Retrieve tax invoice JSON by booking ID.
     */
    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<?> getInvoiceByBookingId(
            @PathVariable("bookingId") Long bookingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getAuthenticatedUser(authHeader, null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }

        Optional<Invoice> opt = invoiceService.getInvoiceByBookingId(bookingId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Invoice for this booking not found."));
        }

        Invoice invoice = opt.get();
        if (!isAuthorizedForInvoice(user, invoice)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to view this invoice."));
        }

        return ResponseEntity.ok(invoice);
    }

    /**
     * View or print printable HTML tax invoice.
     * Supports either Authorization header or ?token= for seamless opening in a new browser tab or mobile webview.
     */
    @GetMapping(value = "/{invoiceNumber}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getInvoiceHtml(
            @PathVariable("invoiceNumber") String invoiceNumber,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "token", required = false) String queryToken) {
        User user = getAuthenticatedUser(authHeader, queryToken);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("<h3>Unauthorized: Please log in to view this tax invoice.</h3>");
        }

        Optional<Invoice> opt = invoiceService.getInvoiceByNumber(invoiceNumber);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("<h3>Invoice not found.</h3>");
        }

        Invoice invoice = opt.get();
        if (!isAuthorizedForInvoice(user, invoice)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("<h3>Forbidden: You are not authorized to view this financial record.</h3>");
        }

        String html = invoiceService.generateInvoiceHtml(invoice);
        return ResponseEntity.ok(html);
    }
}
