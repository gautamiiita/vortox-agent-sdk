package com.vortox.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Runs a {@link CompletionCheck}: one model call with no tools and none of the run's conversation.
 * See CompletionCheck for why.
 */
final class FreshCheck {

    private static final Logger log = LoggerFactory.getLogger(FreshCheck.class);

    /** Evidence beyond this is cut; the host is expected to fit its own budget, this is the backstop. */
    static final int MAX_EVIDENCE_CHARS = 400_000;

    static final String SYSTEM_PROMPT = """
            You check whether finished work meets the requirements it was given. You did not do the work \
            and have no stake in it. You are shown the task as it was written and evidence of what was \
            done, read from the work itself. You are deliberately not shown the worker's own account of it.

            List every requirement the task states — each instruction the work must satisfy, in the \
            task's order, numbered as the task numbers them where it does — and decide each one:
            - MET: the evidence shows it done.
            - NOT_MET: the evidence shows it not done, or done only in part. Say exactly what is missing \
            and where (file, function, count).
            - UNCLEAR: the evidence cannot show it either way, for example because it needs the \
            application running.

            Rules:
            - Judge the requirement as written, not a narrower reading of it. "Every", "all", "no", \
            "never" and "unchanged" mean exactly that; work left undone is not out of scope because the \
            work did not reach it.
            - Something that is not in the change is not done, unless the evidence shows it was already \
            so before.
            - Judge only what the task requires. Not style, design, test quality or extras, unless the \
            task asks for them.
            - When in doubt between MET and NOT_MET, prefer UNCLEAR. A NOT_MET sends the work back, so \
            it needs evidence you can point to.

            Answer with JSON only, no prose:
            {"requirements":[{"requirement":"<n>. <the requirement, shortened>","status":"MET|NOT_MET|UNCLEAR","evidence":"<what shows it>"}]}""";

    private FreshCheck() {}

    /** Token usage of the check, added to the run's totals. */
    record Usage(int in, int out, int cacheCreate, int cacheRead) {
        static final Usage NONE = new Usage(0, 0, 0, 0);
    }

    record Outcome(CompletionVerdict verdict, Usage usage) {}

    static Outcome run(LlmClient client, String apiKey, String model, CompletionCheck check, String tag) {
        String evidence;
        try {
            evidence = check.evidence().get();
        } catch (Exception e) {
            log.warn("ReactLoop [{}] completion check: could not gather evidence: {}", tag, e.getMessage());
            return new Outcome(CompletionVerdict.notRun("the evidence could not be gathered: " + e.getMessage()), Usage.NONE);
        }
        if (evidence == null || evidence.isBlank()) {
            return new Outcome(CompletionVerdict.notRun("there was no evidence to check"), Usage.NONE);
        }
        if (evidence.length() > MAX_EVIDENCE_CHARS) {
            evidence = evidence.substring(0, MAX_EVIDENCE_CHARS) + "\n\n[evidence cut at " + MAX_EVIDENCE_CHARS + " characters]";
        }
        String message = "## The task as written\n\n" + check.requirements().strip()
                + "\n\n## Evidence of what was done\n\n" + evidence;

        AnthropicClient.ClaudeResponse response = client.send(SYSTEM_PROMPT,
                List.of(Map.of("role", "user", "content", message)), List.of(), apiKey, model);
        Usage usage = new Usage(response.getInputTokens(), response.getOutputTokens(),
                response.getCacheCreationInputTokens(), response.getCacheReadInputTokens());
        if (response.hasError()) {
            log.warn("ReactLoop [{}] completion check: model error, accepting the completion: {}", tag, response.getError());
            return new Outcome(CompletionVerdict.notRun("the check's model call failed: " + response.getError()), usage);
        }
        CompletionVerdict verdict = CompletionVerdict.parse(response.getTextContent());
        if (!verdict.ran()) {
            log.warn("ReactLoop [{}] completion check did not run: {}", tag, verdict.note());
        } else {
            log.info("ReactLoop [{}] completion check: {} requirement(s), {} not met",
                    tag, verdict.findings().size(), verdict.unmet().size());
        }
        return new Outcome(verdict, usage);
    }
}
