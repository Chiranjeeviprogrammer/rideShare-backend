package com.rideshare.backend.service;

import com.rideshare.backend.model.Booking;
import com.rideshare.backend.model.GstSettings;
import com.rideshare.backend.model.Invoice;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.BookingRepository;
import com.rideshare.backend.repository.GstSettingsRepository;
import com.rideshare.backend.repository.InvoiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class InvoiceService {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private GstSettingsRepository gstSettingsRepository;

    @Autowired
    private GstSettingsService gstSettingsService;

    @Autowired
    private GstCalculationService gstCalculationService;

    @Autowired
    private BookingRepository bookingRepository;

    /**
     * Atomically generates a concurrency-safe unique invoice number.
     */
    @Transactional
    public synchronized String generateNextInvoiceNumber(GstSettings settings) {
        GstSettings locked = gstSettingsRepository.findByIdForUpdate(settings.getId() != null ? settings.getId() : 1L)
                .orElse(settings);

        long seq = locked.getNextInvoiceNumber() != null ? locked.getNextInvoiceNumber() : 1001L;
        locked.setNextInvoiceNumber(seq + 1);
        gstSettingsRepository.save(locked);

        String prefix = locked.getInvoicePrefix() != null ? locked.getInvoicePrefix() : "RS-INV-";
        int year = LocalDateTime.now().getYear();

        return String.format("%s%d-%06d", prefix, year, seq);
    }

    /**
     * Creates an immutable tax invoice for a completed/confirmed booking.
     */
    @Transactional
    public Invoice createInvoiceForBooking(Booking booking) {
        if (booking == null) return null;

        // Idempotency: return existing invoice if already issued
        Optional<Invoice> existing = invoiceRepository.findByBookingId(booking.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        GstSettings settings = gstSettingsService.getActiveSettings();
        String invoiceNumber = generateNextInvoiceNumber(settings);

        // Check if booking already holds frozen GST snapshot
        boolean hasSnapshot = booking.getTaxableAmount() != null && booking.getTaxableAmount() > 0;
        
        String placeOfSupply;
        String placeOfSupplyStateCode;
        boolean isInterstate;
        double taxableAmount;
        double gstRate;
        double cgstRate;
        double cgstAmount;
        double sgstRate;
        double sgstAmount;
        double igstRate;
        double igstAmount;
        double totalTaxAmount;

        if (hasSnapshot) {
            taxableAmount = booking.getTaxableAmount();
            gstRate = booking.getGstRateSnapshot() != null ? booking.getGstRateSnapshot() : settings.getGstRate();
            cgstAmount = booking.getCgstAmount() != null ? booking.getCgstAmount() : 0.0;
            sgstAmount = booking.getSgstAmount() != null ? booking.getSgstAmount() : 0.0;
            igstAmount = booking.getIgstAmount() != null ? booking.getIgstAmount() : 0.0;
            isInterstate = Boolean.TRUE.equals(booking.getIsInterstate());
            placeOfSupply = booking.getPlaceOfSupply() != null ? booking.getPlaceOfSupply() : settings.getBusinessState();
            placeOfSupplyStateCode = gstCalculationService.resolveStateCode(placeOfSupply, settings.getBusinessStateCode());
            totalTaxAmount = booking.getTax() > 0 ? booking.getTax() : (cgstAmount + sgstAmount + igstAmount);
            if (isInterstate) {
                igstRate = gstRate;
                cgstRate = 0.0;
                sgstRate = 0.0;
            } else {
                igstRate = 0.0;
                cgstRate = gstRate / 2.0;
                sgstRate = gstRate / 2.0;
            }
        } else {
            String pickup = booking.getPickupAddress();
            BigDecimal grossTotal = BigDecimal.valueOf(booking.getTotalAmount()).setScale(2, RoundingMode.HALF_UP);
            GstCalculationService.GstCalculationResult calc = gstCalculationService.calculate(grossTotal, pickup, settings);
            taxableAmount = calc.getTaxableAmount().doubleValue();
            gstRate = calc.getGstRate();
            cgstRate = calc.getCgstRate().doubleValue();
            cgstAmount = calc.getCgstAmount().doubleValue();
            sgstRate = calc.getSgstRate().doubleValue();
            sgstAmount = calc.getSgstAmount().doubleValue();
            igstRate = calc.getIgstRate().doubleValue();
            igstAmount = calc.getIgstAmount().doubleValue();
            totalTaxAmount = calc.getTotalTaxAmount().doubleValue();
            isInterstate = calc.isInterstate();
            placeOfSupply = calc.getPlaceOfSupply();
            placeOfSupplyStateCode = calc.getPlaceOfSupplyStateCode();
        }

        User passenger = booking.getPassenger();
        User driver = booking.getRide() != null ? booking.getRide().getDriver() : null;

        Invoice invoice = Invoice.builder()
                .invoiceNumber(invoiceNumber)
                .booking(booking)
                .passenger(passenger)
                .driver(driver)
                .ride(booking.getRide())
                .invoiceDate(LocalDateTime.now())
                .placeOfSupply(placeOfSupply)
                .placeOfSupplyStateCode(placeOfSupplyStateCode)
                .isInterstate(isInterstate)
                // Supplier Details
                .supplierLegalName(settings.getBusinessName())
                .supplierTradeName(settings.getTradeName())
                .supplierGstin(settings.getGstin())
                .supplierAddress(settings.getBusinessAddress())
                .supplierState(settings.getBusinessState())
                .supplierStateCode(settings.getBusinessStateCode())
                // Customer Details
                .customerName(passenger != null ? passenger.getName() : "Commuter")
                .customerEmail(passenger != null ? passenger.getEmail() : "")
                .customerPhone(passenger != null ? passenger.getPhoneNumber() : "")
                .customerAddress(passenger != null ? passenger.getHomeAddress() : "")
                .customerState(placeOfSupply)
                // Trip Details
                .tripPickup(booking.getPickupAddress())
                .tripDropoff(booking.getDropoffAddress())
                .tripSeats(booking.getSeats())
                .hsnSacCode(settings.getHsnSacCode())
                // Financials
                .baseAmount(booking.getBaseFare())
                .platformFeeAmount(booking.getPlatformFee())
                .discountAmount(booking.getDiscount())
                .taxableAmount(taxableAmount)
                .gstRate(gstRate)
                .cgstRate(cgstRate)
                .cgstAmount(cgstAmount)
                .sgstRate(sgstRate)
                .sgstAmount(sgstAmount)
                .igstRate(igstRate)
                .igstAmount(igstAmount)
                .totalTaxAmount(totalTaxAmount)
                .totalAmount(booking.getTotalAmount())
                .driverAmount(booking.getDriverAmount())
                .paymentMethod(booking.getPaymentMethod() != null ? booking.getPaymentMethod() : "ONLINE")
                .paymentStatus("PAID".equalsIgnoreCase(booking.getPaymentStatus()) ? "PAID" : "SETTLED")
                .paymentReference(booking.getTransactionReference() != null ? booking.getTransactionReference() : booking.getBookingCode())
                .status("ISSUED")
                .invoiceFooter(settings.getInvoiceFooter())
                .createdAt(LocalDateTime.now())
                .build();

        Invoice saved = invoiceRepository.save(invoice);

        // Update booking reference
        booking.setInvoiceNumber(saved.getInvoiceNumber());
        booking.setTaxableAmount(saved.getTaxableAmount());
        booking.setGstRateSnapshot(saved.getGstRate());
        booking.setCgstAmount(saved.getCgstAmount());
        booking.setSgstAmount(saved.getSgstAmount());
        booking.setIgstAmount(saved.getIgstAmount());
        booking.setIsInterstate(saved.getIsInterstate());
        booking.setPlaceOfSupply(saved.getPlaceOfSupply());
        booking.setPricesIncludeGstSnapshot(settings.getPricesIncludeGst());
        bookingRepository.save(booking);

        return saved;
    }

    public Optional<Invoice> getInvoiceByNumber(String invoiceNumber) {
        return invoiceRepository.findByInvoiceNumber(invoiceNumber);
    }

    public Optional<Invoice> getInvoiceByBookingId(Long bookingId) {
        return invoiceRepository.findByBookingId(bookingId);
    }

    public org.springframework.data.domain.Page<Invoice> findInvoicesFiltered(
            String status, Boolean isInterstate, LocalDateTime startDate, LocalDateTime endDate, String search, org.springframework.data.domain.Pageable pageable) {
        return invoiceRepository.findInvoicesFiltered(status, isInterstate, startDate, endDate, search, pageable);
    }

    public java.util.Map<String, Object> getGstSummaryReport(String status, LocalDateTime startDate, LocalDateTime endDate) {
        java.util.List<Object[]> rows = invoiceRepository.getGstSummaryReport(status, startDate, endDate);
        java.util.Map<String, Object> summary = new java.util.HashMap<>();
        if (rows != null && !rows.isEmpty() && rows.get(0) != null) {
            Object[] r = rows.get(0);
            summary.put("invoiceCount", ((Number) r[0]).longValue());
            summary.put("totalTaxableAmount", ((Number) r[1]).doubleValue());
            summary.put("totalCgstAmount", ((Number) r[2]).doubleValue());
            summary.put("totalSgstAmount", ((Number) r[3]).doubleValue());
            summary.put("totalIgstAmount", ((Number) r[4]).doubleValue());
            summary.put("totalTaxAmount", ((Number) r[5]).doubleValue());
            summary.put("totalGrossAmount", ((Number) r[6]).doubleValue());
        } else {
            summary.put("invoiceCount", 0L);
            summary.put("totalTaxableAmount", 0.0);
            summary.put("totalCgstAmount", 0.0);
            summary.put("totalSgstAmount", 0.0);
            summary.put("totalIgstAmount", 0.0);
            summary.put("totalTaxAmount", 0.0);
            summary.put("totalGrossAmount", 0.0);
        }
        return summary;
    }

    /**
     * Generates a modern, clean HTML tax invoice view for web and mobile browsers.
     */
    public String generateInvoiceHtml(Invoice inv) {
        if (inv == null) return "<h3>Invoice not found</h3>";

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");
        String formattedDate = inv.getInvoiceDate() != null ? inv.getInvoiceDate().format(dtf) : "N/A";

        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\" />\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n" +
                "  <title>Tax Invoice - " + inv.getInvoiceNumber() + "</title>\n" +
                "  <style>\n" +
                "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: #071426; color: #F5F8FC; margin: 0; padding: 24px; }\n" +
                "    .invoice-card { max-width: 800px; margin: 0 auto; background: #0D1B2A; border: 1px solid rgba(0, 213, 232, 0.3); border-radius: 16px; padding: 32px; box-shadow: 0 12px 40px rgba(0,0,0,0.5); }\n" +
                "    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid rgba(255,255,255,0.1); padding-bottom: 20px; margin-bottom: 24px; }\n" +
                "    .brand-title { font-size: 26px; font-weight: 800; color: #00D5E8; margin: 0; letter-spacing: -0.5px; }\n" +
                "    .invoice-badge { background: rgba(0, 213, 232, 0.15); border: 1px solid #00D5E8; color: #00D5E8; padding: 4px 12px; border-radius: 8px; font-size: 13px; font-weight: 700; text-transform: uppercase; }\n" +
                "    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-bottom: 24px; }\n" +
                "    .box { background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.06); border-radius: 12px; padding: 16px; }\n" +
                "    .box h4 { margin: 0 0 10px 0; color: #00D5E8; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px; }\n" +
                "    .box p { margin: 4px 0; font-size: 13.5px; color: #CBD5E1; }\n" +
                "    table { width: 100%; border-collapse: collapse; margin: 24px 0; }\n" +
                "    th { background: rgba(0, 213, 232, 0.08); color: #00D5E8; text-align: left; padding: 12px; font-size: 12px; text-transform: uppercase; border-bottom: 1px solid rgba(0, 213, 232, 0.2); }\n" +
                "    td { padding: 12px; font-size: 13.5px; border-bottom: 1px solid rgba(255,255,255,0.06); color: #F5F8FC; }\n" +
                "    .amount-col { text-align: right; }\n" +
                "    .totals-box { margin-left: auto; max-width: 340px; margin-top: 16px; }\n" +
                "    .totals-row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 13.5px; color: #CBD5E1; }\n" +
                "    .grand-total { display: flex; justify-content: space-between; padding: 12px 0; font-size: 18px; font-weight: 800; color: #00D5E8; border-top: 2px solid rgba(0, 213, 232, 0.4); margin-top: 8px; }\n" +
                "    .footer { margin-top: 32px; padding-top: 18px; border-top: 1px dashed rgba(255,255,255,0.15); font-size: 11.5px; color: #7E93AB; text-align: center; line-height: 1.6; }\n" +
                "    .btn-print { background: #00D5E8; color: #071426; font-weight: 700; border: none; padding: 10px 20px; border-radius: 8px; cursor: pointer; margin-top: 20px; font-size: 14px; display: inline-flex; align-items: center; gap: 8px; }\n" +
                "    @media print { body { background: #FFFFFF; color: #000000; padding: 0; } .invoice-card { border: none; box-shadow: none; padding: 0; color: #000000; } .btn-print { display: none; } }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"invoice-card\">\n" +
                "    <div class=\"header\">\n" +
                "      <div>\n" +
                "        <h1 class=\"brand-title\">" + (inv.getSupplierTradeName() != null ? inv.getSupplierTradeName() : "RideShare") + "</h1>\n" +
                "        <p style=\"margin: 4px 0; font-size: 13px; color: #7E93AB;\">" + inv.getSupplierLegalName() + "</p>\n" +
                "        <p style=\"margin: 2px 0; font-size: 12px; color: #10B981; font-weight: 600;\">GSTIN: " + inv.getSupplierGstin() + "</p>\n" +
                "      </div>\n" +
                "      <div style=\"text-align: right;\">\n" +
                "        <span class=\"invoice-badge\">TAX INVOICE</span>\n" +
                "        <h2 style=\"margin: 10px 0 2px 0; font-size: 17px; color: #F5F8FC;\">#" + inv.getInvoiceNumber() + "</h2>\n" +
                "        <p style=\"margin: 0; font-size: 12px; color: #7E93AB;\">Date: " + formattedDate + "</p>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class=\"grid\">\n" +
                "      <div class=\"box\">\n" +
                "        <h4>Supplier Details</h4>\n" +
                "        <p><strong>" + inv.getSupplierLegalName() + "</strong></p>\n" +
                "        <p>" + inv.getSupplierAddress() + "</p>\n" +
                "        <p>State: " + inv.getSupplierState() + " (Code: " + inv.getSupplierStateCode() + ")</p>\n" +
                "        <p>SAC Code: " + inv.getHsnSacCode() + "</p>\n" +
                "      </div>\n" +
                "      <div class=\"box\">\n" +
                "        <h4>Billed To (Passenger)</h4>\n" +
                "        <p><strong>" + inv.getCustomerName() + "</strong></p>\n" +
                "        <p>Email: " + inv.getCustomerEmail() + "</p>\n" +
                "        <p>Phone: " + (inv.getCustomerPhone() != null ? inv.getCustomerPhone() : "N/A") + "</p>\n" +
                "        <p>Place of Supply: <strong>" + inv.getPlaceOfSupply() + " (Code: " + inv.getPlaceOfSupplyStateCode() + ")</strong></p>\n" +
                "        <p>Tax Type: <span style=\"color: " + (inv.getIsInterstate() ? "#38BDF8" : "#34D399") + "; font-weight: 700;\">" + (inv.getIsInterstate() ? "INTER-STATE (IGST)" : "INTRA-STATE (CGST + SGST)") + "</span></p>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class=\"box\" style=\"margin-bottom: 24px;\">\n" +
                "      <h4>Trip Summary</h4>\n" +
                "      <p><strong>Pickup:</strong> " + (inv.getTripPickup() != null ? inv.getTripPickup() : "N/A") + "</p>\n" +
                "      <p><strong>Dropoff:</strong> " + (inv.getTripDropoff() != null ? inv.getTripDropoff() : "N/A") + "</p>\n" +
                "      <p><strong>Seats:</strong> " + inv.getTripSeats() + " seat(s) &bull; <strong>Payment Method:</strong> " + inv.getPaymentMethod() + " &bull; <strong>Ref:</strong> " + (inv.getPaymentReference() != null ? inv.getPaymentReference() : "N/A") + "</p>\n" +
                "    </div>\n" +
                "\n" +
                "    <table>\n" +
                "      <thead>\n" +
                "        <tr>\n" +
                "          <th>Item Description</th>\n" +
                "          <th>SAC Code</th>\n" +
                "          <th class=\"amount-col\">Taxable Value</th>\n" +
                "          <th class=\"amount-col\">Tax Rate</th>\n" +
                "          <th class=\"amount-col\">Total Tax</th>\n" +
                "          <th class=\"amount-col\">Amount (INR)</th>\n" +
                "        </tr>\n" +
                "      </thead>\n" +
                "      <tbody>\n" +
                "        <tr>\n" +
                "          <td>Carpooling Passenger Mobility Service</td>\n" +
                "          <td>" + inv.getHsnSacCode() + "</td>\n" +
                "          <td class=\"amount-col\">₹" + String.format("%.2f", inv.getTaxableAmount()) + "</td>\n" +
                "          <td class=\"amount-col\">" + String.format("%.1f", inv.getGstRate()) + "%</td>\n" +
                "          <td class=\"amount-col\">₹" + String.format("%.2f", inv.getTotalTaxAmount()) + "</td>\n" +
                "          <td class=\"amount-col\"><strong>₹" + String.format("%.2f", inv.getTotalAmount()) + "</strong></td>\n" +
                "        </tr>\n" +
                "      </tbody>\n" +
                "    </table>\n" +
                "\n" +
                "    <div class=\"totals-box\">\n" +
                "      <div class=\"totals-row\"><span>Taxable Base Value:</span><span>₹" + String.format("%.2f", inv.getTaxableAmount()) + "</span></div>\n" +
                (inv.getIsInterstate()
                        ? "      <div class=\"totals-row\"><span>IGST (" + String.format("%.1f", inv.getIgstRate()) + "%):</span><span>₹" + String.format("%.2f", inv.getIgstAmount()) + "</span></div>\n"
                        : "      <div class=\"totals-row\"><span>CGST (" + String.format("%.1f", inv.getCgstRate()) + "%):</span><span>₹" + String.format("%.2f", inv.getCgstAmount()) + "</span></div>\n" +
                          "      <div class=\"totals-row\"><span>SGST (" + String.format("%.1f", inv.getSgstRate()) + "%):</span><span>₹" + String.format("%.2f", inv.getSgstAmount()) + "</span></div>\n"
                ) +
                "      <div class=\"totals-row\"><span>Total GST Tax:</span><span>₹" + String.format("%.2f", inv.getTotalTaxAmount()) + "</span></div>\n" +
                "      <div class=\"grand-total\"><span>Total Invoiced:</span><span>₹" + String.format("%.2f", inv.getTotalAmount()) + "</span></div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class=\"footer\">\n" +
                "      <p>" + (inv.getInvoiceFooter() != null ? inv.getInvoiceFooter() : "Computer generated tax invoice.") + "</p>\n" +
                "      <p>RideShare Mobility Technologies &bull; Support: tax@rideshare.com &bull; Electronic Commerce Operator under Section 9(5) of CGST Act, 2017</p>\n" +
                "    </div>\n" +
                "\n" +
                "    <div style=\"text-align: center;\">\n" +
                "      <button onclick=\"window.print()\" class=\"btn-print\">\n" +
                "        🖨️ Print / Download Tax Invoice\n" +
                "      </button>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";
    }
}
