package com.rideshare.backend.repository;

import com.rideshare.backend.model.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    Optional<SupportTicket> findByTicketNumber(String ticketNumber);

    long countByStatus(String status);

    @Query("SELECT t FROM SupportTicket t WHERE " +
           "(CAST(:status AS string) IS NULL OR t.status = :status) AND " +
           "(CAST(:priority AS string) IS NULL OR t.priority = :priority) AND " +
           "(CAST(:category AS string) IS NULL OR t.category = :category) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(t.ticketNumber) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(t.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(t.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(t.subject) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<SupportTicket> searchTickets(@Param("status") String status,
                                      @Param("priority") String priority,
                                      @Param("category") String category,
                                      @Param("search") String search,
                                      Pageable pageable);
}
