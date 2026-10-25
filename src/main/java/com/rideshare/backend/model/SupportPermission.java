package com.rideshare.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "support_permissions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "permission"})
})
public class SupportPermission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false, length = 64)
    private String permission;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt = LocalDateTime.now();

    @Column(name = "granted_by")
    private String grantedBy;

    public SupportPermission(User user, String permission, String grantedBy) {
        this.user = user;
        this.permission = permission;
        this.grantedBy = grantedBy;
        this.grantedAt = LocalDateTime.now();
    }
}
