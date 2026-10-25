package com.rideshare.backend.repository;

import com.rideshare.backend.model.SupportPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupportPermissionRepository extends JpaRepository<SupportPermission, Long> {
    List<SupportPermission> findByUserId(Long userId);

    @Query("SELECT sp.permission FROM SupportPermission sp WHERE sp.user.id = :userId")
    List<String> findPermissionNamesByUserId(@Param("userId") Long userId);

    @Query("SELECT sp.permission FROM SupportPermission sp WHERE lower(sp.user.email) = lower(:email)")
    List<String> findPermissionNamesByUserEmail(@Param("email") String email);

    boolean existsByUserIdAndPermission(Long userId, String permission);

    @Modifying
    @Query("DELETE FROM SupportPermission sp WHERE sp.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM SupportPermission sp WHERE sp.user.id = :userId AND sp.permission = :permission")
    void deleteByUserIdAndPermission(@Param("userId") Long userId, @Param("permission") String permission);
}
