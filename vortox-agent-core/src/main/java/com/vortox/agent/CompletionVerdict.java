package com.vortox.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * What a {@link CompletionCheck} found: one finding per requirement it read in the task.
 *
 * @param ran       false when the check could not run (see {@code note}); the completion was accepted
 * @param findings  one per requirement, in the order the task states them
 * @param sendBacks how many times findings sent the run back before it ended
 * @param note      why the check did not run, or null
 */
public record CompletionVerdict(boolean ran, List<Finding> findings, int sendBacks, String note) {

    public enum Status { MET, NOT_MET, UNCLEAR }

    /** One requirement as the check read it, and the evidence for its status. */
    public record Finding(String requirement, Status status, String evidence) {}

    public CompletionVerdict {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    static CompletionVerdict notRun(String why) {
        return new CompletionVerdict(false, List.of(), 0, why);
    }

    /** The requirements the evidence shows are not met. UNCLEAR is not counted: it may not send work back. */
    public List<Finding> unmet() {
        return findings.stream().filter(f -> f.status() == Status.NOT_MET).toList();
    }

    /** One line for an activity feed: what was found not met, shortened. */
    String unmetSummary() {
        String s = String.join("; ", unmet().stream().map(Finding::requirement).toList());
        return s.length() > 300 ? s.substring(0, 299) + "…" : s;
    }

    CompletionVerdict withSendBacks(int n) {
        return new CompletionVerdict(ran, findings, n, note);
    }

    /** The findings as the agent is told them when its completion is returned. */
    String sendBackMessage() {
        StringBuilder sb = new StringBuilder(
                "Not accepted yet. An independent check compared your work with the task's requirements. "
                + "It saw the requirements and the work itself — not your report — and found these not met:\n");
        for (Finding f : unmet()) {
            sb.append("\n- ").append(f.requirement());
            if (f.evidence() != null && !f.evidence().isBlank()) sb.append("\n  ").append(f.evidence().strip());
        }
        sb.append("\n\nFix them, then call task_complete again. Do not narrow a requirement to what is done: "
                + "\"every\", \"all\" and \"no\" mean exactly that. If you are certain a finding is wrong, "
                + "say why in your final report — a person will see both.");
        return sb.toString();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Reads the check's answer: a JSON object with a {@code requirements} array of
     * {@code {requirement, status, evidence}}. Lenient about prose or a code fence around it.
     * An answer that cannot be read is a check that did not run, not a pass.
     */
    static CompletionVerdict parse(String answer) {
        if (answer == null) return notRun("the check returned nothing");
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) return notRun("the check's answer was not JSON");
        try {
            JsonNode root = JSON.readTree(answer.substring(start, end + 1));
            JsonNode items = root.path("requirements");
            if (!items.isArray() || items.isEmpty()) return notRun("the check listed no requirements");
            List<Finding> findings = new ArrayList<>();
            for (JsonNode item : items) {
                String requirement = text(item, "requirement");
                if (requirement == null) continue;
                findings.add(new Finding(requirement, status(text(item, "status")), text(item, "evidence")));
            }
            if (findings.isEmpty()) return notRun("the check listed no requirements");
            return new CompletionVerdict(true, findings, 0, null);
        } catch (Exception e) {
            return notRun("the check's answer could not be read: " + e.getMessage());
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static Status status(String s) {
        if (s == null) return Status.UNCLEAR;
        String norm = s.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        if (norm.equals("MET")) return Status.MET;
        if (norm.equals("NOT_MET") || norm.equals("UNMET") || norm.equals("PARTIAL")
                || norm.equals("PARTIALLY_MET")) return Status.NOT_MET;
        return Status.UNCLEAR;
    }
}
