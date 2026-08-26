package com.vortox.sidecar.api;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where {@code GET /agent/llm/models} is allowed to fetch from.
 *
 * <p>This endpoint took a {@code baseUrl} parameter, appended {@code /api/models} to the string, and
 * returned the response body verbatim — an arbitrary-URL fetch from the sidecar's network position
 * for anything holding the sidecar key. Appending a path looked like it constrained the target and
 * did not: a {@code baseUrl} carrying its own query or fragment simply absorbs the suffix, so
 * {@code http://169.254.169.254/latest/meta-data/#} reached cloud metadata and handed it back.
 *
 * <p>Two things fix it: the host is checked against a configured allow-list, and the URI is rebuilt
 * from its parsed parts so a query or fragment cannot survive into the request.
 */
class AgentControllerLlmModelsUriTest {

    private static final Set<String> ALLOWED = Set.of("ai.example.internal");

    @Test
    void buildsTheModelsUriForAnAllowedHost() {
        URI uri = AgentController.resolveAllowedModelsUri("https://ai.example.internal", ALLOWED);

        assertThat(uri.toString()).isEqualTo("https://ai.example.internal/api/models");
    }

    @Test
    void toleratesATrailingSlashAndKeepsABasePath() {
        assertThat(AgentController.resolveAllowedModelsUri("https://ai.example.internal/", ALLOWED))
                .hasToString("https://ai.example.internal/api/models");
        assertThat(AgentController.resolveAllowedModelsUri("https://ai.example.internal/v1/", ALLOWED))
                .hasToString("https://ai.example.internal/v1/api/models");
    }

    /** Off unless configured: a deployment that hasn't named its LLM host has no use for a proxy. */
    @Test
    void isDisabledWhenNoHostsAreConfigured() {
        assertThatThrownBy(() -> AgentController.resolveAllowedModelsUri("https://ai.example.internal", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not enabled");
    }

    @Test
    void refusesAHostThatIsNotAllowed() {
        assertThatThrownBy(() -> AgentController.resolveAllowedModelsUri("http://169.254.169.254/", ALLOWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in sidecar.llm.allowed-hosts");

        assertThatThrownBy(() -> AgentController.resolveAllowedModelsUri("http://localhost:8080", ALLOWED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The escape that made the appended path meaningless. Both of these previously produced a
     * request to somewhere other than {@code …/api/models} on the named host.
     */
    @Test
    void dropsAQueryOrFragmentRatherThanLettingItAbsorbTheAppendedPath() {
        URI withFragment = AgentController.resolveAllowedModelsUri(
                "https://ai.example.internal/latest/meta-data/#", ALLOWED);
        assertThat(withFragment.toString())
                .isEqualTo("https://ai.example.internal/latest/meta-data/api/models");
        assertThat(withFragment.getFragment()).isNull();

        URI withQuery = AgentController.resolveAllowedModelsUri(
                "https://ai.example.internal/admin?next=", ALLOWED);
        assertThat(withQuery.getQuery()).isNull();
        assertThat(withQuery.getPath()).isEqualTo("/admin/api/models");
    }

    /** A host is not enough — a `file:` or `gopher:` URL is not something to fetch on request. */
    @Test
    void refusesSchemesOtherThanHttp() {
        assertThatThrownBy(() -> AgentController.resolveAllowedModelsUri(
                "file://ai.example.internal/etc/passwd", ALLOWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
    }

    @Test
    void refusesInputThatIsNotAUsableUrl() {
        assertThatThrownBy(() -> AgentController.resolveAllowedModelsUri("not a url", ALLOWED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentController.resolveAllowedModelsUri("https:///no-host", ALLOWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    /** Host comparison is case-insensitive, as DNS is. */
    @Test
    void matchesTheHostRegardlessOfCase() {
        assertThat(AgentController.resolveAllowedModelsUri("https://AI.Example.Internal", ALLOWED))
                .hasToString("https://ai.example.internal/api/models");
    }
}
