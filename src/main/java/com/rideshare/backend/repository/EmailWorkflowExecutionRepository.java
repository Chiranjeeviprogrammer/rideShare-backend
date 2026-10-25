package com.rideshare.backend.repository;

import com.rideshare.backend.model.EmailWorkflowExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailWorkflowExecutionRepository extends JpaRepository<EmailWorkflowExecution, Long> {

    boolean existsByWorkflowIdAndContactEmail(Long workflowId, String contactEmail);
}
