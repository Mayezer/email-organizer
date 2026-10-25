package com.mayezer.emailorganizer.model;

public class EmailContent {

    private final String plainDecoded; // Encapsulamento
    private final String htmlDecoded;
    private final String ampHtmlDecoded;
    private final String textForAnalysis;

    public EmailContent(
        String plainDecoded,
        String htmlDecoded,
        String ampHtmlDecoded,
        String textForAnalysis) { // Construtor
            this.plainDecoded = plainDecoded;
            this.htmlDecoded = htmlDecoded;
            this.ampHtmlDecoded = ampHtmlDecoded;
            this.textForAnalysis = textForAnalysis;
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
    public String getTextForAnalysis(){
        return textForAnalysis;
    }
}
