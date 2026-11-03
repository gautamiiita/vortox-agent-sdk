package com.vortox.sidecar.service;

import com.vortox.agent.spi.ToolGate;
import com.vortox.sidecar.skill.SkillDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run's {@link ToolGate}, built from what the tools declare plus whatever gates the host adds.
 * What a person reads is the declared text filled in from THIS call's arguments, because approving
 * "a declaration" is not the same as approving the one about to be made.
 */
class ToolApprovalGateTest {

    private static SkillDefinition skill(String name, String approval) {
        return new SkillDefinition(name, "d", "python", 30, Map.of(), "impl", "raw", null, approval);
    }

    @Test
    @DisplayName("a tool without a declaration is not held")
    void undeclaredRuns() {
        ToolGate gate = ToolApprovalGate.of(List.of(skill("read", null)), List.of());
        assertThat(gate.approvalRequestFor("read", Map.of())).isNull();
        assertThat(gate.approvalRequestFor("unknown_tool", Map.of())).isNull();
    }

    @Test
    @DisplayName("the declared text is filled in from the call's arguments")
    void templateRendered() {
        ToolGate gate = ToolApprovalGate.of(
                List.of(skill("declare", "Declare on your behalf: {resource}, purpose {purpose}")), List.of());

        assertThat(gate.approvalRequestFor("declare", Map.of("resource", "Contact Demography", "purpose", "BILLING")))
                .isEqualTo("Declare on your behalf: Contact Demography, purpose BILLING");
    }

    @Test
    @DisplayName("a placeholder the call did not fill says so rather than vanishing")
    void missingArgument() {
        ToolGate gate = ToolApprovalGate.of(List.of(skill("declare", "Purpose {purpose}")), List.of());
        assertThat(gate.approvalRequestFor("declare", Map.of())).isEqualTo("Purpose (not given)");
    }

    @Test
    @DisplayName("argument values are flattened and cut, so a call cannot flood or reshape the card")
    void valuesSanitised() {
        ToolGate gate = ToolApprovalGate.of(List.of(skill("declare", "Reason: {r}")), List.of());
        String text = gate.approvalRequestFor("declare", Map.of("r", "line1\nline2 " + "x".repeat(500)));
        assertThat(text).doesNotContain("\n").startsWith("Reason: line1 line2 ");
        assertThat(text.length()).isLessThan(260);
    }

    @Test
    @DisplayName("`requires_approval: true` gets a default text naming the tool and its arguments")
    void defaultText() {
        ToolGate gate = ToolApprovalGate.of(List.of(skill("delete_rows", "")), List.of());
        String text = gate.approvalRequestFor("delete_rows", Map.of("table", "orders"));
        assertThat(text).contains("delete_rows").contains("table").contains("orders");
    }

    @Test
    @DisplayName("a host gate holds tools the skills know nothing about")
    void hostGate() {
        ToolGate host = (name, params) -> "ds_record".equals(name) ? "Host asks: " + params.get("q") : null;
        ToolGate gate = ToolApprovalGate.of(List.of(), List.of(host));
        assertThat(gate.approvalRequestFor("ds_record", Map.of("q", "Q1"))).isEqualTo("Host asks: Q1");
        assertThat(gate.approvalRequestFor("other", Map.of())).isNull();
    }

    @Test
    @DisplayName("control tools are never held, whatever a gate says — holding task_complete would deadlock")
    void controlToolsNeverHeld() {
        ToolGate greedy = (name, params) -> "hold everything";
        ToolGate gate = ToolApprovalGate.of(List.of(), List.of(greedy));
        assertThat(gate.approvalRequestFor("task_complete", Map.of())).isNull();
        assertThat(gate.approvalRequestFor("request_approval", Map.of())).isNull();
        assertThat(gate.approvalRequestFor("anything_else", Map.of())).isEqualTo("hold everything");
    }

    @Test
    @DisplayName("nothing declared and no host gate: the open gate, so the loop skips the check")
    void openWhenNothingDeclared() {
        assertThat(ToolApprovalGate.of(List.of(skill("read", null)), List.of())).isSameAs(ToolGate.OPEN);
    }
}
