package com.mayezer.emailorganizer.model;

import java.time.Instant;

public class Email {

    private final String id; // Encapsulamento
    private final String sender;
    private final String subject;
    private final String threadId;
    private final Instant receivedAt;

    public Email(
        String id,
        String sender,
        String subject,
        String threadId,
        Instant receivedAt) {
            this.id = id;
            this.sender = sender;
            this.subject = subject;
            this.threadId = threadId;
            this.receivedAt = receivedAt;
    }

    public String getId() { // Metodos com retorno
        return id;
    }
    public String getSender() {
        return sender;
    }
    public String getSubject() {
        return subject;
    }
    public String getThreadId() {
        return threadId;
    }
    public Instant getReceivedAt() {
        return receivedAt;
    }
}
