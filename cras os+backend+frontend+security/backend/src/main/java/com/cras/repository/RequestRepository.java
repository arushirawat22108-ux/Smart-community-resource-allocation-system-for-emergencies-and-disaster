package com.cras.repository;

import com.cras.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RequestRepository extends JpaRepository<EmergencyRequest, Long> {
    List<EmergencyRequest> findByResourceIdAndStatusOrderByPriorityScoreDescCreatedAtAsc(
            Long resourceId, RequestStatus status);

    List<EmergencyRequest> findByStatusOrderByPriorityScoreDescCreatedAtAsc(RequestStatus status);

    Optional<EmergencyRequest> findByIdAndRequesterEmail(Long id, String email);
}
