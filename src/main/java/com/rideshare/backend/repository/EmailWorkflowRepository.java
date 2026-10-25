package com.rideshare.backend.repository;

import com.rideshare.backend.model.EmailWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailWorkflowRepository extends JpaRepository<EmailWorkflow, Long> {

    List<EmailWorkflow> findByEnabled(boolean enabled);

    Optional<EmailWorkflow> findFirstByTriggerTypeAndEnabledTrue(String triggerType);

    List<EmailWorkflow> findByTriggerType(String triggerType);
}
