package com.mayezer.emailorganizer.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailAnalysis;

public final class EmailSyncRepository {
    public enum Phase { IMPORTING, ANALYZING }
    public record ImportProgress(String pageToken, String fullHistoryId, int historyOffset, boolean recoveryStarted) {
        ImportProgress at(String pageToken, String fullHistoryId, int historyOffset) {
            return new ImportProgress(pageToken, fullHistoryId, historyOffset, recoveryStarted);
        }

        ImportProgress clear() {
            return at(null, null, 0);
        }
    }
    public record Lease(UUID jobId, UUID token, Phase phase, ImportProgress progress, int attempts) {}

    private final Connection connection;
    private final EmailRepository emails;

    public EmailSyncRepository(Connection connection) {
        this.connection = connection;
        this.emails = new EmailRepository(connection);
    }

    public Lease claim(String account) throws SQLException {
        UUID token = UUID.randomUUID();
        // The partial unique index allows only one active row for this account.
        String sql = """
            UPDATE email_sync_jobs SET lease_token = ?, lease_until = clock_timestamp() + interval '3 minutes'
            WHERE id = (
                SELECT id FROM email_sync_jobs WHERE account = ? AND status IN ('IMPORTING', 'ANALYZING')
                AND available_at <= clock_timestamp() AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, status, page_token, full_history_id, history_offset, history_recovery_started, attempts
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, token);
            statement.setString(2, account);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Lease(result.getObject("id", UUID.class), token,
                    Phase.valueOf(result.getString("status")),
                    new ImportProgress(result.getString("page_token"), result.getString("full_history_id"),
                        result.getInt("history_offset"), result.getBoolean("history_recovery_started")),
                    result.getInt("attempts")) : null;
            }
        }
    }

    public String historyId(String account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT history_id FROM email_sync_checkpoints WHERE account = ?")) {
            statement.setString(1, account);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    public void startFullScan(Lease lease, String historyId) throws SQLException {
        finish(lease, () -> update(lease, "IMPORTING", lease.progress().at(null, historyId, 0), 0, 0, 0, 0, 0));
    }

    public void saveFullPage(Lease lease, String account, List<Email> page, String nextToken) throws SQLException {
        ImportProgress next = nextToken == null ? lease.progress().clear()
            : lease.progress().at(nextToken, lease.progress().fullHistoryId(), 0);
        // After the full scan, read any changes that arrived while it was running.
        String historyId = nextToken == null ? lease.progress().fullHistoryId() : null;
        saveImportBatch(lease, account, page, next, historyId, "IMPORTING");
    }

    public void saveHistoryBatch(Lease lease, String account, List<Email> page, String nextToken,
                                 int nextOffset, String historyId) throws SQLException {
        saveImportBatch(lease, account, page, lease.progress().at(nextToken, null, nextOffset),
            historyId, historyId == null ? "IMPORTING" : "ANALYZING");
    }

    private void saveImportBatch(Lease lease, String account, List<Email> page, ImportProgress next,
                                 String historyId, String status) throws SQLException {
        finish(lease, () -> {
            int imported = 0;
            for (Email email : page) {
                if (emails.save(email, account)) {
                    imported++;
                }
            }
            if (historyId != null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO email_sync_checkpoints (account, history_id) VALUES (?, ?)
                    ON CONFLICT (account) DO UPDATE SET history_id = EXCLUDED.history_id
                    """)) {
                    statement.setString(1, account);
                    statement.setString(2, historyId);
                    statement.executeUpdate();
                }
            }
            update(lease, status, next, 0, 0, imported, 0, 0);
        });
    }

    public void recoverExpiredHistory(Lease lease, String account) throws SQLException {
        finish(lease, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM email_sync_checkpoints WHERE account = ?")) {
                statement.setString(1, account);
                statement.executeUpdate();
            }
            // A very long scan can outlive its own starting history. Avoid looping forever.
            update(lease, lease.progress().recoveryStarted() ? "FAILED" : "IMPORTING",
                new ImportProgress(null, null, 0, true), 0, 0, 0, 0, 0);
        });
    }

    public void saveAnalysis(Lease lease, Email email, EmailAnalysis analysis) throws SQLException {
        finish(lease, () -> {
            boolean saved = analysis != null && emails.saveAnalysis(email.getId(), analysis, false);
            boolean failed = analysis == null && emails.markFailed(email.getId());
            update(lease, "ANALYZING", lease.progress().clear(), 0, 0, 0, saved ? 1 : 0, failed ? 1 : 0);
        });
    }

    public void complete(Lease lease) throws SQLException {
        finish(lease, () -> update(lease, "COMPLETED", lease.progress().clear(), 0, 0, 0, 0, 0));
    }

    public void retry(Lease lease) throws SQLException {
        int attempts = lease.attempts() + 1;
        boolean exhausted = attempts >= 5;
        finish(lease, () -> update(lease, exhausted ? "FAILED" : lease.phase().name(), lease.progress(),
            attempts, exhausted ? 0 : 5 << (attempts - 1), 0, 0, 0));
    }

    private void update(Lease lease, String status, ImportProgress progress, int attempts, int delaySeconds,
                        int imported, int analyzed, int failed) throws SQLException {
        String sql = """
            UPDATE email_sync_jobs SET status = ?, page_token = ?, full_history_id = ?, history_offset = ?,
                history_recovery_started = ?, attempts = ?,
                available_at = clock_timestamp() + make_interval(secs => ?), updated_at = clock_timestamp(),
                lease_token = NULL, lease_until = NULL, imported_count = imported_count + ?,
                analyzed_count = analyzed_count + ?, analysis_failed_count = analysis_failed_count + ?
            WHERE id = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, progress.pageToken());
            statement.setString(3, progress.fullHistoryId());
            statement.setInt(4, progress.historyOffset());
            statement.setBoolean(5, progress.recoveryStarted());
            statement.setInt(6, attempts);
            statement.setInt(7, delaySeconds);
            statement.setInt(8, imported);
            statement.setInt(9, analyzed);
            statement.setInt(10, failed);
            statement.setObject(11, lease.jobId());
            statement.executeUpdate();
        }
    }

    private void finish(Lease lease, Change change) throws SQLException {
        // No Gmail/OpenAI calls happen while this transaction is open. The row
        // lock fences off a worker whose lease expired during a remote call.
        connection.setAutoCommit(false);
        try {
            if (lockLease(lease)) {
                change.apply();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
        connection.setAutoCommit(true);
    }

    private boolean lockLease(Lease lease) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM email_sync_jobs WHERE id = ? AND lease_token = ? AND lease_until > clock_timestamp()
            FOR UPDATE
            """)) {
            statement.setObject(1, lease.jobId());
            statement.setObject(2, lease.token());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @FunctionalInterface
    private interface Change { void apply() throws SQLException; }
}
