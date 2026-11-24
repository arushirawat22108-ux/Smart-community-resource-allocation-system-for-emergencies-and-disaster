package com.cras.repository;

import com.cras.entity.EmergencyRequest;
import com.cras.entity.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RequestRepository extends JpaRepository<EmergencyRequest, Long> {

    List<EmergencyRequest> findByResourceIdAndStatusOrderByPriorityScoreDescCreatedAtAsc(
            Long resourceId, RequestStatus status);

    List<EmergencyRequest> findByStatusOrderByPriorityScoreDescCreatedAtAsc(RequestStatus status);

    Optional<EmergencyRequest> findByIdAndRequesterEmail(Long id, String email);

    long countByStatus(RequestStatus status);

    long countByLocationIdAndStatus(Long locationId, RequestStatus status);

    long countByResourceIdAndCreatedAtAfter(Long resourceId, LocalDateTime since);
}
