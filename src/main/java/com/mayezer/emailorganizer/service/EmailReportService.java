package com.mayezer.emailorganizer.service;

import java.sql.SQLException;
import java.util.List;

import com.mayezer.emailorganizer.model.AnalyzedEmail;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailAnalysis;
import com.mayezer.emailorganizer.repository.EmailRepository;

public class EmailReportService {

    private final EmailRepository emailRepository;

    public EmailReportService (
        EmailRepository emailRepository) {
            this.emailRepository = emailRepository;
    }

    public void printReport() throws SQLException {
        List<AnalyzedEmail> analyzedEmails = emailRepository.findCompleted();

        if (analyzedEmails.isEmpty()) {
            System.out.println("There are no emails analyzed in the last 07 days.");
            return;
        }

        System.out.println("--- Weekly Analyzed Emails Report (Last 07 Days) ---");
        System.out.println("Total: " + analyzedEmails.size() + "\n\n");

        for (AnalyzedEmail analyzedEmail : analyzedEmails) {
            Email email = analyzedEmail.getEmail();
            EmailAnalysis emailAnalysis = analyzedEmail.getAnalysis();

            System.out.println(
                "ID: " + email.getId() + "\n" +
                "Sender: " + email.getSender() + "\n" +
                "Subject: " + email.getSubject() + "\n" +
                "Category: " + emailAnalysis.getCategory() + "\n" +
                "Summary: " + emailAnalysis.getSummary());

            if (emailAnalysis.getRequiresAction()) {
                System.out.println("Requires action: Yes");
            } else {
                System.out.println("Requires action: No");
            }
            System.out.println();
        }
    }
}
