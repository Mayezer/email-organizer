package com.mayezer.emailorganizer.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;

public class GmailClientFactory {
    private static final String APPLICATION_NAME = "Gmail API Java Email Organizer";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String CREDENTIALS_FILE_PATH =
        System.getenv().getOrDefault("GOOGLE_CREDENTIALS_PATH", "credentials.json");
    private static final Path TOKENS_DIRECTORY = Path.of("tokens");
    private static final String STORED_USER = "user";
    private static final List<String> SCOPES = List.of(GmailScopes.GMAIL_READONLY);

    private static GoogleAuthorizationCodeFlow authorizationFlow(NetHttpTransport transport, boolean background)
        throws IOException {
        GoogleClientSecrets secrets;
        try (Reader reader = Files.newBufferedReader(Path.of(CREDENTIALS_FILE_PATH), StandardCharsets.UTF_8)) {
            secrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
        }
        GoogleAuthorizationCodeFlow.Builder flow = new GoogleAuthorizationCodeFlow.Builder(
            transport, JSON_FACTORY, secrets, SCOPES)
            .setDataStoreFactory(new FileDataStoreFactory(TOKENS_DIRECTORY.toFile()))
            .setAccessType("offline");
        if (background) {
            flow.setRequestInitializer(GmailClientFactory::setTimeouts);
        }
        return flow.build();
    }

    public static Gmail createClient() throws IOException, GeneralSecurityException {
        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleAuthorizationCodeFlow flow = authorizationFlow(transport, false);
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize(STORED_USER);
        return new Gmail.Builder(transport, JSON_FACTORY, credential).setApplicationName(APPLICATION_NAME).build();
    }

    public static Gmail createBackgroundClient() throws IOException, GeneralSecurityException {
        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = authorizationFlow(transport, true).loadCredential(STORED_USER);
        if (credential == null || credential.getRefreshToken() == null) {
            throw new IOException("Gmail must be authorized before starting the sync worker.");
        }
        return new Gmail.Builder(transport, JSON_FACTORY, request -> {
            credential.initialize(request);
            setTimeouts(request);
        }).setApplicationName(APPLICATION_NAME).build();
    }

    private static void setTimeouts(HttpRequest request) {
        request.setConnectTimeout(4_000);
        request.setReadTimeout(8_000);
        request.setNumberOfRetries(1);
        request.setLoggingEnabled(false);
        request.setCurlLoggingEnabled(false);
    }
}
