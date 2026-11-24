package com.cras.repository;

import com.cras.entity.ReliefCamp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReliefCampRepository extends JpaRepository<ReliefCamp, Long> {
    List<ReliefCamp> findAllByOrderByIdAsc();
}
