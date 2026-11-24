package com.cras.entity;

import com.cras.service.AuditHasher;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** One audit record. Rows are hash-chained: see {@link AuditHasher}. */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String actor;
    private String action;
    @Column(length = 1000)
    private String detail;
    private LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

    /** Hash of the previous entry (or GENESIS). Null only for rows written before chaining existed. */
    @Column(length = 64)
    private String prevHash;
    @Column(length = 64)
    private String entryHash;

    public AuditLog() {}

    public AuditLog(String actor, String action, String detail) {
        this.actor = actor;
        this.action = action;
        this.detail = detail != null && detail.length() > 1000 ? detail.substring(0, 1000) : detail;
        this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Links this row to its predecessor and computes its own hash. */
    public void seal(String previousHash) {
        this.prevHash = previousHash;
        this.entryHash = AuditHasher.hash(previousHash, actor, action, detail, createdAt);
    }

    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getPrevHash() { return prevHash; }
    public String getEntryHash() { return entryHash; }
}
