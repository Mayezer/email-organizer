package com.mayezer.emailorganizer;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.SQLException;

import com.google.api.services.gmail.Gmail;
import com.mayezer.emailorganizer.config.DatabaseConnection;
import com.mayezer.emailorganizer.config.GmailClientFactory;
import com.mayezer.emailorganizer.config.OpenAIClientFactory;
import com.mayezer.emailorganizer.repository.EmailRepository;
import com.mayezer.emailorganizer.service.EmailAnalysisService;
import com.mayezer.emailorganizer.service.EmailProcessingService;
import com.mayezer.emailorganizer.service.EmailReportService;
import com.mayezer.emailorganizer.service.EmailService;
import com.openai.client.OpenAIClient;

public class Main {
    public static void main(String[] args) throws IOException, GeneralSecurityException, SQLException {

        try (Connection connection = DatabaseConnection.getConnection()) {
            System.out.println("Cennection: True" + "\n\n");

            EmailRepository emailRepository = new EmailRepository(connection);
            if (args.length > 0) {
                if (args[0].equals("--report")) {
                    if (args.length != 1) {
                    System.out.println("Use --report without additional arguments.");
                    return;
                }
                    EmailReportService emailReportService = new EmailReportService(emailRepository);
                    emailReportService.printReport();
                    return;
                }
                if (args.length != 2) {
                    System.err.println("Use --retry followed by the email ID.");
                    return;
                }
                if (!args[0].equals("--retry")) {
                    System.err.println("Use --retry followed by the email ID.");
                    return;
                }
                String gmailId = args[1];
                boolean markedForRetry = emailRepository.markForRetry(gmailId);
                if (markedForRetry) {
                    System.out.println("The email has been marked for a retry: " + gmailId);
                } else {
                    System.err.println("The ID was not found, or the email does not have a FAILED status: " + gmailId);
                }
                return;
            }
            Gmail gmail = GmailClientFactory.createClient();

            OpenAIClient openAIClient = OpenAIClientFactory.createClient();
            EmailAnalysisService emailAnalysisService = new EmailAnalysisService(openAIClient);

            EmailService emailService = new EmailService(gmail, emailRepository); // Injeção de depêndencia por construtor.
            emailService.listEmails();

            EmailProcessingService emailProcessingService = new EmailProcessingService(gmail, emailRepository, emailAnalysisService);
            emailProcessingService.processPendingEmails();
        }
    }
}
