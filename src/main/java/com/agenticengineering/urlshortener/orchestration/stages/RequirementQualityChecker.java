package com.agenticengineering.urlshortener.orchestration.stages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Requirement-quality checks and material-ambiguity detection for N02 (T056; FR-034, FR-035; material ambiguity as
 * defined in spec Amendment Appendix A). Disclosed limitation: this is a rule-based heuristic over the vocabulary of
 * the URL-shortener domain, validated on the SCN-A/B/C fixtures only; it is not general language understanding.
 * Every rule and term list is in this class so a reviewer can inspect exactly what is checked.
 */
public class RequirementQualityChecker {

    private static final Pattern ACTION = Pattern.compile(
            "\\b(add|return|returning|change|create|resolve|list|expose|provide|record|lookup|redirect|remove|delete)\\b");
    private static final Pattern OBJECT = Pattern.compile(
            "\\b(endpoint|short code|short links?|link status|link ttl|ttl|expiry|expiry time|analytics|summary|redirect)\\b");
    private static final Pattern ACCEPTANCE_DETAIL = Pattern.compile(
            "\\b(\\d+|active|expired|unknown|read-only|/api/v\\d+|days?|hours?|minutes?)\\b|/api/v\\d+");
    private static final Pattern CHANGE = Pattern.compile("\\b(change|modify|update|replace|increase|decrease|reduce)\\b.*\\bfrom\\b.*\\bto\\b");
    private static final Pattern NEW = Pattern.compile("\\b(add|new|endpoint|capability)\\b");
    private static final Pattern WRITE = Pattern.compile("\\b(delete|deletes|create|creates|update|updates|remove|removes|change|changes)\\b");
    private static final Pattern UNQUALIFIED_LINKS = Pattern.compile("\\blinks?\\b");
    private static final Pattern QUALIFIED_LINKS = Pattern.compile("\\b(short links?|link status|link ttl|default link)\\b");
    private static final Pattern POLICY_CONFLICT = Pattern.compile(
            "\\b(fetch(es|ing)? the target|crawl|store (the )?(client )?ip|personal data|skip approval|bypass approval|disable audit)\\b");

    /** Vague terms and the interpretations each admits (each changes the contract or acceptance criteria). */
    private static final Map<String, List<String>> VAGUE = new LinkedHashMap<>();

    static {
        VAGUE.put("support", List.of("create short links", "resolve short links", "list links", "delete links",
                "report analytics for links"));
        VAGUE.put("faster", List.of("lower redirect latency", "higher creation throughput", "faster analytics reporting"));
        VAGUE.put("better", List.of("better performance", "better security", "better usability"));
        VAGUE.put("improve", List.of("improve performance", "improve security", "improve error messages"));
        VAGUE.put("secure", List.of("authentication on creation", "stricter target validation", "encryption at rest"));
        VAGUE.put("handle", List.of("reject with an error", "retry", "ignore and log"));
        VAGUE.put("manage", List.of("create", "update", "delete", "list"));
    }

    public QualityReport check(String requirement) {
        String text = requirement == null ? "" : requirement.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("requirement text is empty (malformed input, N01)");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<QualityReport.Finding> findings = new ArrayList<>();

        for (Map.Entry<String, List<String>> vague : VAGUE.entrySet()) {
            if (Pattern.compile("\\b" + vague.getKey() + "\\b").matcher(lower).find()) {
                findings.add(finding(findings, vague.getKey(), "vague term: the operations or outcome are not specified",
                        vague.getValue()));
            }
        }
        if (UNQUALIFIED_LINKS.matcher(lower).find() && !QUALIFIED_LINKS.matcher(lower).find()) {
            findings.add(finding(findings, "links", "object admits several meanings with different contracts",
                    List.of("short links (codes issued by this service)", "target URLs", "links inside documents")));
        }

        boolean complete = ACTION.matcher(lower).find() && OBJECT.matcher(lower).find() && ACCEPTANCE_DETAIL.matcher(lower).find();
        boolean consistent = !(lower.contains("read-only") && WRITE.matcher(lower).find());
        if (!consistent) {
            findings.add(finding(findings, "read-only", "conflicting operations: read-only scope with a write action",
                    List.of("a read-only lookup without the write action", "a write endpoint that is not read-only")));
        }
        boolean policyFit = !POLICY_CONFLICT.matcher(lower).find();
        if (!policyFit) {
            findings.add(finding(findings, "policy", "conflicts with an approved boundary (D-003, NFR-001 or human governance)",
                    List.of("drop the conflicting part", "request a governed policy exception")));
        }
        boolean vagueFound = findings.stream().anyMatch(f -> VAGUE.containsKey(f.term()) || f.term().equals("links"));
        boolean testable = complete && !vagueFound && consistent;
        if (!complete && findings.isEmpty()) {
            findings.add(finding(findings, "acceptance condition", "subject, action or acceptance condition missing",
                    List.of("the requester states the observable outcome", "the outcome is inferred (not allowed without approval)")));
        }

        Map<String, QualityReport.Outcome> checks = new LinkedHashMap<>();
        checks.put("completeness", outcome(complete));
        checks.put("consistency", outcome(consistent));
        checks.put("testability", outcome(testable));
        checks.put("policy_fit", outcome(policyFit));

        QualityReport.Kind kind = CHANGE.matcher(lower).find() ? QualityReport.Kind.CHANGE_REQUEST
                : NEW.matcher(lower).find() ? QualityReport.Kind.NEW_CAPABILITY : QualityReport.Kind.UNCLASSIFIED;
        boolean material = !findings.isEmpty();
        Set<String> branches = new LinkedHashSet<>();
        if (material) {
            branches.add("N03");
        }
        if (kind == QualityReport.Kind.CHANGE_REQUEST) {
            branches.add("N04");
        }
        String rationale = material
                ? "clarification required: " + findings.size() + " material finding(s) " + findings.stream().map(f -> f.id() + " '"
                        + f.term() + "'").toList()
                : "no clarification required: completeness, consistency, testability and policy fit all PASS"
                        + " (action, object and acceptance detail present; no conflicting operations; no vague terms)";
        return new QualityReport(text, kind, checks, List.copyOf(findings), material, Set.copyOf(branches), rationale);
    }

    private static QualityReport.Finding finding(List<QualityReport.Finding> existing, String term, String reason,
            List<String> interpretations) {
        return new QualityReport.Finding("AMB-" + (existing.size() + 1), term, reason, List.copyOf(interpretations));
    }

    private static QualityReport.Outcome outcome(boolean pass) {
        return pass ? QualityReport.Outcome.PASS : QualityReport.Outcome.FAIL;
    }
}
