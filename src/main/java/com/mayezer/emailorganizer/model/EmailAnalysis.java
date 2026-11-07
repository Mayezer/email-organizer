package com.mayezer.emailorganizer.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class EmailAnalysis {

    public enum SecurityStatus {
        NO_ALERT, SUSPICIOUS, INCONCLUSIVE
    }

    public enum RiskSignal {
        UNEXPECTED_CREDENTIAL_REQUEST(true),
        UNEXPECTED_PAYMENT_REQUEST(true),
        SENDER_IMPERSONATION(true),
        DECEPTIVE_DESTINATION(true),
        MALICIOUS_ATTACHMENT_INDICATOR(true),
        OTHER_CONCRETE_RISK(true),
        LINK_TRACKING(false),
        THIRD_PARTY_DOMAIN(false),
        ALTERNATIVE_FORMAT_LINKS(false);

        private final boolean concrete;

        RiskSignal(boolean concrete) {
            this.concrete = concrete;
        }

        public boolean isConcrete() {
            return concrete;
        }
    }

    private final String category;
    private final String summary;
    private final boolean requiresAction;
    private final SecurityStatus securityStatus;
    private final String securityReason;
    private final List<RiskSignal> riskSignals;

    public EmailAnalysis(String category, String summary, boolean requiresAction,
        SecurityStatus securityStatus, String securityReason) {
        this(category, summary, requiresAction, securityStatus, securityReason, List.of());
    }

    @JsonCreator
    public EmailAnalysis (
        @JsonProperty("category") String category,
        @JsonProperty("summary") String summary,
        @JsonProperty("requiresAction") boolean requiresAction,
        @JsonProperty("securityStatus") SecurityStatus securityStatus,
        @JsonProperty("securityReason") String securityReason,
        @JsonProperty("riskSignals") List<RiskSignal> riskSignals) {
            this.category = category;
            this.summary = summary;
            this.requiresAction = requiresAction;
            this.securityStatus = securityStatus;
            this.securityReason = securityReason;
            this.riskSignals = riskSignals == null ? null : List.copyOf(riskSignals);
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

    public SecurityStatus getSecurityStatus() {
        return securityStatus;
    }

    public String getSecurityReason() {
        return securityReason;
    }

    public List<RiskSignal> getRiskSignals() {
        return riskSignals;
    }
}
