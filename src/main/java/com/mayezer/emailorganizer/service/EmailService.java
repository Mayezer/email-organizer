package com.mayezer.emailorganizer.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.mayezer.emailorganizer.model.Email;
import com.mayezer.emailorganizer.model.EmailContent;
import com.mayezer.emailorganizer.repository.EmailRepository;

public class EmailService {

    private final Gmail gmail; // Atributo
    private final EmailRepository emailRepository;

    public EmailService(Gmail gmail, EmailRepository emailRepository) { // Construtor
        this.gmail = gmail;
        this.emailRepository = emailRepository;

    }

    public void listEmails() throws  IOException, SQLException { // Método
        ListMessagesResponse reponse = this.gmail.users().messages().list("me").execute(); // Armazena a resposta da API.
        List<Message> messages = reponse.getMessages(); // Extrai a lista de mensagens e armazena em messages.
        ArrayList<Email> emails = new ArrayList<>(); // Transforma nossos emails em uma lista.
        EmailBodyExtractor extractor = new EmailBodyExtractor();
        if (messages != null && !messages.isEmpty()) { // Verifica se é nulo e se (!não) está vazia.
            for (Message message : messages) { // Percorre todos os e-mails e nos tras ID e threadId.
                String id = message.getId(); // Salva o ID na variável id.
                if (emailRepository.existsById(id)) {
                    continue;
                }
                Message idMessage = this.gmail.users().messages().get("me", id).execute();
                MessagePart payload = idMessage.getPayload();
                EmailContent emailContent = extractor.extract(payload);
                List<MessagePartHeader> headers = payload.getHeaders();

                String sender = null;
                String subject = null;

                for (MessagePartHeader header : headers) {
                    String name = header.getName();
                    if (name.equals("From")) {
                        sender = header.getValue();
                    }
                    if (name.equals("Subject")) {
                        subject = header.getValue();
                    }
                }

            Email email = new Email( // Fazemos a conexão com nosso Email.java
                id,
                sender,
                subject);
            /* System.out.println(email.getId());
            System.out.println(email.getSender());
            System.out.println(email.getSubject());
            System.out.println(emailContent.getPartBodyPlain()); */
            emails.add(email);
            emailRepository.save(email);
            }

        } else {
            System.out.println("There are no emails to read!");
        }
        System.out.println(emails.size()); // Mostra a quantidade de e-mails.
    }
}
