package com.cras.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tells the database WHO is changing stock, so the ledger trigger can record the real actor
 * (and a reason) instead of the generic MySQL account. Uses MySQL session variables, which live
 * on the very connection that the surrounding transaction is using.
 * Call it inside a @Transactional method, before the stock is modified.
 */
@Component
public class DbSession {

    private final JdbcTemplate jdbc;

    public DbSession(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void tag(String actor, String reason) {
        jdbc.update("SET @cras_actor = ?, @cras_reason = ?", actor, reason);
    }
}
