package com.cras.repository;

import com.cras.entity.Allocation;
import com.cras.entity.AllocationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {

    List<Allocation> findByResourceIdAndStatusOrderByQuantityAsc(Long resourceId, AllocationStatus status);

    Optional<Allocation> findByRequestIdAndStatus(Long requestId, AllocationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Allocation a where a.id = :id")
    Optional<Allocation> findByIdForUpdate(Long id);

    List<Allocation> findTop100ByOrderByAllocatedAtDesc();

    List<Allocation> findByRequestId(Long requestId);
}
