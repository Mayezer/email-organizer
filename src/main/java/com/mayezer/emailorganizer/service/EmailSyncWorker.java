package com.mayezer.emailorganizer.service;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailProcessingResult;
import com.mayezer.emailorganizer.repository.EmailRepository;
import com.mayezer.emailorganizer.repository.EmailSyncRepository;
import com.mayezer.emailorganizer.repository.EmailSyncRepository.Lease;
import com.mayezer.emailorganizer.repository.EmailSyncRepository.Phase;

public final class EmailSyncWorker {
    static final int PAGE_SIZE = 5;

    record Page(List<Email> emails, String nextToken) {}
    record HistoryBatch(List<Email> emails, String nextToken, int nextOffset, String checkpoint) {}

    static final class HistoryExpired extends IOException {
        HistoryExpired(IOException cause) {
            super("Gmail history is no longer available", cause);
        }
    }

    interface Mailbox {
        void prepare() throws IOException;
        String currentHistoryId() throws IOException;
        Page fetchPage(String pageToken, EmailRepository repository) throws IOException, SQLException;
        HistoryBatch fetchHistory(String historyId, String pageToken, int offset, EmailRepository repository)
            throws IOException, SQLException;
        boolean isTrashedOrMissing(String gmailId) throws IOException;
        EmailProcessingResult analyze(Email email, EmailRepository repository) throws IOException, SQLException;
    }

    private final String account;
    private final Mailbox mailbox;

    public EmailSyncWorker(String account) {
        this(account, new GmailSyncMailbox(account));
    }

    EmailSyncWorker(String account, Mailbox mailbox) {
        this.account = account;
        this.mailbox = mailbox;
    }

    public boolean runStep(Connection connection) throws SQLException {
        EmailSyncRepository jobs = new EmailSyncRepository(connection);
        Lease lease = jobs.claim(account);
        if (lease == null) {
            return false;
        }

        EmailRepository emails = new EmailRepository(connection);
        try {
            mailbox.prepare();
            if (lease.phase() == Phase.IMPORTING) {
                importStep(jobs, lease, emails);
                return true;
            }
        } catch (IOException exception) {
            System.err.println("Sync mailbox step failed (" + exception.getClass().getSimpleName() + ")");
            jobs.retry(lease);
            return true;
        }

        Email pending = emails.findSyncPending(account);
        if (pending == null) {
            jobs.complete(lease);
        } else {
            EmailProcessingResult result = null;
            try {
                result = mailbox.analyze(pending, emails);
            } catch (IOException | RuntimeException exception) {
                System.err.println("Sync analysis failed (" + exception.getClass().getSimpleName() + ")");
            }
            jobs.saveAnalysis(lease, pending, result);
        }
        return true;
    }

    private void importStep(EmailSyncRepository jobs, Lease lease, EmailRepository emails)
            throws IOException, SQLException {
        // Read after claiming the lease so the checkpoint includes the preceding batch's commit.
        String historyId = jobs.historyId(account);
        var progress = lease.progress();
        if (progress.pageToken() == null && progress.historyOffset() == 0 && progress.fullHistoryId() == null) {
            for (var pending : emails.findPendingTrash(account, PAGE_SIZE)) {
                emails.reconcileTrash(account, pending, mailbox.isTrashedOrMissing(pending.gmailId()));
            }
        }
        if (historyId != null) {
            HistoryBatch batch;
            try {
                batch = mailbox.fetchHistory(historyId, progress.pageToken(), progress.historyOffset(), emails);
            } catch (HistoryExpired expired) {
                jobs.recoverExpiredHistory(lease, account);
                return;
            }
            jobs.saveHistoryBatch(lease, account, batch.emails(), batch.nextToken(), batch.nextOffset(),
                batch.checkpoint());
        } else if (progress.fullHistoryId() == null) {
            jobs.startFullScan(lease, mailbox.currentHistoryId());
        } else {
            Page page = mailbox.fetchPage(progress.pageToken(), emails);
            jobs.saveFullPage(lease, account, page.emails(), page.nextToken());
        }
    }
}
