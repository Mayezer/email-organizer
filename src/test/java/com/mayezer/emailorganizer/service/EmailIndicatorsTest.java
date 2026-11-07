package com.mayezer.emailorganizer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.mayezer.emailorganizer.model.EmailIndicators;

class EmailIndicatorsTest {
    private final EmailBodyExtractor extractor = new EmailBodyExtractor();

    @Test
    void findsAnEmbeddedAttachmentAndAnHtmlLinkInNestedMime() {
        var html = text("text/html", "<a href='https://example.test/vaga'>Ver vaga</a><img src='cid:logo'>");
        var logo = new MessagePart().setMimeType("image/png")
            .setHeaders(List.of(header("Content-Disposition", "inline"), header("Content-ID", "<logo>")))
            .setBody(new MessagePartBody().setAttachmentId("synthetic-logo"));
        var related = new MessagePart().setMimeType("multipart/related").setParts(List.of(html, logo));
        var message = new MessagePart().setMimeType("multipart/mixed").setParts(List.of(related));
        var content = extractor.extract(message);
        assertEquals(new EmailIndicators(true, true), content.getIndicators());
        assertTrue(content.getTextForAnalysis().contains("Ver vaga: https://example.test/vaga"));
    }

    @Test
    void detectsAttachmentsWithoutReadingLinksInsideThem() {
        var attachment = text("text/plain", "https://example.test/dentro-do-anexo").setFilename("nota.txt");
        var message = new MessagePart().setMimeType("multipart/mixed")
            .setParts(List.of(text("text/plain", "Mensagem simples."), attachment));
        var content = extractor.extract(message);
        assertEquals(new EmailIndicators(true, false), content.getIndicators());
        assertFalse(content.getTextForAnalysis().contains("dentro-do-anexo"));
    }

    @Test
    void findsLinksInPlainTextAndAmpWithoutAttachments() {
        assertEquals(new EmailIndicators(false, true),
            extractor.extract(text("text/plain", "Veja (HTTP://example.test/agenda).")).getIndicators());
        assertEquals(new EmailIndicators(false, true),
            extractor.extract(text("text/x-amp-html", "<a href='https://example.test/agenda'>Agenda</a>")).getIndicators());
    }

    @Test
    void embeddedResourcesAndUnsupportedSchemesDoNotCountAsWebLinks() {
        var html = text("text/html", """
            <img src='https://example.test/pixel'>
            <a href='#rodape'>Rodapé</a><a href='mailto:demo@example.test'>Contato</a>
            """);
        assertEquals(new EmailIndicators(false, false), extractor.extract(html).getIndicators());
        assertEquals(new EmailIndicators(false, false),
            extractor.extract(text("text/plain", "Mensagem simples.")).getIndicators());
    }

    @Test
    void preservesUnknownWhenThePayloadOrTextIsUnavailable() {
        var external = new MessagePart().setMimeType("text/html")
            .setBody(new MessagePartBody().setSize(128).setAttachmentId("synthetic-external-text"));
        assertEquals(new EmailIndicators(false, null), extractor.extract(external).getIndicators());
        assertEquals(new EmailIndicators(null, null), extractor.extract(null).getIndicators());
    }

    private static MessagePart text(String type, String value) {
        String encoded = Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return new MessagePart().setMimeType(type).setBody(new MessagePartBody().setData(encoded));
    }

    private static MessagePartHeader header(String name, String value) {
        return new MessagePartHeader().setName(name).setValue(value);
    }
}
