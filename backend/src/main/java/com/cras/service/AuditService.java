package com.cras.service;

import com.cras.entity.AuditChainHead;
import com.cras.entity.AuditLog;
import com.cras.repository.AuditChainHeadRepository;
import com.cras.repository.AuditLogRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Append-only, hash-chained audit trail.
 *
 * Lock order rule (prevents deadlocks): business rows (resources) are always locked FIRST,
 * the audit-chain head is always locked LAST, because log() is called at the end of each transaction.
 */
@Service
public class AuditService {

    private final AuditLogRepository repo;
    private final AuditChainHeadRepository headRepo;

    public AuditService(AuditLogRepository repo, AuditChainHeadRepository headRepo) {
        this.repo = repo;
        this.headRepo = headRepo;
    }

    @Transactional
    public void log(String actor, String action, String detail) {
        AuditChainHead head = headRepo.lockHead().orElseGet(() -> headRepo.saveAndFlush(new AuditChainHead()));
        AuditLog entry = new AuditLog(actor, action, detail);
        entry.seal(head.getLastHash());
        repo.save(entry);
        head.setLastHash(entry.getEntryHash());
        headRepo.save(head);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> recent() {
        return repo.findTop100ByOrderByIdDesc();
    }

    public record ChainReport(boolean valid, int total, int sealed, int legacy, Long brokenAtId,
                              String message, String headHash) {}

    /** Recomputes every hash from the start and checks every link plus the head pointer. */
    @Transactional(readOnly = true)
    public ChainReport verify() {
        List<AuditLog> all = repo.findAll(Sort.by("id"));
        int legacy = 0, sealed = 0;
        String expectedPrev = null;
        for (AuditLog e : all) {
            if (e.getEntryHash() == null) {
                if (sealed > 0) {
                    return new ChainReport(false, all.size(), sealed, legacy, e.getId(),
                            "Entry #" + e.getId() + " has no hash but appears after the chain started (row inserted outside the application).", null);
                }
                legacy++;
                continue;
            }
            sealed++;
            if (expectedPrev == null) {
                if (!AuditHasher.GENESIS.equals(e.getPrevHash())) {
                    return new ChainReport(false, all.size(), sealed, legacy, e.getId(),
                            "First sealed entry #" + e.getId() + " does not start at GENESIS.", null);
                }
                expectedPrev = AuditHasher.GENESIS;
            }
            if (!expectedPrev.equals(e.getPrevHash())) {
                return new ChainReport(false, all.size(), sealed, legacy, e.getId(),
                        "Broken link at entry #" + e.getId() + ": it does not point to its predecessor (a row was deleted, inserted or reordered).", null);
            }
            String recomputed = AuditHasher.hash(e.getPrevHash(), e.getActor(), e.getAction(), e.getDetail(), e.getCreatedAt());
            if (!recomputed.equals(e.getEntryHash())) {
                return new ChainReport(false, all.size(), sealed, legacy, e.getId(),
                        "Entry #" + e.getId() + " was altered: its stored hash no longer matches its content.", null);
            }
            expectedPrev = e.getEntryHash();
        }
        String last = expectedPrev == null ? AuditHasher.GENESIS : expectedPrev;
        String head = headRepo.findById(1L).map(AuditChainHead::getLastHash).orElse(AuditHasher.GENESIS);
        if (!head.equals(last)) {
            return new ChainReport(false, all.size(), sealed, legacy, null,
                    "Chain head does not match the newest entry (trailing entries were deleted).", head);
        }
        return new ChainReport(true, all.size(), sealed, legacy, null,
                "All " + sealed + " sealed entries verified: every link and hash is intact."
                        + (legacy > 0 ? " (" + legacy + " older entries predate chaining.)" : ""), head);
    }
}
