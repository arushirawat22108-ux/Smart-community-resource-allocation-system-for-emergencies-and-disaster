package com.cras.controller;

import com.cras.service.DbmsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dbms")
@CrossOrigin
@PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
public class DbmsController {

    private final DbmsService dbms;

    public DbmsController(DbmsService dbms) {
        this.dbms = dbms;
    }

    @GetMapping("/status")
    public Map<String, Object> status() { return dbms.status(); }

    @GetMapping("/ledger")
    public List<Map<String, Object>> ledger(@RequestParam(required = false) Long resourceId,
                                            @RequestParam(defaultValue = "40") int limit) {
        return dbms.ledger(resourceId, limit);
    }

    @GetMapping("/reconcile")
    public Map<String, Object> reconcile() { return dbms.reconcile(); }

    @GetMapping("/explain")
    public Map<String, Object> explain(@RequestParam(defaultValue = "queue") String plan,
                                       @RequestParam(required = false) Long resourceId) {
        return dbms.explain(plan, resourceId);
    }

    @GetMapping("/indexes")
    public List<Map<String, Object>> indexes() { return dbms.indexes(); }

    @GetMapping("/analytics")
    public List<Map<String, Object>> analytics() { return dbms.analytics(); }

    @GetMapping("/view/{name}")
    public List<Map<String, Object>> view(@PathVariable String name) { return dbms.viewRows(name); }
}
