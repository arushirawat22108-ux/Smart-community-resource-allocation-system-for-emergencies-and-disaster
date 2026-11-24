package com.cras.repository;

import com.cras.entity.CampClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CampClaimRepository extends JpaRepository<CampClaim, Long> {

    @Query("select c from CampClaim c")
    List<CampClaim> findEverything();

    /** Locked read used by grant / release so two Banker operations never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CampClaim c order by c.id")
    List<CampClaim> lockAllOrdered();
}
