package com.rideshare.backend.repository;

import com.rideshare.backend.model.CarModel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarModelRepository extends JpaRepository<CarModel, Long> {
    List<CarModel> findByMakeId(Long makeId);
}
