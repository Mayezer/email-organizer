package com.mayezer.emailorganizer.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailAnalysis;
import com.mayezer.emailorganizer.model.EmailContent;
import com.mayezer.emailorganizer.repository.EmailRepository;

public class EmailProcessingService {

    private final Gmail gmail;
    private final EmailRepository emailRepository;
    private final EmailAnalysisService emailAnalysisService;

    public EmailProcessingService (
        Gmail gmail,
        EmailRepository emailRepository,
        EmailAnalysisService emailAnalysisService) {
            this.gmail = gmail;
            this.emailRepository = emailRepository;
            this.emailAnalysisService = emailAnalysisService;
    }

    public void processPendingEmails() throws SQLException {
        List<Email> pendingEmails = emailRepository.findPending();
        EmailBodyExtractor extractor = new EmailBodyExtractor();

        for (Email pendingEmail : pendingEmails) {
            String id = pendingEmail.getId();
            try {
                Message message = this.gmail.users().messages().get("me", id).execute();
                MessagePart payload = message.getPayload();
                EmailContent emailContent = extractor.extract(payload);
                String textForAnalysis = emailContent.getTextForAnalysis();
                if (textForAnalysis == null || textForAnalysis.isBlank()) {
                    System.err.println("Email with no text content!" + id);
                    emailRepository.markFailed(id);
                    continue;
                }
                EmailAnalysis emailAnalysis = emailAnalysisService.analyze(pendingEmail, textForAnalysis);
                emailRepository.saveAnalysis(id, emailAnalysis);
            }

            catch (IOException | RuntimeException exception) {
                System.err.println("Failed to process email: " + id + " | Error: " + exception.getMessage());
                emailRepository.markFailed(id);
            }
        }
    }
}
