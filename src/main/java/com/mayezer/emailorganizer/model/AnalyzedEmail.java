package com.mayezer.emailorganizer.model;

public class AnalyzedEmail {

    private final Email email;
    private final EmailAnalysis analysis;

    public AnalyzedEmail(
        Email email,
        EmailAnalysis analysis) {
            this.email = email;
            this.analysis = analysis;
    }

    public Email getEmail() {
        return email;
    }
    public EmailAnalysis getAnalysis() {
        return analysis;
    }
}
