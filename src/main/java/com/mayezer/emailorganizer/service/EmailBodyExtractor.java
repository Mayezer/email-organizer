package com.mayezer.emailorganizer.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;

import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.mayezer.emailorganizer.model.EmailContent;
import com.mayezer.emailorganizer.model.EmailIndicators;

public class EmailBodyExtractor {
    private static final String PLAIN = "text/plain";
    private static final String HTML = "text/html";
    private static final String AMP = "text/x-amp-html";
    private static final Pattern WEB_LINK = Pattern.compile("(?i)\\bhttps?://(?:[\\p{L}\\p{N}]|\\[)[^\\s<>\"'/?#]*");

    public EmailContent extract(MessagePart payload) {
        var parts = new BodyParts();
        collectBodies(payload, parts);
        List<String> plainParts = parts.bodies.getOrDefault(PLAIN, List.of());
        List<String> htmlParts = parts.bodies.getOrDefault(HTML, List.of());
        List<String> ampParts = parts.bodies.getOrDefault(AMP, List.of());
        String plain = String.join("\n\n", plainParts);
        HtmlText html = convertHtmlParts(htmlParts);
        HtmlText amp = convertHtmlParts(ampParts);

        List<String> textForAnalysis = new ArrayList<>();
        addAnalysisText(textForAnalysis, PLAIN, plain);
        addAnalysisText(textForAnalysis, HTML, html.text());
        addAnalysisText(textForAnalysis, AMP, amp.text());

        Boolean hasLinks = null;
        if (html.hasLinks() || amp.hasLinks() || WEB_LINK.matcher(plain).find()) {
            hasLinks = true;
        } else if (!parts.unreadableBody) {
            hasLinks = false;
        }
        Boolean hasAttachments = payload == null ? null : parts.hasAttachments;
        return new EmailContent(plain, String.join("\n\n", htmlParts), String.join("\n\n", ampParts),
            String.join("\n\n", textForAnalysis), new EmailIndicators(hasAttachments, hasLinks));
    }

    private void collectBodies(MessagePart part, BodyParts result) {
        if (part == null) {
            result.unreadableBody = true;
            return;
        }

        String mimeType = part.getMimeType() == null ? "" : part.getMimeType().toLowerCase(Locale.ROOT);
        boolean bodyType = PLAIN.equals(mimeType) || HTML.equals(mimeType) || AMP.equals(mimeType);
        MessagePartBody body = part.getBody();
        String disposition = header(part, "Content-Disposition").split(";", 2)[0].trim();
        boolean external = body != null && hasValue(body.getAttachmentId());

        if (hasValue(part.getFilename()) || "attachment".equalsIgnoreCase(disposition)
                || (!bodyType && !mimeType.startsWith("multipart/") && (
                    "inline".equalsIgnoreCase(disposition) || hasValue(header(part, "Content-ID")) || external))) {
            result.hasAttachments = true;
            return;
        }

        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                collectBodies(child, result);
            }
        }
        if (!bodyType) {
            return;
        }

        if (body == null || body.getData() == null) {
            // Gmail can put the textual body in a separate resource. Its contents are still unknown.
            if (body == null || external || !Integer.valueOf(0).equals(body.getSize())) {
                result.unreadableBody = true;
            }
            return;
        }
        if (body.getData().isEmpty() && (external || (body.getSize() != null && body.getSize() > 0))) {
            result.unreadableBody = true;
            return;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(body.getData());
            result.bodies.computeIfAbsent(mimeType, key -> new ArrayList<>())
                .add(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalidBase64) {
            result.unreadableBody = true;
        }
    }

    private static String header(MessagePart part, String name) {
        if (part.getHeaders() != null) {
            for (MessagePartHeader header : part.getHeaders()) {
                if (header != null && name.equalsIgnoreCase(header.getName()) && header.getValue() != null) {
                    return header.getValue();
                }
            }
        }
        return "";
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static HtmlText convertHtmlParts(List<String> parts) {
        boolean hasLinks = false;
        List<String> text = new ArrayList<>();
        for (String part : parts) {
            var document = Jsoup.parse(part);
            hasLinks |= WEB_LINK.matcher(document.text()).find();
            for (var link : document.select("a[href], area[href]")) {
                String target = link.attr("href").strip();
                hasLinks |= WEB_LINK.matcher(target).lookingAt();
                if (!target.isEmpty() && link.normalName().equals("a")) {
                    link.text(link.text() + ": " + link.attr("href"));
                }
            }
            text.add(document.text());
        }
        return new HtmlText(String.join("\n\n", text), hasLinks);
    }

    private static void addAnalysisText(List<String> parts, String type, String text) {
        if (!text.isBlank()) {
            parts.add("[" + type + "]: \n" + text);
        }
    }

    private static final class BodyParts {
        private final Map<String, List<String>> bodies = new HashMap<>();
        private boolean hasAttachments;
        private boolean unreadableBody;
    }

    private record HtmlText(String text, boolean hasLinks) {}
}
