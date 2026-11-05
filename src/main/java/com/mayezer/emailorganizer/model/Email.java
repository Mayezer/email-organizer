package com.mayezer.emailorganizer.model;

public class Email {

    private final String id; // Encapsulamento
    private final String sender;
    private final String subject;

    public Email(
        String id,
        String sender,
        String subject) { // Construtor
            this.id = id;
            this.sender = sender;
            this.subject = subject;
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
}
