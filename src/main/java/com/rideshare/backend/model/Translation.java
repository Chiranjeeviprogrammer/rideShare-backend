package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "translations",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"translation_key", "locale"})
    },
    indexes = {
        @Index(name = "idx_translations_locale_status", columnList = "locale, status"),
        @Index(name = "idx_translations_key", columnList = "translation_key")
    }
)
public class Translation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "translation_key", nullable = false, length = 150)
    private String translationKey;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale; // 'en' or 'hi'

    @Column(name = "translation_value", columnDefinition = "TEXT", nullable = false)
    private String value;

    @Column(name = "category", length = 50)
    private String category = "COMMON"; // AUTH, DASHBOARD, BOOKING, WALLET, DRIVER, SAFETY, SUPPORT, COMMON

    @Column(name = "status", length = 20)
    private String status = "PUBLISHED"; // PUBLISHED, DRAFT

    @Column(name = "version")
    private Integer version = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = "PUBLISHED";
        }
        if (this.version == null) {
            this.version = 1;
        }
        if (this.category == null) {
            this.category = "COMMON";
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.version != null) {
            this.version += 1;
        } else {
            this.version = 1;
        }
    }
}
