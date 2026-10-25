package com.rideshare.backend.controller;

import com.rideshare.backend.model.GstSettings;
import com.rideshare.backend.model.GstSettingsHistory;
import com.rideshare.backend.model.Invoice;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.GstSettingsHistoryRepository;
import com.rideshare.backend.repository.InvoiceRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.GstCalculationService;
import com.rideshare.backend.service.GstSettingsService;
import com.rideshare.backend.service.InvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated Admin Controller for GST Configuration, Audit History, Invoicing, and Tax Reports.
 */
@RestController
@RequestMapping("/api/admin/gst")
public class GstAdminController {

    @Autowired
    private GstSettingsService gstSettingsService;

    @Autowired
    private GstCalculationService gstCalculationService;

    @Autowired
    private GstSettingsHistoryRepository historyRepository;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User getAdminUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            String email = jwtUtil.extractEmail(authHeader.substring(7));
            User user = userRepository.findByEmail(email);
            if (user != null && ("ADMIN".equalsIgnoreCase(user.getRole()) || "SUPPORT".equalsIgnoreCase(user.getRole()))
                    && !"SUSPENDED".equalsIgnoreCase(user.getStatus())) {
                return user;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Get active GST settings.
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getGstSettings(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative authorization required."));
        }
        GstSettings settings = gstSettingsService.getActiveSettings();
        return ResponseEntity.ok(settings);
    }

    /**
     * Update GST settings with history logging and cache invalidation.
     */
    @PutMapping("/settings")
    public ResponseEntity<?> updateGstSettings(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody GstSettings incoming,
            HttpServletRequest request) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative authorization required."));
        }

        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = request.getRemoteAddr();
        }

        GstSettings updated = gstSettingsService.updateSettings(incoming, admin.getEmail(), clientIp);
        return ResponseEntity.ok(updated);
    }

    /**
     * Verify GSTIN format and extract state/PAN metadata.
     */
    @PostMapping("/verify-gstin")
    public ResponseEntity<?> verifyGstin(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> body) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative authorization required."));
        }

        String gstin = body.get("gstin");
        Map<String, Object> result = gstSettingsService.verifyGstin(gstin);
        return ResponseEntity.ok(result);
    }

    /**
     * Get GST configuration change history / audit logs.
     */
    @GetMapping("/history")
    public ResponseEntity<?> getGstHistory(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative authorization required."));
        }

        List<GstSettingsHistory> history = historyRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(history);
    }

    /**
     * Preview GST calculation for given parameters before applying.
     */
    @PostMapping("/preview-calculation")
    public ResponseEntity<?> previewCalculation(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative authorization required."));
        }

        double amountVal = 0.0;
        if (params.get("amount") != null) {
            amountVal = Double.parseDouble(params.get("amount").toString());
        }
        String tripLocation = (String) params.get("tripLocation");

        GstSettings currentSettings = gstSettingsService.getActiveSettings();

        // Optional overrides from preview request
        if (params.get("gstRate") != null) {
            currentSettings.setGstRate(Double.parseDouble(params.get("gstRate").toString()));
        }
        if (params.get("pricesIncludeGst") != null) {
            currentSettings.setPricesIncludeGst(Boolean.parseBoolean(params.get("pricesIncludeGst").toString()));
        }
        if (params.get("taxRuleType") != null) {
            currentSettings.setTaxRuleType((String) params.get("taxRuleType"));
        }

        GstCalculationService.GstCalculationResult result = gstCalculationService.calculate(
                BigDecimal.valueOf(amountVal), tripLocation, currentSettings);

        return ResponseEntity.ok(result.toMap());
    }

    /**
     * List and search tax invoices with filters.
     */
    @GetMapping("/invoices")
    public ResponseEntity<?> listInvoices(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "isInterstate", required = false) Boolean isInterstate,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative authorization required."));
        }

        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(23, 59, 59) : null;

        PageRequest pageRequest = PageRequest.of(Math.max(0, page), Math.min(size, 100), Sort.by(Sort.Direction.DESC, "invoice_date"));
        Page<Invoice> invoices = invoiceService.findInvoicesFiltered(status, isInterstate, startDt, endDt, search, pageRequest);

        return ResponseEntity.ok(invoices);
    }

    /**
     * Get single invoice details by ID.
     */
    @GetMapping("/invoices/{id}")
    public ResponseEntity<?> getInvoiceById(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable("id") Long id) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative authorization required."));
        }

        return invoiceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GST Summary Report for Admin Dashboard KPI metrics.
     */
    @GetMapping("/reports/summary")
    public ResponseEntity<?> getSummaryReport(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative authorization required."));
        }

        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(23, 59, 59) : null;

        Map<String, Object> summary = invoiceService.getGstSummaryReport(status, startDt, endDt);
        return ResponseEntity.ok(summary);
    }

    /**
     * Export GST invoices to CSV for accounting and audit compliance.
     */
    @GetMapping(value = "/reports/export", produces = "text/csv")
    public void exportGstReportCsv(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletResponse response) {
        User admin = getAdminUser(authHeader);
        if (admin == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(23, 59, 59) : null;

        List<Invoice> invoices = invoiceRepository.findAll(Sort.by(Sort.Direction.DESC, "invoiceDate"));

        response.setContentType("text/csv");
        String filename = "GST_Report_" + LocalDate.now() + ".csv";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("Invoice Number,Invoice Date,Place Of Supply,State Code,Tax Type,Customer Name,Customer Email,SAC Code,Taxable Value,GST Rate,CGST,SGST,IGST,Total Tax,Total Amount,Payment Status,Payment Ref");

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            for (Invoice inv : invoices) {
                if (startDt != null && inv.getInvoiceDate().isBefore(startDt)) continue;
                if (endDt != null && inv.getInvoiceDate().isAfter(endDt)) continue;
                if (status != null && !status.equalsIgnoreCase(inv.getStatus())) continue;

                String taxType = Boolean.TRUE.equals(inv.getIsInterstate()) ? "INTER-STATE (IGST)" : "INTRA-STATE (CGST+SGST)";
                String dateStr = inv.getInvoiceDate() != null ? inv.getInvoiceDate().format(dtf) : "";

                writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%.2f,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,\"%s\",\"%s\"\n",
                        inv.getInvoiceNumber(),
                        dateStr,
                        inv.getPlaceOfSupply(),
                        inv.getPlaceOfSupplyStateCode(),
                        taxType,
                        inv.getCustomerName() != null ? inv.getCustomerName().replace("\"", "\"\"") : "",
                        inv.getCustomerEmail() != null ? inv.getCustomerEmail() : "",
                        inv.getHsnSacCode(),
                        inv.getTaxableAmount(),
                        inv.getGstRate(),
                        inv.getCgstAmount(),
                        inv.getSgstAmount(),
                        inv.getIgstAmount(),
                        inv.getTotalTaxAmount(),
                        inv.getTotalAmount(),
                        inv.getPaymentStatus(),
                        inv.getPaymentReference() != null ? inv.getPaymentReference() : ""
                );
            }
            writer.flush();
        } catch (Exception e) {
            System.err.println("Failed to write CSV export: " + e.getMessage());
        }
    }
}
