package com.mayezer.emailorganizer.model;

public class EmailContent {

    private final String plainDecoded; // Encapsulamento
    private final String htmlDecoded;
    private final String ampHtmlDecoded;

    public EmailContent(
        String plainDecoded, 
        String htmlDecoded,
        String ampHtmlDecoded) { // Construtor
            this.plainDecoded = plainDecoded;
            this.htmlDecoded = htmlDecoded;
            this.ampHtmlDecoded = ampHtmlDecoded;
    }

    public String getPartBodyPlain() { // Metodos com retorno
        return plainDecoded;
    }
    public String getPartBodyHtml() {
        return  htmlDecoded;
    }
    public String getPartBodyAmpHtml() {
        return ampHtmlDecoded;
    }
}

