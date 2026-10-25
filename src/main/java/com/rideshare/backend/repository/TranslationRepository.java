package com.rideshare.backend.repository;

import com.rideshare.backend.model.Translation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TranslationRepository extends JpaRepository<Translation, Long> {

    List<Translation> findByLocale(String locale);

    List<Translation> findByLocaleAndStatus(String locale, String status);

    Optional<Translation> findByTranslationKeyAndLocale(String translationKey, String locale);

    List<Translation> findByCategory(String category);

    List<Translation> findByStatus(String status);

    @Query("SELECT DISTINCT t.translationKey FROM Translation t ORDER BY t.translationKey ASC")
    List<String> findDistinctTranslationKeys();

    @Query("SELECT t FROM Translation t ORDER BY t.category ASC, t.translationKey ASC, t.locale ASC")
    List<Translation> findAllOrdered();

    long countByLocale(String locale);

    long countByLocaleAndStatus(String locale, String status);
}
