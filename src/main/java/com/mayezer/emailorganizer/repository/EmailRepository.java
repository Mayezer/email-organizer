package com.mayezer.emailorganizer.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.mayezer.emailorganizer.model.Email;

public class EmailRepository {

    private final Connection connection;

    public EmailRepository(
        Connection connection) {
            this.connection = connection;
    }

    public boolean existsById(String gmailId) throws SQLException { // Retorna um valor boolean, assinatura existById tem como parâmetro uma String chamada gmailId.

        String sql = "SELECT 1 FROM emails WHERE gmail_id = ? LIMIT 1"; // Pesquisa SQL - Não usamos loop, pois retorna apenas 1.
        
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gmailId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next(); // Retorna true ou false, por isso não precisamos de if else.
            }
        }
    }
    public void save(Email email) throws SQLException {
        String sql = "INSERT INTO emails (gmail_id, sender, subject) VALUES (?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email.getId());
            statement.setString(2, email.getSender());
            statement.setString(3, email.getSubject());
            statement.executeUpdate();
        }
    }

    public List<Email> findPending() throws  SQLException{
        String sql = "SELECT gmail_id, sender, subject FROM emails WHERE processing_status = 'PENDING'";
        List<Email> pendingEmails = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString("gmail_id");
                    String sender = result.getString("sender");
                    String subject = result.getString("subject");
                    Email email = new Email(id, sender, subject);
                    pendingEmails.add(email);
                }
            }
        }
        return pendingEmails;
    }
}
