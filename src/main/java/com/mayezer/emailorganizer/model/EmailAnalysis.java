package com.mayezer.emailorganizer.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class EmailAnalysis {

    private final String category;
    private final String summary;
    private final boolean requiresAction;

    @JsonCreator
    public EmailAnalysis (
        @JsonProperty("category") String category,
        @JsonProperty("summary") String summary,
        @JsonProperty("requiresAction") boolean requiresAction) {
            this.category = category;
            this.summary = summary;
            this.requiresAction = requiresAction;
    }

    public String getCategory() {
        return category;
    }

    public String getSummary() {
        return summary;
    }

    public boolean getRequiresAction() {
        return requiresAction;
    }

}
