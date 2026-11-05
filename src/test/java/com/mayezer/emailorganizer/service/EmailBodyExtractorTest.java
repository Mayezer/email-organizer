package com.mayezer.emailorganizer.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.mayezer.emailorganizer.model.EmailContent;

public class EmailBodyExtractorTest {

    // 1° test
    @Test
    public void shouldExtractPlainText() {

        String originalText = "Your interview will be tomorrow at 10 a.m.";
        byte[] originalBytes = originalText.getBytes(StandardCharsets.UTF_8);
        String encodedText = Base64.getUrlEncoder().encodeToString(originalBytes);

        MessagePartBody body = new MessagePartBody();
        body.setData(encodedText);

        MessagePart payload = new MessagePart();
        payload.setMimeType("text/plain").setBody(body);

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(originalText, content.getPartBodyPlain());

        String expectedTextForAnalysis = ("[text/plain]: " + "\n" + originalText);
        Assertions.assertEquals(expectedTextForAnalysis, content.getTextForAnalysis());
        Assertions.assertEquals("", content.getPartBodyHtml());
        Assertions.assertEquals("", content.getPartBodyAmpHtml());
    }

    // 2° test
    @Test
    public void shouldExtractHtmlWithLink() {
        String originalLink = "<p>Your interview will be tomorrow. <a href='https://example.com/interview'>View details</a></p>";
        byte[] originalBytes = originalLink.getBytes(StandardCharsets.UTF_8);
        String encodedLink = Base64.getUrlEncoder().encodeToString(originalBytes);

        MessagePartBody body = new MessagePartBody();
        body.setData(encodedLink);

        MessagePart payload = new MessagePart();
        payload.setMimeType("text/html").setBody(body);

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(originalLink, content.getPartBodyHtml());

        String expectedTextForAnalysis = ("[text/html]: " + "\n" + "Your interview will be tomorrow. View details: https://example.com/interview");
        Assertions.assertEquals(expectedTextForAnalysis, content.getTextForAnalysis());
        Assertions.assertEquals("", content.getPartBodyPlain());
        Assertions.assertEquals("", content.getPartBodyAmpHtml());
    }

    // 3° test
    @Test
    public void shouldExtractAmpHtmlWithLink() {
        String originalLink = "<p>Your interview will be tomorrow. <a href='https://example.com/interview'>View details</a></p>";
        byte[] originalBytes = originalLink.getBytes(StandardCharsets.UTF_8);
        String encodedLink = Base64.getUrlEncoder().encodeToString(originalBytes);

        MessagePartBody body = new MessagePartBody();
        body.setData(encodedLink);

        MessagePart payload = new MessagePart();
        payload.setMimeType("text/x-amp-html").setBody(body);

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(originalLink, content.getPartBodyAmpHtml());

        String expectedTextForAnalysis = ("[text/x-amp-html]: " + "\n" + "Your interview will be tomorrow. View details: https://example.com/interview");
        Assertions.assertEquals(expectedTextForAnalysis, content.getTextForAnalysis());
        Assertions.assertEquals("", content.getPartBodyPlain());
        Assertions.assertEquals("", content.getPartBodyHtml());
    }

    // 4° test
    @Test
    public void shouldReturnEmptyContentWhenBodyIsMissing() {
        String nothing = "";

        MessagePart payload = new MessagePart();
        payload.setMimeType("text/plain");

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(nothing, content.getPartBodyAmpHtml());
        Assertions.assertEquals("", content.getTextForAnalysis());
        Assertions.assertEquals("", content.getPartBodyPlain());
        Assertions.assertEquals("", content.getPartBodyHtml());
    }

    // 5° test
    @Test
    public void shouldIgnoreAttachmentWithFilename() {
        String nothing = "";
        String attachmentText = "Test sentence!";
        byte[] originalBytes = attachmentText.getBytes(StandardCharsets.UTF_8);
        String encodedText = Base64.getUrlEncoder().encodeToString(originalBytes);

        MessagePartBody body = new MessagePartBody();
        body.setData(encodedText);

        MessagePart payload = new MessagePart();
        payload.setMimeType("text/plain").setFilename("note.txt").setBody(body);

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(nothing, content.getPartBodyAmpHtml());
        Assertions.assertEquals("", content.getTextForAnalysis());
        Assertions.assertEquals("", content.getPartBodyPlain());
        Assertions.assertEquals("", content.getPartBodyHtml());
    }

    // 6° test
    @Test
    public void shouldIgnoreAttachmentWithContentDisposition() {
        String nothing = "";
        String attachmentText = "Test sentence!";
        byte[] originalBytes = attachmentText.getBytes(StandardCharsets.UTF_8);
        String encodedText = Base64.getUrlEncoder().encodeToString(originalBytes);

        MessagePartHeader header = new MessagePartHeader();
        header.setName("Content-Disposition").setValue("attachment");

        MessagePartBody body = new MessagePartBody();
        body.setData(encodedText);

        MessagePart payload = new MessagePart();
        payload.setHeaders(List.of(header));
        payload.setMimeType("text/plain").setBody(body);

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(nothing, content.getPartBodyAmpHtml());
        Assertions.assertEquals("", content.getTextForAnalysis());
        Assertions.assertEquals("", content.getPartBodyPlain());
        Assertions.assertEquals("", content.getPartBodyHtml());
    }

    // 7° test
    @Test
    public void shouldExtractAllBodyFormats() {
        String attachmentText = "Test sentence!";

        byte[] originalBytes = attachmentText.getBytes(StandardCharsets.UTF_8);
        String encodedText = Base64.getUrlEncoder().encodeToString(originalBytes);

        MessagePartBody body = new MessagePartBody();
        body.setData(encodedText);

        MessagePart plainPart = new MessagePart();
        plainPart.setMimeType("text/plain").setBody(body);

        MessagePart htmlPart = new MessagePart();
        htmlPart.setMimeType("text/html").setBody(body);

        MessagePart ampHtmlPart = new MessagePart();
        ampHtmlPart.setMimeType("text/x-amp-html").setBody(body);

        String expectedTextForAnalysis =
            "[text/plain]: \n" + attachmentText + "\n\n" +
            "[text/html]: \n" + attachmentText + "\n\n" +
            "[text/x-amp-html]: \n" + attachmentText;

        MessagePart payload = new MessagePart();
        payload.setMimeType("multipart/alternative").setParts(List.of(plainPart, htmlPart, ampHtmlPart)).setBody(body);

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(expectedTextForAnalysis, content.getTextForAnalysis());
        Assertions.assertEquals(attachmentText, content.getPartBodyAmpHtml());
        Assertions.assertEquals(attachmentText, content.getPartBodyPlain());
        Assertions.assertEquals(attachmentText, content.getPartBodyHtml());
    }

    // 8° test
    @Test
    public void shouldExtractNestedBodiesAndIgnoreAttachment() {

        String messageText = "Test sentence!";
        byte[] originalBytes = messageText.getBytes(StandardCharsets.UTF_8);
        String encodedText = Base64.getUrlEncoder().encodeToString(originalBytes);

        MessagePartBody body = new MessagePartBody();
        body.setData(encodedText);

        String attachmentText = "ATTACHMENT_ONLY";
        byte[] originalBytesAttachment = attachmentText.getBytes(StandardCharsets.UTF_8);
        String encodedTextAttachment = Base64.getUrlEncoder().encodeToString(originalBytesAttachment);

        MessagePartBody attachmentBody = new MessagePartBody();
        attachmentBody.setData(encodedTextAttachment);

        MessagePart plainPart = new MessagePart();
        plainPart.setMimeType("text/plain").setBody(body);

        MessagePart htmlPart = new MessagePart();
        htmlPart.setMimeType("text/html").setBody(body);

        MessagePart ampHtmlPart = new MessagePart();
        ampHtmlPart.setMimeType("text/x-amp-html").setBody(body);

        MessagePart alternatives = new MessagePart();
        alternatives.setMimeType("multipart/alternative").setParts(List.of(plainPart, htmlPart, ampHtmlPart));

        String expectedTextForAnalysis =
            "[text/plain]: \n" + messageText + "\n\n" +
            "[text/html]: \n" + messageText + "\n\n" +
            "[text/x-amp-html]: \n" + messageText;

        MessagePart attachmentPart = new MessagePart();
        attachmentPart.setMimeType("text/plain").setFilename("note.txt").setBody(attachmentBody);

        MessagePart payload = new MessagePart();
        payload.setMimeType("multipart/mixed").setParts(List.of(alternatives, attachmentPart)).setBody(body);

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(expectedTextForAnalysis, content.getTextForAnalysis());
        Assertions.assertEquals(messageText, content.getPartBodyAmpHtml());
        Assertions.assertEquals(messageText, content.getPartBodyPlain());
        Assertions.assertEquals(messageText, content.getPartBodyHtml());
    }

    // 9° test
    @Test
    public void shouldExtractPlainTextWithUppercaseMimeType() {

        String messageText = "Test sentence!";
        byte[] originalBytes = messageText.getBytes(StandardCharsets.UTF_8);
        String encodedText = Base64.getUrlEncoder().encodeToString(originalBytes);

        MessagePartBody body = new MessagePartBody();
        body.setData(encodedText);

        String attachmentText = "ATTACHMENT_ONLY";
        byte[] originalBytesAttachment = attachmentText.getBytes(StandardCharsets.UTF_8);
        String encodedTextAttachment = Base64.getUrlEncoder().encodeToString(originalBytesAttachment);

        MessagePartBody attachmentBody = new MessagePartBody();
        attachmentBody.setData(encodedTextAttachment);

        MessagePart plainPart = new MessagePart();
        plainPart.setMimeType("TEXT/PLAIN").setBody(body);

        MessagePart htmlPart = new MessagePart();
        htmlPart.setMimeType("text/html").setBody(body);

        MessagePart ampHtmlPart = new MessagePart();
        ampHtmlPart.setMimeType("text/x-amp-html").setBody(body);

        MessagePart alternatives = new MessagePart();
        alternatives.setMimeType("multipart/alternative").setParts(List.of(plainPart, htmlPart, ampHtmlPart));

        String expectedTextForAnalysis =
            "[text/plain]: \n" + messageText + "\n\n" +
            "[text/html]: \n" + messageText + "\n\n" +
            "[text/x-amp-html]: \n" + messageText;

        MessagePart attachmentPart = new MessagePart();
        attachmentPart.setMimeType("TEXT/PLAIN").setFilename("note.txt").setBody(attachmentBody);

        MessagePart payload = new MessagePart();
        payload.setMimeType("multipart/mixed").setParts(List.of(alternatives, attachmentPart)).setBody(body);

        EmailBodyExtractor extractor = new EmailBodyExtractor();
        EmailContent content = extractor.extract(payload);

        Assertions.assertEquals(expectedTextForAnalysis, content.getTextForAnalysis());
        Assertions.assertEquals(messageText, content.getPartBodyAmpHtml());
        Assertions.assertEquals(messageText, content.getPartBodyPlain());
        Assertions.assertEquals(messageText, content.getPartBodyHtml());
    }
}
