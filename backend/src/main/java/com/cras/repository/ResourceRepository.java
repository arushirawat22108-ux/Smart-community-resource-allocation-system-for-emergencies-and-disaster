package com.cras.repository;

import com.cras.entity.Resource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    /** Pessimistic write lock — used by allocation and stock updates (DBMS concurrency). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Resource r where r.id = :id")
    Optional<Resource> findByIdForUpdate(Long id);

    /**
     * Locks EVERY resource row in ascending id order. A fixed global lock order is how CRAS
     * prevents deadlock when one operation needs several resources (Banker's algorithm).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Resource r order by r.id")
    List<Resource> lockAllOrdered();

    List<Resource> findAllByOrderByIdAsc();

    Optional<Resource> findByName(String name);
}
