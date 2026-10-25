package com.rideshare.backend.repository;

import com.rideshare.backend.model.CarMake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;

public interface CarMakeRepository extends JpaRepository<CarMake, Long> {
    @Query("SELECT DISTINCT m FROM CarMake m LEFT JOIN FETCH m.models ORDER BY m.name ASC")
    List<CarMake> findAllWithModels();
}
