package com.rideshare.backend.repository;

import com.rideshare.backend.model.FaqItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaqItemRepository extends JpaRepository<FaqItem, Long> {
    List<FaqItem> findByActiveTrueOrderBySortOrderAsc();
    List<FaqItem> findAllByOrderBySortOrderAsc();
}
