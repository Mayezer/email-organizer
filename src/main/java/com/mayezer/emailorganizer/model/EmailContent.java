package com.mayezer.emailorganizer.model;

public class EmailContent {

    private final String plainDecoded; // Encapsulamento
    private final String htmlDecoded;
    private final String ampHtmlDecoded;
    private final String textForAnalysis;
    private final EmailIndicators indicators;

    public EmailContent(
        String plainDecoded,
        String htmlDecoded,
        String ampHtmlDecoded,
        String textForAnalysis,
        EmailIndicators indicators) { // Construtor
            this.plainDecoded = plainDecoded;
            this.htmlDecoded = htmlDecoded;
            this.ampHtmlDecoded = ampHtmlDecoded;
            this.textForAnalysis = textForAnalysis;
        this.indicators = indicators;
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

    public EmailIndicators getIndicators() {
        return indicators;
    }
}
