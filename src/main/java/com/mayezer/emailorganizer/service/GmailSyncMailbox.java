package com.mayezer.emailorganizer.service;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.mayezer.emailorganizer.config.GmailClientFactory;
import com.mayezer.emailorganizer.config.OpenAIClientFactory;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailProcessingResult;
import com.mayezer.emailorganizer.repository.EmailRepository;

final class GmailSyncMailbox implements EmailSyncWorker.Mailbox {
    private final String account;
    private Gmail gmail;
    private EmailAnalysisService analysisService;

    GmailSyncMailbox(String account) {
        this.account = account;
    }

    GmailSyncMailbox(String account, Gmail authenticatedClient) {
        this(account);
        this.gmail = authenticatedClient;
    }

    @Override
    public void prepare() throws IOException {
        if (gmail != null) {
            return;
        }
        try {
            Gmail client = GmailClientFactory.createBackgroundClient();
            String authenticated = client.users().getProfile("me").setFields("emailAddress").execute().getEmailAddress();
            if (!account.equalsIgnoreCase(authenticated)) {
                throw new IOException("The authorized Gmail account does not match the configured account.");
            }
            gmail = client;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not initialize the Gmail client", exception);
        }
    }

    @Override
    public String currentHistoryId() throws IOException {
        BigInteger id = gmail.users().getProfile("me").setFields("historyId").execute().getHistoryId();
        return requireHistoryId(id);
    }

    @Override
    public EmailSyncWorker.Page fetchPage(String pageToken, EmailRepository repository) throws IOException, SQLException {
        ListMessagesResponse page = gmail.users().messages().list("me").setPageToken(pageToken)
            .setMaxResults((long) EmailSyncWorker.PAGE_SIZE).setFields("messages(id),nextPageToken").execute();
        checkNextToken(pageToken, page.getNextPageToken());
        List<String> ids = orEmpty(page.getMessages()).stream().map(Message::getId).toList();
        return new EmailSyncWorker.Page(fetchMetadata(ids, repository), page.getNextPageToken());
    }

    @Override
    public EmailSyncWorker.HistoryBatch fetchHistory(String historyId, String pageToken, int offset,
                                                     EmailRepository repository) throws IOException, SQLException {
        ListHistoryResponse page;
        try {
            page = gmail.users().history().list("me").setStartHistoryId(new BigInteger(historyId))
                .setHistoryTypes(List.of("messageAdded")).setPageToken(pageToken)
                .setMaxResults((long) EmailSyncWorker.PAGE_SIZE)
                .setFields("history(messagesAdded(message(id)),id),nextPageToken,historyId").execute();
        } catch (GoogleJsonResponseException exception) {
            if (exception.getStatusCode() == 404) {
                throw new EmailSyncWorker.HistoryExpired(exception);
            }
            throw exception;
        }
        checkNextToken(pageToken, page.getNextPageToken());

        // Gmail limits history records, which can each contain multiple message IDs.
        // Keep a position within the page so even a large record is imported in groups of five.
        List<String> ids = new ArrayList<>(EmailSyncWorker.PAGE_SIZE);
        int position = 0;
        for (History record : orEmpty(page.getHistory())) {
            for (HistoryMessageAdded added : orEmpty(record.getMessagesAdded())) {
                if (position < offset) {
                    position++;
                    continue;
                }
                if (ids.size() == EmailSyncWorker.PAGE_SIZE) {
                    return new EmailSyncWorker.HistoryBatch(fetchMetadata(ids, repository), pageToken, position, null);
                }
                ids.add(added.getMessage().getId());
                position++;
            }
        }
        if (position < offset) {
            throw new IOException("Gmail history page no longer contains the saved position.");
        }
        String checkpoint = page.getNextPageToken() == null ? requireHistoryId(page.getHistoryId()) : null;
        return new EmailSyncWorker.HistoryBatch(fetchMetadata(ids, repository), page.getNextPageToken(), 0, checkpoint);
    }

    private List<Email> fetchMetadata(List<String> ids, EmailRepository repository) throws IOException, SQLException {
        List<Email> emails = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            if (repository.existsById(id)) {
                continue;
            }
            try {
                Message message = gmail.users().messages().get("me", id)
                    .setFormat("metadata").setMetadataHeaders(List.of("From", "Subject"))
                    .setFields("id,threadId,internalDate,labelIds,payload(headers)").execute();
                List<String> labels = orEmpty(message.getLabelIds());
                if (!labels.contains("SPAM") && !labels.contains("TRASH")) {
                    emails.add(EmailService.fromGmail(message));
                }
            } catch (GoogleJsonResponseException exception) {
                if (exception.getStatusCode() != 404 && exception.getStatusCode() != 410) {
                    throw exception;
                }
            }
        }
        return emails;
    }

    @Override
    public boolean isTrashedOrMissing(String gmailId) throws IOException {
        try {
            var message = gmail.users().messages().get("me", gmailId)
                .setFormat("metadata").setFields("labelIds").execute();
            return orEmpty(message.getLabelIds()).contains("TRASH");
        } catch (GoogleJsonResponseException exception) {
            if (exception.getStatusCode() == 404 || exception.getStatusCode() == 410) {
                return true;
            }
            throw exception;
        }
    }

    private static void checkNextToken(String current, String next) throws IOException {
        if (next != null && Objects.equals(next, current)) {
            throw new IOException("Gmail did not advance the page token.");
        }
    }

    private static String requireHistoryId(BigInteger id) throws IOException {
        if (id == null || id.signum() <= 0) {
            throw new IOException("Gmail did not return a history ID.");
        }
        return id.toString();
    }

    private static <T> List<T> orEmpty(List<T> items) {
        return items == null ? List.of() : items;
    }

    @Override
    public EmailProcessingResult analyze(Email email, EmailRepository repository) throws IOException, SQLException {
        return new EmailProcessingService(gmail, repository, this::analysisService).analyze(email);
    }

    private EmailAnalysisService analysisService() {
        if (analysisService == null) {
            analysisService = new EmailAnalysisService(OpenAIClientFactory.createBackgroundClient());
        }
        return analysisService;
    }
}
