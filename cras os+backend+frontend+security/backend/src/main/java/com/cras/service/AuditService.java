package com.cras.service;

import com.cras.entity.AuditLog;
import com.cras.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void log(String actor, String action, String details) {
        repository.save(new AuditLog(actor, action, details));
    }
}
