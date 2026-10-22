package com.mayezer.emailorganizer.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailContent;
import com.mayezer.emailorganizer.repository.EmailRepository;

public class EmailProcessingService {

    private final Gmail gmail;
    private final EmailRepository emailRepository;

    public EmailProcessingService (
        Gmail gmail, 
        EmailRepository emailRepository) {
            this.gmail = gmail;
            this.emailRepository = emailRepository;
    }

    public void processPendingEmails() throws SQLException, IOException{
        List<Email> pendingEmails = emailRepository.findPending();
        EmailBodyExtractor extractor = new EmailBodyExtractor();
        
        for (Email pendingEmail : pendingEmails) {
            String id = pendingEmail.getId();
            Message message = this.gmail.users().messages().get("me", id).execute();
            MessagePart payload = message.getPayload();
            EmailContent emailContent = extractor.extract(payload);
            String body = emailContent.getPartBodyPlain();
            String bodyHtml = emailContent.getPartBodyHtml();
            String bodyAmpHtml = emailContent.getPartBodyAmpHtml();

            String textForAnalysis = null;

            if (body != null && !body.isBlank()) {
                textForAnalysis = body;
            } else if (bodyHtml != null && !bodyHtml.isBlank()) {
                textForAnalysis = bodyHtml;
            } else if (bodyAmpHtml != null && !bodyAmpHtml.isBlank()) {
                textForAnalysis = bodyAmpHtml;
            } else {
                System.err.println("Email with no text content!" + id);
                continue;
            }

        }
    }
}
