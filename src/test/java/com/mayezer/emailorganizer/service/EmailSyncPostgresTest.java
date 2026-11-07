package com.mayezer.emailorganizer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.http.GenericUrl;
import com.google.api.services.gmail.Gmail;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailAnalysis;
import com.mayezer.emailorganizer.model.EmailAnalysis.SecurityStatus;
import com.mayezer.emailorganizer.repository.EmailRepository;
import com.mayezer.emailorganizer.repository.EmailSyncRepository;
import com.mayezer.emailorganizer.service.EmailSyncWorker.HistoryBatch;
import com.mayezer.emailorganizer.service.EmailSyncWorker.Page;

@EnabledIfEnvironmentVariable(named = "EMAIL_SYNC_TEST_DB_URL", matches = ".+")
class EmailSyncPostgresTest {
    private final String prefix = "sync-test-" + UUID.randomUUID();
    private final String account = prefix + "@example.test";

    @Test
    void resumesCommittedPageAfterWorkerStopsAndKeepsMetadataWhenAnalysisFails() throws SQLException {
        var mailbox = new EmailSyncWorker.Mailbox() {
            @Override public void prepare() {}

            @Override public String currentHistoryId() { return "100"; }

            @Override public Page fetchPage(String token, EmailRepository repository) {
                if (token == null) {
                    return new Page(List.of(email(1), email(2), email(3), email(4), email(5)), "next");
                }
                assertEquals("next", token);
                return new Page(List.of(email(5), email(6), email(7)), null);
            }

            @Override public HistoryBatch fetchHistory(String historyId, String token, int offset,
                                                        EmailRepository repository) {
                assertEquals("100", historyId);
                return new HistoryBatch(List.of(), null, 0, "110");
            }

            @Override public EmailAnalysis analyze(Email email, EmailRepository repository) throws IOException {
                if (email.getId().equals(email(6).getId())) {
                    throw new IOException("Simulated analysis failure");
                }
                return new EmailAnalysis("other", "Synthetic summary", false, SecurityStatus.NO_ALERT, null);
            }
        };

        EmailSyncRepository.Lease abandoned;
        try (Connection first = open(); Connection second = open()) {
            enqueue(first);
            assertTrue(new EmailSyncWorker(account, mailbox).runStep(first));
            assertEquals("100", jobField(second, "full_history_id"));
            assertTrue(new EmailSyncWorker(account, mailbox).runStep(first));
            assertEquals(5, countEmails(second, "PENDING"));
            assertEquals("next", jobField(second, "page_token"));

            abandoned = new EmailSyncRepository(first).claim(account);
            assertNotNull(abandoned);
            assertNull(new EmailSyncRepository(second).claim(account));
        }

        try (Connection restarted = open()) {
            updateJob(restarted, "lease_until = clock_timestamp() - interval '1 second'");
            var worker = new EmailSyncWorker(account, mailbox);
            assertTrue(worker.runStep(restarted));
            assertEquals("IMPORTING", jobField(restarted, "status"));
            assertEquals(7, countEmails(restarted, "PENDING"));

            var jobs = new EmailSyncRepository(restarted);
            jobs.saveFullPage(abandoned, account, List.of(email(8)), null);
            assertEquals(7, countEmails(restarted, "PENDING"));
            assertEquals("100", jobs.historyId(account));
            assertTrue(worker.runStep(restarted));
            assertEquals("ANALYZING", jobField(restarted, "status"));
            assertEquals("110", jobs.historyId(account));

            for (int i = 0; i < 8; i++) {
                assertTrue(worker.runStep(restarted));
            }
            assertFalse(worker.runStep(restarted));
            assertEquals("COMPLETED", jobField(restarted, "status"));
            assertEquals("7", jobField(restarted, "imported_count"));
            assertEquals("6", jobField(restarted, "analyzed_count"));
            assertEquals("1", jobField(restarted, "analysis_failed_count"));
            assertEquals(6, countEmails(restarted, "COMPLETED"));
            assertEquals(1, countEmails(restarted, "FAILED"));
            assertTrue(new EmailRepository(restarted).existsById(email(6).getId()));
        }
    }

    @Test
    void rollsBackPartialPageAndCheckpointTogether() throws SQLException {
        try (Connection connection = open(); Connection reader = open()) {
            enqueue(connection);
            var jobs = new EmailSyncRepository(connection);
            jobs.startFullScan(jobs.claim(account), "100");
            jobs.saveFullPage(jobs.claim(account), account, List.of(email(1)), null);
            jobs.saveHistoryBatch(jobs.claim(account), account, List.of(email(2)), "next", 0, null);
            var lease = jobs.claim(account);

            // Force the job write to fail after the email and account checkpoint have been written.
            assertThrows(SQLException.class, () ->
                jobs.saveHistoryBatch(lease, account, List.of(email(3)), null, -1, "110"));
            assertEquals(2, countEmails(reader, "PENDING"));
            assertEquals("100", new EmailSyncRepository(reader).historyId(account));
            assertEquals("next", jobField(reader, "page_token"));
        }
        try (Connection restarted = open()) {
            updateJob(restarted, "lease_until = clock_timestamp() - interval '1 second'");
            var jobs = new EmailSyncRepository(restarted);
            var lease = jobs.claim(account);
            assertEquals("next", lease.progress().pageToken());
            jobs.saveHistoryBatch(lease, account, List.of(email(2), email(3)), null, 0, "110");
            assertEquals(3, countEmails(restarted, "PENDING"));
            assertEquals("110", jobs.historyId(account));
        }
    }

    @Test
    void failedPageKeepsTheCheckpointAndRetriesWithABoundedBudget() throws SQLException {
        try (Connection connection = open()) {
            enqueue(connection);
            var jobs = new EmailSyncRepository(connection);
            jobs.startFullScan(jobs.claim(account), "100");
            jobs.saveFullPage(jobs.claim(account), account, List.of(email(1)), "next");
            for (int attempt = 1; attempt <= 5; attempt++) {
                var lease = jobs.claim(account);
                assertEquals("next", lease.progress().pageToken());
                jobs.retry(lease);
                assertEquals(Integer.toString(attempt), jobField(connection, "attempts"));
                assertNull(jobs.claim(account));
                updateJob(connection, "available_at = clock_timestamp() - interval '1 second'");
            }
            assertEquals("FAILED", jobField(connection, "status"));
            assertNull(jobs.claim(account));
            assertEquals(1, countEmails(connection, "PENDING"));
        }
    }

    @Test
    void importsOnlyGmailAdditionsInBatchesAndRetriesTheSameHistoryPageAfter429() throws SQLException {
        String anchor = "18446744073709551600";
        var gmail = new GmailFixture();
        gmail.profile(anchor);
        gmail.fullPage(null, null, 1);
        gmail.metadata(1, 200);
        gmail.history(anchor, null, null, "18446744073709551602", 1, 2);
        gmail.metadata(2, 200);
        var worker = gmail.worker();

        try (Connection connection = open()) {
            enqueue(connection);
            runSteps(worker, connection, 6); // anchor, full page, catch-up, two analyses, completion
            assertEquals("COMPLETED", jobField(connection, "status"));
            assertEquals(2, countEmails(connection, "COMPLETED"));
            assertEquals("18446744073709551602", new EmailSyncRepository(connection).historyId(account));
            enqueue(connection);
        }

        String start = "18446744073709551602";
        String end = "18446744073709551614";
        gmail.history(start, null, "page-two", end, 3, 4, 5, 6, 7, 8, 9);
        for (int i = 3; i <= 7; i++) {
            gmail.metadata(i, 200);
        }
        gmail.history(start, null, "page-two", end, 3, 4, 5, 6, 7, 8, 9);
        gmail.metadata(8, 200);
        gmail.metadata(9, 429);
        gmail.history(start, null, "page-two", end, 3, 4, 5, 6, 7, 8, 9);
        gmail.metadata(8, 200);
        gmail.metadata(9, 200);
        gmail.history(start, "page-two", null, end, 9, 10, 11, 12);
        gmail.metadata(10, 200);
        gmail.metadata(11, 404);
        gmail.metadata(12, 200);

        try (Connection first = open()) {
            var jobs = new EmailSyncRepository(first);
            assertTrue(gmail.worker().runStep(first));
            assertEquals(5, countEmails(first, "PENDING"));
            assertEquals("5", jobField(first, "history_offset"));
            assertNull(jobField(first, "page_token"));
            assertEquals(start, jobs.historyId(account));
            assertTrue(gmail.worker().runStep(first));
            assertEquals("1", jobField(first, "attempts"));
            assertEquals("5", jobField(first, "history_offset"));
            assertEquals(5, countEmails(first, "PENDING"));
            assertFalse(gmail.worker().runStep(first));
        }
        try (Connection restarted = open()) {
            updateJob(restarted, "available_at = clock_timestamp() - interval '1 second'");
            var jobs = new EmailSyncRepository(restarted);
            assertTrue(gmail.worker().runStep(restarted));
            assertEquals("page-two", jobField(restarted, "page_token"));
            assertEquals("0", jobField(restarted, "history_offset"));
            assertEquals(7, countEmails(restarted, "PENDING"));
            assertEquals(start, jobs.historyId(account));
            assertTrue(gmail.worker().runStep(restarted));
            assertEquals("ANALYZING", jobField(restarted, "status"));
            assertEquals("9", jobField(restarted, "imported_count"));
            assertEquals(9, countEmails(restarted, "PENDING"));
            assertEquals(end, jobs.historyId(account));
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12).stream()
                .map(n -> email(n).getId()).sorted().toList(), listedIds(restarted));
        }
        gmail.assertConsumed();
    }

    @Test
    void expiredHistoryRestartsAFullScanAndResumesItsPagesWithoutDuplicatingEmails() throws SQLException {
        var gmail = new GmailFixture();
        gmail.error("/gmail/v1/users/me/history", 404);
        gmail.profile("200");
        gmail.fullPage(null, "full-next", 1, 2, 3, 4, 5);
        for (int i = 2; i <= 5; i++) {
            gmail.metadata(i, 200);
        }
        gmail.fullPage("full-next", null, 5, 6, 7);
        gmail.metadata(6, 200);
        gmail.metadata(7, 200);
        gmail.history("200", null, null, "220", 7, 8);
        gmail.metadata(8, 200);

        try (Connection first = open()) {
            enqueue(first);
            var jobs = new EmailSyncRepository(first);
            jobs.startFullScan(jobs.claim(account), "100");
            jobs.saveFullPage(jobs.claim(account), account, List.of(email(1)), null);
            try (var insert = first.prepareStatement(
                    "INSERT INTO email_sync_checkpoints (account, history_id) VALUES (?, '77')")) {
                insert.setString(1, "other-" + account);
                insert.executeUpdate();
            }
            runSteps(gmail.worker(), first, 3);
            assertNull(jobs.historyId(account));
            assertEquals("77", jobs.historyId("other-" + account));
            assertEquals("full-next", jobField(first, "page_token"));
            assertEquals("200", jobField(first, "full_history_id"));
            assertEquals(5, countEmails(first, "PENDING"));
        }
        try (Connection restarted = open()) {
            runSteps(gmail.worker(), restarted, 2);
            assertEquals("ANALYZING", jobField(restarted, "status"));
            assertEquals("220", new EmailSyncRepository(restarted).historyId(account));
            assertEquals(8, countEmails(restarted, "PENDING"));
            assertEquals(8, listedIds(restarted).size());
        }
        gmail.assertConsumed();
    }

    @Test
    void anEmptyHistoryCompletesWithoutListingTheMailboxAgain() throws SQLException {
        try (Connection connection = open()) {
            enqueue(connection);
            var jobs = new EmailSyncRepository(connection);
            jobs.startFullScan(jobs.claim(account), "100");
            jobs.saveFullPage(jobs.claim(account), account, List.of(email(1)), null);
            jobs.saveHistoryBatch(jobs.claim(account), account, List.of(), null, 0, "110");
            jobs.complete(jobs.claim(account));
            enqueue(connection);
            var gmail = new GmailFixture();
            gmail.history("110", null, null, "120");
            runSteps(gmail.worker(), connection, 3); // empty history, outstanding analysis, completion
            assertEquals("COMPLETED", jobField(connection, "status"));
            assertEquals("0", jobField(connection, "imported_count"));
            assertEquals("120", jobs.historyId(account));
            gmail.assertConsumed();
        }
    }

    @Test
    void repeatedHistoryExpiryStopsInsteadOfRescanningForeverAndIgnoresAnExpiredLease() throws SQLException {
        try (Connection connection = open()) {
            enqueue(connection);
            var jobs = new EmailSyncRepository(connection);
            jobs.startFullScan(jobs.claim(account), "100");
            jobs.saveFullPage(jobs.claim(account), account, List.of(email(1)), null);
            var stale = jobs.claim(account);
            updateJob(connection, "lease_until = clock_timestamp() - interval '1 second'");
            var current = jobs.claim(account);
            jobs.recoverExpiredHistory(stale, account);
            assertEquals("100", jobs.historyId(account));
            jobs.recoverExpiredHistory(current, account);
            assertEquals("IMPORTING", jobField(connection, "status"));
            assertNull(jobs.historyId(account));
            jobs.startFullScan(jobs.claim(account), "200");
            jobs.saveFullPage(jobs.claim(account), account, List.of(email(1)), null);
            jobs.recoverExpiredHistory(jobs.claim(account), account);
            assertEquals("FAILED", jobField(connection, "status"));
            assertNull(jobs.historyId(account));
            assertEquals(1, countEmails(connection, "PENDING"));
        }
    }

    private static void runSteps(EmailSyncWorker worker, Connection connection, int steps) throws SQLException {
        for (int i = 0; i < steps; i++) {
            assertTrue(worker.runStep(connection));
        }
    }

    private final class GmailFixture extends MockHttpTransport {
        private record Exchange(String path, int status, String body, String start, String token, boolean paginated) {}
        private final ArrayDeque<Exchange> responses = new ArrayDeque<>();
        private static final String USER_PATH = "/gmail/v1/users/me/";

        private EmailSyncWorker worker() {
            var client = new Gmail.Builder(this, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("email-sync-test").build();
            var adapter = new GmailSyncMailbox(account, client);
            return new EmailSyncWorker(account, new EmailSyncWorker.Mailbox() {
                @Override public void prepare() {}
                @Override public String currentHistoryId() throws IOException { return adapter.currentHistoryId(); }
                @Override public Page fetchPage(String token, EmailRepository repository) throws IOException, SQLException {
                    return adapter.fetchPage(token, repository);
                }
                @Override public HistoryBatch fetchHistory(String historyId, String token, int offset,
                        EmailRepository repository) throws IOException, SQLException {
                    return adapter.fetchHistory(historyId, token, offset, repository);
                }
                @Override public EmailAnalysis analyze(Email email, EmailRepository repository) {
                    return new EmailAnalysis("other", "Synthetic summary", false, SecurityStatus.NO_ALERT, null);
                }
            });
        }

        private void profile(String historyId) {
            responses.add(new Exchange(USER_PATH + "profile", 200, "{\"historyId\":\"" + historyId + "\"}", null, null, false));
        }

        private void fullPage(String token, String next, int... messages) {
            String body = "{\"messages\":[" + Arrays.stream(messages).mapToObj(this::messageRef)
                .collect(Collectors.joining(",")) + "]" + nextToken(next) + "}";
            responses.add(new Exchange(USER_PATH + "messages", 200, body, null, token, true));
        }

        private void history(String start, String token, String next, String checkpoint, int... messages) {
            String body = "{\"history\":[{\"messagesAdded\":[" + Arrays.stream(messages)
                .mapToObj(n -> "{\"message\":" + messageRef(n) + "}").collect(Collectors.joining(","))
                + "]}],\"historyId\":\"" + checkpoint + "\"" + nextToken(next) + "}";
            responses.add(new Exchange(USER_PATH + "history", 200, body, start, token, true));
        }

        private String messageRef(int n) { return "{\"id\":\"" + email(n).getId() + "\"}"; }
        private String nextToken(String next) { return next == null ? "" : ",\"nextPageToken\":\"" + next + "\""; }

        private void metadata(int n, int status) {
            String path = USER_PATH + "messages/" + email(n).getId();
            if (status != 200) {
                error(path, status);
            } else {
                responses.add(new Exchange(path, 200, messageRef(n), null, null, false));
            }
        }

        private void error(String path, int status) {
            responses.add(new Exchange(path, status, "{\"error\":{\"code\":" + status + "}}", null, null, false));
        }

        @Override public LowLevelHttpRequest buildRequest(String method, String url) {
            assertEquals("GET", method);
            assertFalse(responses.isEmpty(), "Unexpected Gmail request: " + url);
            Exchange exchange = responses.removeFirst();
            assertEquals(exchange.path(), URI.create(url).getPath());
            if (exchange.paginated()) {
                GenericUrl parsed = new GenericUrl(url);
                assertEquals("5", parsed.getFirst("maxResults"));
                assertEquals(exchange.token(), parsed.getFirst("pageToken"));
                if (exchange.start() != null) {
                    assertEquals(exchange.start(), parsed.getFirst("startHistoryId"));
                    assertEquals("messageAdded", parsed.getFirst("historyTypes"));
                }
            }
            return new MockLowLevelHttpRequest(url).setResponse(new MockLowLevelHttpResponse()
                .setStatusCode(exchange.status()).setContentType("application/json").setContent(exchange.body()));
        }

        private void assertConsumed() { assertTrue(responses.isEmpty(), "Expected more Gmail requests"); }
    }

    private Email email(int n) {
        return new Email(prefix + "-" + n, "Synthetic Sender <sender@example.test>", "Synthetic " + n,
            null, Instant.parse("2026-10-01T12:00:00Z").plusSeconds(n));
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getenv("EMAIL_SYNC_TEST_DB_URL"));
    }

    private void enqueue(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("INSERT INTO email_sync_jobs (id, account) VALUES (?, ?)")) {
            query.setObject(1, UUID.randomUUID());
            query.setString(2, account);
            query.executeUpdate();
        }
    }

    private String jobField(Connection connection, String column) throws SQLException {
        try (var query = connection.prepareStatement("SELECT " + column
                + " FROM email_sync_jobs WHERE account = ? ORDER BY created_at DESC LIMIT 1")) {
            query.setString(1, account);
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private void updateJob(Connection connection, String change) throws SQLException {
        try (var query = connection.prepareStatement("UPDATE email_sync_jobs SET " + change
                + " WHERE id = (SELECT id FROM email_sync_jobs WHERE account = ? ORDER BY created_at DESC LIMIT 1)")) {
            query.setString(1, account);
            assertEquals(1, query.executeUpdate());
        }
    }

    private List<String> listedIds(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT gmail_id FROM emails WHERE sync_account = ? ORDER BY gmail_id")) {
            query.setString(1, account);
            try (var result = query.executeQuery()) {
                var ids = new java.util.ArrayList<String>();
                while (result.next()) {
                    ids.add(result.getString(1));
                }
                return ids;
            }
        }
    }

    private int countEmails(Connection connection, String status) throws SQLException {
        try (var query = connection.prepareStatement(
            "SELECT count(*) FROM emails WHERE sync_account = ? AND processing_status = ?")) {
            query.setString(1, account);
            query.setString(2, status);
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }
}
