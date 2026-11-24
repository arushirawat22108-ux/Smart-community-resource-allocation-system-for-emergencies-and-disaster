package com.cras.repository;

import com.cras.entity.AuditChainHead;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AuditChainHead h where h.id = 1")
    Optional<AuditChainHead> lockHead();
}
