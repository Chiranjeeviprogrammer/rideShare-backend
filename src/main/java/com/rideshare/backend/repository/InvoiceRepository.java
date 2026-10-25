package com.rideshare.backend.repository;

import com.rideshare.backend.model.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    Optional<Invoice> findByBookingId(Long bookingId);

    List<Invoice> findByPassengerIdOrderByInvoiceDateDesc(Long passengerId);

    List<Invoice> findByDriverIdOrderByInvoiceDateDesc(Long driverId);

    @Query(value = "SELECT * FROM invoices i WHERE " +
           "(CAST(:status AS TEXT) IS NULL OR i.status = CAST(:status AS TEXT)) AND " +
           "(CAST(:isInterstate AS BOOLEAN) IS NULL OR i.is_interstate = CAST(:isInterstate AS BOOLEAN)) AND " +
           "(CAST(:startDate AS TIMESTAMP) IS NULL OR i.invoice_date >= CAST(:startDate AS TIMESTAMP)) AND " +
           "(CAST(:endDate AS TIMESTAMP) IS NULL OR i.invoice_date <= CAST(:endDate AS TIMESTAMP)) AND " +
           "(CAST(:search AS TEXT) IS NULL OR " +
           " i.invoice_number ILIKE CONCAT('%', CAST(:search AS TEXT), '%') " +
           " OR i.customer_name ILIKE CONCAT('%', CAST(:search AS TEXT), '%') " +
           " OR i.customer_email ILIKE CONCAT('%', CAST(:search AS TEXT), '%') " +
           " OR i.place_of_supply ILIKE CONCAT('%', CAST(:search AS TEXT), '%'))",
           countQuery = "SELECT COUNT(*) FROM invoices i WHERE " +
           "(CAST(:status AS TEXT) IS NULL OR i.status = CAST(:status AS TEXT)) AND " +
           "(CAST(:isInterstate AS BOOLEAN) IS NULL OR i.is_interstate = CAST(:isInterstate AS BOOLEAN)) AND " +
           "(CAST(:startDate AS TIMESTAMP) IS NULL OR i.invoice_date >= CAST(:startDate AS TIMESTAMP)) AND " +
           "(CAST(:endDate AS TIMESTAMP) IS NULL OR i.invoice_date <= CAST(:endDate AS TIMESTAMP)) AND " +
           "(CAST(:search AS TEXT) IS NULL OR " +
           " i.invoice_number ILIKE CONCAT('%', CAST(:search AS TEXT), '%') " +
           " OR i.customer_name ILIKE CONCAT('%', CAST(:search AS TEXT), '%') " +
           " OR i.customer_email ILIKE CONCAT('%', CAST(:search AS TEXT), '%') " +
           " OR i.place_of_supply ILIKE CONCAT('%', CAST(:search AS TEXT), '%'))",
           nativeQuery = true)
    Page<Invoice> findInvoicesFiltered(
            @Param("status") String status,
            @Param("isInterstate") Boolean isInterstate,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("search") String search,
            Pageable pageable
    );

    @Query(value = "SELECT " +
           "COUNT(*), " +
           "COALESCE(SUM(i.taxable_amount), 0.0), " +
           "COALESCE(SUM(i.cgst_amount), 0.0), " +
           "COALESCE(SUM(i.sgst_amount), 0.0), " +
           "COALESCE(SUM(i.igst_amount), 0.0), " +
           "COALESCE(SUM(i.total_tax_amount), 0.0), " +
           "COALESCE(SUM(i.total_amount), 0.0) " +
           "FROM invoices i WHERE " +
           "(CAST(:status AS TEXT) IS NULL OR i.status = CAST(:status AS TEXT)) AND " +
           "(CAST(:startDate AS TIMESTAMP) IS NULL OR i.invoice_date >= CAST(:startDate AS TIMESTAMP)) AND " +
           "(CAST(:endDate AS TIMESTAMP) IS NULL OR i.invoice_date <= CAST(:endDate AS TIMESTAMP))",
           nativeQuery = true)
    List<Object[]> getGstSummaryReport(
            @Param("status") String status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
}
