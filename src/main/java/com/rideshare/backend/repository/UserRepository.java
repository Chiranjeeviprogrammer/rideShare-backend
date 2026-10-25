package com.rideshare.backend.repository;

import com.rideshare.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    User findFirstByEmailIgnoreCase(String email);
    User findFirstByPhoneNumber(String phoneNumber);
    User findFirstByReferralCode(String referralCode);

    default User findByEmail(String email) {
        return email == null ? null : findFirstByEmailIgnoreCase(email.trim());
    }

    default User findByPhoneNumber(String phoneNumber) {
        return phoneNumber == null ? null : findFirstByPhoneNumber(phoneNumber.trim());
    }

    default User findByReferralCode(String referralCode) {
        return referralCode == null ? null : findFirstByReferralCode(referralCode.trim());
    }

    long countByRole(String role);
    long countByStatus(String status);
    List<User> findByRole(String role);

    @Query("SELECT u FROM User u WHERE " +
           "(CAST(:role AS string) IS NULL OR u.role = :role) AND " +
           "(CAST(:status AS string) IS NULL OR u.status = :status) AND " +
           "(:verified IS NULL OR u.verified = :verified) AND " +
           "(CAST(:search AS string) IS NULL OR " +
           "CAST(u.id AS string) LIKE CONCAT('%', CAST(:search AS string), '%') OR " +
           "LOWER(u.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(u.phoneNumber IS NOT NULL AND u.phoneNumber LIKE CONCAT('%', CAST(:search AS string), '%')))")
    Page<User> searchUsers(@Param("role") String role,
                           @Param("status") String status,
                           @Param("verified") Boolean verified,
                           @Param("search") String search,
                           Pageable pageable);

    default Page<User> searchUsers(String role, String status, String search, Pageable pageable) {
        return searchUsers(role, status, null, search, pageable);
    }
}
