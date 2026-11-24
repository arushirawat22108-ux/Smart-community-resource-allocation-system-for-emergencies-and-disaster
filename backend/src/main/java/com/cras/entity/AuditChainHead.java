package com.cras.entity;

import com.cras.service.AuditHasher;
import jakarta.persistence.*;

/**
 * Single row (id = 1) holding the hash of the newest audit entry.
 * Locked with SELECT ... FOR UPDATE so concurrent transactions append to the chain one at a time.
 */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    @Id
    private Long id = 1L;

    @Column(nullable = false, length = 64)
    private String lastHash = AuditHasher.GENESIS;

    public Long getId() { return id; }
    public String getLastHash() { return lastHash; }
    public void setLastHash(String lastHash) { this.lastHash = lastHash; }
}
