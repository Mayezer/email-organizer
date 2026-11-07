package com.mayezer.emailorganizer.service;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.repository.EmailRepository;

public class EmailService {

    private final Gmail gmail;
    private final EmailRepository emailRepository;

    public EmailService(Gmail gmail, EmailRepository emailRepository) {
        this.gmail = gmail;
        this.emailRepository = emailRepository;
    }

    public void listEmails() throws IOException, SQLException {
        String pageToken = null;
        int imported = 0;
        do {
            ListMessagesResponse page = gmail.users().messages().list("me").setPageToken(pageToken).execute();
            if (page.getMessages() != null) {
                for (Message message : page.getMessages()) {
                    String id = message.getId();
                    if (emailRepository.existsById(id)) {
                        continue;
                    }
                    Message details = gmail.users().messages().get("me", id)
                        .setFields("id,threadId,internalDate,payload(headers)").execute();
                    if (emailRepository.save(fromGmail(details))) {
                        imported++;
                    }
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        System.out.println("Imported emails: " + imported);
    }

    public void backfillMetadata(int limit, String afterId) throws IOException, SQLException {
        List<Email> batch = emailRepository.findMissingMetadata(afterId, limit);
        int updated = 0;
        int unavailable = 0;
        for (Email email : batch) {
            try {
                Message message = gmail.users().messages().get("me", email.getId())
                    .setFields("id,threadId,internalDate").execute();
                Email metadata = fromGmail(message);
                if (metadata.getThreadId() == null || metadata.getReceivedAt() == null) {
                    unavailable++;
                } else if (emailRepository.fillMissingMetadata(metadata)) {
                    updated++;
                }
            } catch (GoogleJsonResponseException exception) {
                if (exception.getStatusCode() != 404 && exception.getStatusCode() != 410) {
                    throw exception;
                }
                unavailable++;
            }
        }

        System.out.printf("Metadata batch: %d selected, %d updated, %d unavailable.%n",
            batch.size(), updated, unavailable);
        if (batch.size() == limit) {
            System.out.println("Continue with --backfill-metadata " + limit + " " + batch.get(batch.size() - 1).getId());
        }
    }

    public void backfillIndicators(int limit, String afterId) throws IOException, SQLException {
        List<Email> batch = emailRepository.findMissingIndicators(afterId, limit);
        var extractor = new EmailBodyExtractor();
        int updated = 0;
        int unavailable = 0;
        for (Email email : batch) {
            try {
                Message message = gmail.users().messages().get("me", email.getId())
                    .setFormat("full").setFields("payload").execute();
                var indicators = extractor.extract(message.getPayload()).getIndicators();
                if (emailRepository.fillMissingIndicators(email.getId(), indicators)) {
                    updated++;
                }
            } catch (GoogleJsonResponseException exception) {
                if (exception.getStatusCode() != 404 && exception.getStatusCode() != 410) {
                    throw exception;
                }
                unavailable++;
            }
        }
        System.out.printf("Indicators batch: %d selected, %d updated, %d unavailable.%n",
            batch.size(), updated, unavailable);
        if (batch.size() == limit) {
            System.out.println("Continue with --backfill-indicators " + limit + " " + batch.get(batch.size() - 1).getId());
        }
    }

    static Email fromGmail(Message message) {
        MessagePart payload = message.getPayload();
        List<MessagePartHeader> headers = payload == null ? null : payload.getHeaders();
        Long internalDate = message.getInternalDate();
        return new Email(message.getId(), header(headers, "From"), header(headers, "Subject"),
            message.getThreadId(), internalDate == null ? null : Instant.ofEpochMilli(internalDate));
    }

    private static String header(List<MessagePartHeader> headers, String name) {
        if (headers != null) {
            for (MessagePartHeader header : headers) {
                if (header != null && name.equalsIgnoreCase(header.getName())) {
                    return header.getValue();
                }
            }
        }
        return null;
    }
}
