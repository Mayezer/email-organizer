package com.mayezer.emailorganizer.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.mayezer.emailorganizer.model.AnalyzedEmail;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailAnalysis;
import com.mayezer.emailorganizer.model.EmailAnalysis.SecurityStatus;
import com.mayezer.emailorganizer.model.EmailIndicators;

public class EmailRepository {

    public record PendingTrash(String gmailId, OffsetDateTime pendingAt) {}

    private static final String EMAIL_COLUMNS = "gmail_id, sender, subject, thread_id, received_at";
    private final Connection connection;

    public EmailRepository(Connection connection) {
        this.connection = connection;
    }

    public boolean existsById(String gmailId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM emails WHERE gmail_id = ?")) {
            statement.setString(1, gmailId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public boolean save(Email email) throws SQLException {
        return save(email, null);
    }

    public boolean save(Email email, String syncAccount) throws SQLException {
        String sql = """
            INSERT INTO emails (gmail_id, sender, subject, thread_id, received_at, sync_account)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (gmail_id) DO NOTHING
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email.getId());
            statement.setString(2, email.getSender());
            statement.setString(3, email.getSubject());
            statement.setString(4, email.getThreadId());
            statement.setObject(5, databaseTime(email.getReceivedAt()));
            statement.setString(6, syncAccount);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean fillMissingMetadata(Email email) throws SQLException {
        String sql = """
            UPDATE emails SET thread_id = COALESCE(thread_id, ?), received_at = COALESCE(received_at, ?)
            WHERE gmail_id = ? AND (thread_id IS NULL OR received_at IS NULL)
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email.getThreadId());
            statement.setObject(2, databaseTime(email.getReceivedAt()));
            statement.setString(3, email.getId());
            return statement.executeUpdate() == 1;
        }
    }

    public List<Email> findMissingMetadata(String afterId, int limit) throws SQLException {
        return findBatch("(thread_id IS NULL OR received_at IS NULL)", afterId, limit);
    }

    public List<Email> findMissingIndicators(String afterId, int limit) throws SQLException {
        return findBatch("(has_attachments IS NULL OR has_links IS NULL)", afterId, limit);
    }

    public boolean fillMissingIndicators(String gmailId, EmailIndicators indicators) throws SQLException {
        String sql = """
            UPDATE emails
            SET has_attachments = COALESCE(emails.has_attachments, observed.attachments),
                has_links = COALESCE(emails.has_links, observed.links)
            FROM (VALUES (?::boolean, ?::boolean)) AS observed(attachments, links)
            WHERE gmail_id = ? AND (
                (has_attachments IS NULL AND observed.attachments IS NOT NULL) OR
                (has_links IS NULL AND observed.links IS NOT NULL)
            )
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, indicators.hasAttachments());
            statement.setObject(2, indicators.hasLinks());
            statement.setString(3, gmailId);
            return statement.executeUpdate() == 1;
        }
    }

    public List<Email> findLegacyAnalyses(String afterId, int limit) throws SQLException {
        return findBatch("processing_status = 'COMPLETED' AND security_status IS NULL", afterId, limit);
    }

    private List<Email> findBatch(String condition, String afterId, int limit) throws SQLException {
        String sql = "SELECT " + EMAIL_COLUMNS + " FROM emails WHERE " + condition
            + " AND gmail_id > ? ORDER BY gmail_id LIMIT ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, afterId);
            statement.setInt(2, limit);
            return readEmails(statement);
        }
    }

    public List<Email> findLegacyPending(int limit) throws SQLException {
        String sql = "SELECT " + EMAIL_COLUMNS + " FROM emails WHERE processing_status = 'PENDING'"
            + " AND sync_account IS NULL ORDER BY received_at NULLS LAST, gmail_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            return readEmails(statement);
        }
    }

    public Email findSyncPending(String account) throws SQLException {
        String sql = "SELECT " + EMAIL_COLUMNS + " FROM emails WHERE processing_status = 'PENDING'"
            + " AND sync_account = ? AND trashed_at IS NULL ORDER BY received_at NULLS LAST, gmail_id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readEmail(result) : null;
            }
        }
    }

    public List<PendingTrash> findPendingTrash(String account, int limit) throws SQLException {
        String sql = """
            SELECT gmail_id, trash_pending_at FROM emails
            WHERE sync_account = ? AND trash_pending_at IS NOT NULL
            ORDER BY trash_pending_at, gmail_id LIMIT ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<PendingTrash> pending = new ArrayList<>();
                while (result.next()) {
                    pending.add(new PendingTrash(result.getString(1), result.getObject(2, OffsetDateTime.class)));
                }
                return pending;
            }
        }
    }

    public void reconcileTrash(String account, PendingTrash pending, boolean trashed) throws SQLException {
        String update = trashed
            ? "trashed_at = CURRENT_TIMESTAMP, trash_pending_at = NULL"
            : "trash_pending_at = CURRENT_TIMESTAMP";
        try (PreparedStatement statement = connection.prepareStatement("UPDATE emails SET " + update
                + " WHERE gmail_id = ? AND sync_account = ? AND trash_pending_at = ?")) {
            statement.setString(1, pending.gmailId());
            statement.setString(2, account);
            statement.setObject(3, pending.pendingAt());
            statement.executeUpdate();
        }
    }

    public List<Email> findEarlierInThread(Email email, int limit) throws SQLException {
        String sql = "SELECT " + EMAIL_COLUMNS + " " + """
             FROM emails
             WHERE thread_id = ? AND received_at < ? AND gmail_id <> ?
               AND sync_account IS NOT DISTINCT FROM (SELECT sync_account FROM emails WHERE gmail_id = ?)
             ORDER BY received_at DESC, gmail_id LIMIT ?
             """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email.getThreadId());
            statement.setObject(2, databaseTime(email.getReceivedAt()));
            statement.setString(3, email.getId());
            statement.setString(4, email.getId());
            statement.setInt(5, limit);
            return readEmails(statement);
        }
    }

    public List<Email> findRecentFromSender(Email email, String address, int limit) throws SQLException {
        String sql = "SELECT " + EMAIL_COLUMNS + " " + """
             FROM emails
             WHERE received_at >= ? AND received_at < ?
               AND thread_id IS NOT NULL AND thread_id <> ? AND gmail_id <> ?
               AND sync_account IS NOT DISTINCT FROM (SELECT sync_account FROM emails WHERE gmail_id = ?)
               AND lower(btrim(COALESCE(substring(sender from '<([^<>]+)>[[:space:]]*$'), sender))) = ?
             ORDER BY received_at DESC, gmail_id LIMIT ?
             """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, databaseTime(email.getReceivedAt().minus(30, ChronoUnit.DAYS)));
            statement.setObject(2, databaseTime(email.getReceivedAt()));
            statement.setString(3, email.getThreadId());
            statement.setString(4, email.getId());
            statement.setString(5, email.getId());
            statement.setString(6, address);
            statement.setInt(7, limit);
            return readEmails(statement);
        }
    }

    public boolean saveAnalysis(String gmailId, EmailAnalysis analysis, boolean reanalysis) throws SQLException {
        String sql = """
            UPDATE emails SET category = ?, summary = ?, requires_action = ?,
                security_status = ?, security_reason = ?
            """ + (reanalysis
                ? " WHERE gmail_id = ? AND processing_status = 'COMPLETED' AND security_status IS NULL"
                : " , processing_status = 'COMPLETED', processed_at = CURRENT_TIMESTAMP"
                    + " WHERE gmail_id = ? AND processing_status = 'PENDING'");

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, analysis.getCategory());
            statement.setString(2, analysis.getSummary());
            statement.setBoolean(3, analysis.getRequiresAction());
            statement.setString(4, analysis.getSecurityStatus().name());
            statement.setString(5, analysis.getSecurityReason());
            statement.setString(6, gmailId);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean markFailed(String gmailId) throws SQLException {
        String sql = "UPDATE emails SET processing_status = 'FAILED' WHERE gmail_id = ? AND processing_status = 'PENDING'";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gmailId);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean markForRetry(String gmailId) throws SQLException {
        String sql = "UPDATE emails SET processing_status = 'PENDING' WHERE gmail_id = ? AND processing_status = 'FAILED'";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gmailId);
            return statement.executeUpdate() == 1;
        }
    }

    public List<AnalyzedEmail> findCompleted() throws SQLException {
        String sql = "SELECT " + EMAIL_COLUMNS + """
             , category, summary, requires_action, security_status, security_reason
             FROM emails WHERE processing_status = 'COMPLETED'
                 AND processed_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'
             ORDER BY processed_at DESC
             """;
        List<AnalyzedEmail> analyzedEmails = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String status = result.getString("security_status");
                EmailAnalysis analysis = new EmailAnalysis(
                    result.getString("category"),
                    result.getString("summary"),
                    result.getBoolean("requires_action"),
                    status == null ? null : SecurityStatus.valueOf(status),
                    result.getString("security_reason"));
                analyzedEmails.add(new AnalyzedEmail(readEmail(result), analysis));
            }
        }
        return analyzedEmails;
    }

    private List<Email> readEmails(PreparedStatement statement) throws SQLException {
        List<Email> emails = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                emails.add(readEmail(result));
            }
        }
        return emails;
    }

    private Email readEmail(ResultSet result) throws SQLException {
        OffsetDateTime receivedAt = result.getObject("received_at", OffsetDateTime.class);
        return new Email(
            result.getString("gmail_id"),
            result.getString("sender"),
            result.getString("subject"),
            result.getString("thread_id"),
            receivedAt == null ? null : receivedAt.toInstant());
    }

    private OffsetDateTime databaseTime(Instant time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
