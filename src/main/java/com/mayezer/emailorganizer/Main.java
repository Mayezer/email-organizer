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
import com.mayezer.emailorganizer.service.EmailService;
import com.openai.client.OpenAIClient;

public class Main {
    public static void main(String[] args) throws IOException, GeneralSecurityException, SQLException {
        Gmail gmail = GmailClientFactory.createClient();
        OpenAIClient openAIClient = OpenAIClientFactory.createClient();
        EmailAnalysisService emailAnalysisService = new EmailAnalysisService(openAIClient);

        try (Connection connection = DatabaseConnection.getConnection()) {
            System.out.println("Cennection: True");
            EmailRepository emailRepository = new EmailRepository(connection);

            EmailService emailService = new EmailService(gmail, emailRepository); // Injeção de depêndencia por construtor.
            emailService.listEmails();


        }
    }
}
