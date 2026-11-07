package com.mayezer.emailorganizer.service;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailContent;
import com.mayezer.emailorganizer.model.EmailProcessingResult;
import com.mayezer.emailorganizer.repository.EmailRepository;

public class EmailProcessingService {

    private final Gmail gmail;
    private final EmailRepository emailRepository;
    private final Supplier<EmailAnalysisService> analysisService;
    private final EmailBodyExtractor extractor = new EmailBodyExtractor();
    private final EmailContextService contextService;

    public EmailProcessingService(Gmail gmail, EmailRepository emailRepository,
        Supplier<EmailAnalysisService> analysisService) {
        this.gmail = gmail;
        this.emailRepository = emailRepository;
        this.analysisService = analysisService;
        this.contextService = new EmailContextService(gmail, emailRepository, extractor);
    }

    public void processPendingEmails() throws SQLException {
        List<Email> batch;
        do {
            batch = emailRepository.findLegacyPending(50);
            for (Email email : batch) {
                process(email, false);
            }
        } while (batch.size() == 50);
    }

    public void reanalyze(int limit, String afterId) throws SQLException {
        List<Email> batch = emailRepository.findLegacyAnalyses(afterId, limit);
        List<Email> chronological = batch.stream()
            .sorted(Comparator.comparing(Email::getReceivedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(Email::getId))
            .toList();
        int updated = 0;
        for (Email email : chronological) {
            if (process(email, true)) {
                updated++;
            }
        }
        System.out.printf("Reanalysis batch: %d selected, %d updated.%n", batch.size(), updated);
        if (batch.size() == limit) {
            System.out.println("Continue with --reanalyze " + limit + " " + batch.get(batch.size() - 1).getId());
        }
    }

    private boolean process(Email email, boolean reanalysis) throws SQLException {
        try {
            Message message = gmail.users().messages().get("me", email.getId()).execute();
            Email current = EmailService.fromGmail(message);
            if (email.getThreadId() == null || email.getReceivedAt() == null) {
                emailRepository.fillMissingMetadata(current);
            }
            EmailProcessingResult result = analyze(message, current);
            emailRepository.fillMissingIndicators(email.getId(), result.indicators());
            if (result.analysis() != null) {
                return emailRepository.saveAnalysis(email.getId(), result.analysis(), reanalysis);
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("Email analysis failed (" + exception.getClass().getSimpleName() + ")");
        }
        if (!reanalysis) {
            emailRepository.markFailed(email.getId());
        }
        return false;
    }

    EmailProcessingResult analyze(Email email) throws IOException, SQLException {
        Message message = gmail.users().messages().get("me", email.getId()).execute();
        return analyze(message, EmailService.fromGmail(message));
    }

    private EmailProcessingResult analyze(Message message, Email current) throws SQLException {
        EmailContent content = extractor.extract(message.getPayload());
        try {
            String text = content.getTextForAnalysis();
            if (text.isBlank()) {
                throw new IllegalStateException("Email has no text content");
            }
            return new EmailProcessingResult(content.getIndicators(),
                analysisService.get().analyze(current, text, contextService.findHistory(current)));
        } catch (IOException | RuntimeException exception) {
            System.err.println("Email analysis failed (" + exception.getClass().getSimpleName() + ")");
            return new EmailProcessingResult(content.getIndicators(), null);
        }
    }
}
