package com.mayezer.emailorganizer.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.mayezer.emailorganizer.model.EmailContent;

public class EmailBodyExtractor {

    private String decodeBody(String data) {
        String textDecoded = null;
        if (data != null) {
                byte[] decodeFunction = Base64.getUrlDecoder().decode(data);
                textDecoded = new String(decodeFunction, StandardCharsets.UTF_8);
            }
        return textDecoded;
    }

    private String convertHtmlToText(String html) {
        Document document = Jsoup.parse(html);
        Elements links = document.select("a[href]");
        for (Element link : links) {
            String label = link.text();
            String url = link.attr("href");
            if (url != null && !url.isBlank()) {
                    String linkDescription = label + ": " + url;
                    link.text(linkDescription);
            }
        }
        String text = document.text();
        return text;
    }

    private String convertHtmlParts(List<String> htmlParts) {
        List<String> convertedHtmlParts = new ArrayList<>();
        for (String htmlPart : htmlParts) {
            htmlPart = convertHtmlToText(htmlPart);
            convertedHtmlParts.add(htmlPart);
        }
        return String.join("\n\n", convertedHtmlParts);
    }

    private void collectBodies(MessagePart part, Map<String, List<String>> bodies) {
        String partBodyString = null;
        if (part == null) {
            return;
        }

        String fileName = part.getFilename();
        if (fileName != null && !fileName.isBlank()) {
            return;
        }

        List<MessagePartHeader> headers = part.getHeaders();
        if (headers != null) {
            for (MessagePartHeader header : headers) {
                String name = header.getName();
                if ("Content-Disposition".equalsIgnoreCase(name)) {
                    String disposition = header.getValue();
                    if (disposition != null) {
                        String type = disposition.split(";", 2)[0].trim();
                        if ("attachment".equalsIgnoreCase(type)) {
                            return;
                        }
                    }
                }
            }
        }

        String mimeType = part.getMimeType();
        List<MessagePart> parts = part.getParts();
        if (parts != null && !parts.isEmpty()) {
            for (MessagePart childPart : parts) { // Recursividade com for
                collectBodies((childPart), bodies);
            }
        }

        if ("text/plain".equalsIgnoreCase(mimeType)
            || "text/html".equalsIgnoreCase(mimeType)
            || "text/x-amp-html".equalsIgnoreCase(mimeType)) {
            MessagePartBody partBody = part.getBody();
            if (partBody != null) {
                partBodyString = decodeBody(partBody.getData());
                if (partBodyString != null) {
                    bodies.computeIfAbsent(mimeType.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                        .add(partBodyString);
                }
            }
        }
    }

    public EmailContent extract(MessagePart payload) {
        Map<String, List<String>> bodies = new HashMap<>();
        collectBodies(payload, bodies);

        List<String> plainParts = bodies.getOrDefault("text/plain", List.of());
        String plainText = String.join("\n\n", plainParts);

        List<String> htmlParts = bodies.getOrDefault("text/html", List.of());
        String htmlText = String.join("\n\n", htmlParts);
        String htmlTextConverted = convertHtmlParts(htmlParts);

        List<String> ampHtmlParts = bodies.getOrDefault("text/x-amp-html", List.of());
        String ampHtmlText = String.join("\n\n", ampHtmlParts);
        String ampHtmlTextConverted = convertHtmlParts(ampHtmlParts);

        List<String> textAnalysis = new ArrayList<>();
        if (plainText !=null && !plainText.isBlank()) {
            textAnalysis.add("[text/plain]: "+ "\n" + plainText);
        }
        if (htmlTextConverted != null && !htmlTextConverted.isBlank()) {
            textAnalysis.add("[text/html]: " + "\n" + htmlTextConverted);
        }
        if (ampHtmlTextConverted != null && !ampHtmlTextConverted.isBlank()) {
            textAnalysis.add("[text/x-amp-html]: " + "\n" + ampHtmlTextConverted);
        }

        String textForAnalysis = String.join("\n\n", textAnalysis);

        EmailContent emailContent = new EmailContent(
            plainText,
            htmlText,
            ampHtmlText,
            textForAnalysis);

        return emailContent;
    }
}
