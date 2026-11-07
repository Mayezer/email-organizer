package com.mayezer.emailorganizer.service;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.repository.EmailRepository;

final class EmailContextService {

    private static final int SAME_THREAD_LIMIT = 4;
    private static final int RELATED_THREAD_LIMIT = 2;
    private static final int RELATED_CANDIDATE_LIMIT = 50;
    private static final Pattern ADDRESS = Pattern.compile("[^\\s<>@,;]+@[^\\s<>@,;]+");
    private static final Pattern REPLY_PREFIX = Pattern.compile("^(?:(?:re|fw|fwd|res|enc):\\s*)+");
    private static final Pattern APPLICATION_REFERENCE = Pattern.compile(
        "\\b(?:vaga|job|req|requisition|application|candidatura)\\s*(?:id\\s*[:#-]?|[:#-])\\s*([a-z0-9][a-z0-9-]{2,30})\\b");

    record PreviousEmail(String relationship, Instant receivedAt, String sender, String subject, String text) {}

    private final Gmail gmail;
    private final EmailRepository repository;
    private final EmailBodyExtractor extractor;

    EmailContextService(Gmail gmail, EmailRepository repository, EmailBodyExtractor extractor) {
        this.gmail = gmail;
        this.repository = repository;
        this.extractor = extractor;
    }

    List<PreviousEmail> findHistory(Email current) throws SQLException, IOException {
        if (current.getThreadId() == null || current.getReceivedAt() == null) {
            return List.of();
        }

        List<PreviousEmail> history = new ArrayList<>();
        for (Email previous : repository.findEarlierInThread(current, SAME_THREAD_LIMIT)) {
            addIfAvailable(history, previous, "same_thread");
        }

        String address = senderAddress(current.getSender());
        if (address != null && current.getSubject() != null) {
            List<Email> related = repository.findRecentFromSender(current, address, RELATED_CANDIDATE_LIMIT)
                .stream().filter(candidate -> relatedSubjects(current.getSubject(), candidate.getSubject()))
                .limit(RELATED_THREAD_LIMIT).toList();
            for (Email candidate : related) {
                addIfAvailable(history, candidate, "possibly_related_thread");
            }
        }

        history.sort(Comparator.comparing(PreviousEmail::receivedAt));
        return history;
    }

    private void addIfAvailable(List<PreviousEmail> history, Email previous, String relationship) throws IOException {
        try {
            Message message = gmail.users().messages().get("me", previous.getId()).execute();
            String text = extractor.extract(message.getPayload()).getTextForAnalysis();
            if (!text.isBlank()) {
                history.add(new PreviousEmail(relationship, previous.getReceivedAt(),
                    previous.getSender(), previous.getSubject(), text));
            }
        } catch (GoogleJsonResponseException exception) {
            if (exception.getStatusCode() != 404 && exception.getStatusCode() != 410) {
                throw exception;
            }
        }
    }

    static String senderAddress(String sender) {
        if (sender == null) {
            return null;
        }
        String header = sender.strip();
        int open = header.lastIndexOf('<');
        String address = (open >= 0 && header.endsWith(">")
            ? header.substring(open + 1, header.length() - 1) : header).strip();
        return ADDRESS.matcher(address).matches() ? address.toLowerCase(Locale.ROOT) : null;
    }

    static boolean relatedSubjects(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        String firstSubject = normalizeSubject(first);
        String secondSubject = normalizeSubject(second);
        if (firstSubject.equals(secondSubject) && firstSubject.split(" ").length >= 4) {
            return true;
        }
        String reference = applicationReference(firstSubject);
        return reference != null && reference.equals(applicationReference(secondSubject));
    }

    private static String normalizeSubject(String subject) {
        return REPLY_PREFIX.matcher(subject.strip().toLowerCase(Locale.ROOT)).replaceFirst("")
            .strip().replaceAll("\\s+", " ");
    }

    private static String applicationReference(String subject) {
        Matcher matcher = APPLICATION_REFERENCE.matcher(subject);
        while (matcher.find()) {
            String reference = matcher.group(1);
            if (reference.chars().anyMatch(Character::isDigit)) {
                return reference;
            }
        }
        return null;
    }
}
