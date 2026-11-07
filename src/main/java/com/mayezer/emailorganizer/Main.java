package com.mayezer.emailorganizer;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import com.google.api.services.gmail.Gmail;
import com.mayezer.emailorganizer.config.DatabaseConnection;
import com.mayezer.emailorganizer.config.GmailClientFactory;
import com.mayezer.emailorganizer.config.OpenAIClientFactory;
import com.mayezer.emailorganizer.repository.EmailRepository;
import com.mayezer.emailorganizer.service.EmailAnalysisService;
import com.mayezer.emailorganizer.service.EmailProcessingService;
import com.mayezer.emailorganizer.service.EmailReportService;
import com.mayezer.emailorganizer.service.EmailService;
import com.mayezer.emailorganizer.service.EmailSyncWorker;

public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, SQLException {
        if (args.length == 1 && args[0].equals("--authorize-gmail")) {
            GmailClientFactory.createClient();
            System.out.println("Gmail authorization is ready.");
            return;
        }
        if (args.length == 1 && args[0].equals("--sync-worker")) {
            String account = System.getenv().getOrDefault("EMAIL_SYNC_ACCOUNT", "").strip().toLowerCase(Locale.ROOT);
            if (account.isEmpty()) {
                System.err.println("Set EMAIL_SYNC_ACCOUNT before starting the sync worker.");
                return;
            }
            runSyncWorker(new EmailSyncWorker(account));
            return;
        }
        try (Connection connection = DatabaseConnection.getConnection()) {
            EmailRepository repository = new EmailRepository(connection);
            if (args.length == 0) {
                Gmail gmail = GmailClientFactory.createClient();
                new EmailService(gmail, repository).listEmails();
                processingService(gmail, repository).processPendingEmails();
                return;
            }

            switch (args[0]) {
                case "--report" -> {
                    if (args.length == 1) {
                        new EmailReportService(repository).printReport();
                    } else {
                        System.err.println("Use --report without additional arguments.");
                    }
                }
                case "--retry" -> {
                    if (args.length == 2 && repository.markForRetry(args[1])) {
                        System.out.println("The email has been marked for retry.");
                    } else {
                        System.err.println("Use --retry with the ID of an email in FAILED status.");
                    }
                }
                case "--backfill-metadata", "--backfill-indicators", "--reanalyze" -> runBatch(args, repository);
                default -> System.err.println("Use --report, --retry, --backfill-metadata, --backfill-indicators, --reanalyze, --authorize-gmail or --sync-worker.");
            }
        }
    }

    private static void runSyncWorker(EmailSyncWorker worker) {
        while (!Thread.currentThread().isInterrupted()) {
            try (Connection connection = DatabaseConnection.getConnection(); Statement setup = connection.createStatement()) {
                setup.execute("SET statement_timeout = '8s'");
                setup.execute("SET lock_timeout = '2s'");
                setup.execute("SET idle_in_transaction_session_timeout = '15s'");
                if (worker.runStep(connection)) {
                    continue;
                }
            } catch (SQLException exception) {
                System.err.println("Sync database step failed (" + exception.getSQLState() + ")");
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void runBatch(String[] args, EmailRepository repository)
        throws IOException, GeneralSecurityException, SQLException {
        int limit = args[0].equals("--reanalyze") ? 20 : 50;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                limit = 0;
            }
        }
        if (args.length > 3 || limit < 1 || limit > 200) {
            System.err.println("Use " + args[0] + " [limit 1-200] [after-gmail-id].");
            return;
        }
        String afterId = args.length == 3 ? args[2] : "";
        switch (args[0]) {
            case "--backfill-indicators" ->
                new EmailService(GmailClientFactory.createBackgroundClient(), repository)
                    .backfillIndicators(limit, afterId);
            case "--backfill-metadata" ->
                new EmailService(GmailClientFactory.createClient(), repository).backfillMetadata(limit, afterId);
            case "--reanalyze" ->
                processingService(GmailClientFactory.createClient(), repository).reanalyze(limit, afterId);
            default -> throw new IllegalArgumentException("Unsupported batch command");
        }
    }

    private static EmailProcessingService processingService(Gmail gmail, EmailRepository repository) {
        var service = new EmailAnalysisService(OpenAIClientFactory.createClient());
        return new EmailProcessingService(gmail, repository, () -> service);
    }
}
