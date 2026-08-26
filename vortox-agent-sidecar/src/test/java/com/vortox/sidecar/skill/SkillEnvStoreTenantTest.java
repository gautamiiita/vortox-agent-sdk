package com.vortox.sidecar.skill;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The highest-severity isolation boundary in a pooled sidecar.
 *
 * <p>The backend resolves each skill's {@code requiredSecrets} against the <em>requesting</em>
 * tenant's secrets, so one shared {@code oracle_query} body runs against a different database per
 * institution. This store was a single global map; left that way, the first tenant to sync would
 * have its {@code ORACLE_PASSWORD} injected into every other tenant's run — succeeding against the
 * wrong database rather than failing, which is far worse than a missing credential.
 */
class SkillEnvStoreTenantTest {

    private static Map<String, Map<String, String>> oracleFor(String host) {
        return Map.of("oracle_query", Map.of("ORACLE_HOST", host, "ORACLE_PASSWORD", "pw-" + host));
    }

    @Test
    void eachTenantSeesOnlyItsOwnValues() {
        SkillEnvStore store = new SkillEnvStore();
        store.resetTenant("tenant-cube", oracleFor("cube-db"));
        store.resetTenant("tenant-arena", oracleFor("arena-db"));

        assertThat(store.get("tenant-cube", "oracle_query")).containsEntry("ORACLE_HOST", "cube-db");
        assertThat(store.get("tenant-arena", "oracle_query")).containsEntry("ORACLE_HOST", "arena-db");
    }

    @Test
    void anUnsyncedTenantGetsNothingRatherThanAnotherTenantsCredentials() {
        SkillEnvStore store = new SkillEnvStore();
        store.resetTenant("tenant-cube", oracleFor("cube-db"));

        // No fallback across tenants: a missing credential fails the skill loudly, a borrowed one
        // would quietly connect to the wrong system.
        assertThat(store.get("tenant-never-synced", "oracle_query")).isEmpty();
        assertThat(store.hasTenant("tenant-never-synced")).isFalse();
    }

    /**
     * The case the test above missed, and the reason the bug survived: it only ever populated a
     * tenant bucket, so the untenanted one was empty and the fallback had nothing to leak.
     *
     * <p>A real deployment fills it on startup — {@code sync("startup", null)} runs before any
     * tenant is known — and those values belong to whichever tenant the key resolves to. Falling
     * back to them for a different tenant code is the "succeeds against the wrong database" failure
     * this class exists to prevent.
     */
    @Test
    void aNamedTenantNeverReadsTheUntenantedBucketByDefault() {
        SkillEnvStore store = new SkillEnvStore();
        store.reset(oracleFor("first-tenant-db"));         // what an untenanted startup sync leaves
        store.resetTenant("tenant-arena", Map.of());       // synced, but no values for this skill

        assertThat(store.get("tenant-arena", "oracle_query"))
                .as("must not inherit the untenanted bucket's credentials")
                .isEmpty();
        assertThat(store.get("tenant-never-synced", "oracle_query")).isEmpty();
        // The untenanted bucket itself still reads, for genuinely untenanted runs.
        assertThat(store.get("oracle_query")).containsEntry("ORACLE_HOST", "first-tenant-db");
    }

    /** The fallback is available, but only where someone has asserted there is one tenant. */
    @Test
    void theUntenantedBucketIsReachableOnlyWhenSingleTenantIsDeclared() {
        SkillEnvStore store = new SkillEnvStore();
        org.springframework.test.util.ReflectionTestUtils.setField(store, "singleTenant", true);
        store.reset(oracleFor("the-one-db"));

        assertThat(store.get("tenant-arena", "oracle_query"))
                .containsEntry("ORACLE_HOST", "the-one-db");
    }

    /** A tenant's own value always wins, flag or no flag — the fallback is only for absence. */
    @Test
    void aTenantsOwnValueIsNeverDisplacedByTheUntenantedBucket() {
        SkillEnvStore store = new SkillEnvStore();
        org.springframework.test.util.ReflectionTestUtils.setField(store, "singleTenant", true);
        store.reset(oracleFor("the-one-db"));
        store.resetTenant("tenant-cube", oracleFor("cube-db"));

        assertThat(store.get("tenant-cube", "oracle_query")).containsEntry("ORACLE_HOST", "cube-db");
    }

    /**
     * {@code " shared"} was the sentinel key for the untenanted bucket, and it is not blank — so it
     * sat in the same namespace as real tenant ids and a tenant of that name would have read and
     * overwritten it. The bucket is a separate field now.
     */
    @Test
    void aTenantNamedLikeTheOldSentinelDoesNotCollideWithTheUntenantedBucket() {
        SkillEnvStore store = new SkillEnvStore();
        store.reset(oracleFor("untenanted-db"));
        store.resetTenant(" shared", oracleFor("impostor-db"));

        assertThat(store.get("oracle_query")).containsEntry("ORACLE_HOST", "untenanted-db");
        assertThat(store.get(" shared", "oracle_query")).containsEntry("ORACLE_HOST", "impostor-db");
    }

    @Test
    void syncingOneTenantDoesNotClearAnother() {
        SkillEnvStore store = new SkillEnvStore();
        store.resetTenant("tenant-cube", oracleFor("cube-db"));
        store.resetTenant("tenant-arena", oracleFor("arena-db"));

        // Re-sync of one tenant, returning fewer skills.
        store.resetTenant("tenant-cube", Map.of());

        assertThat(store.get("tenant-cube", "oracle_query")).isEmpty();
        assertThat(store.get("tenant-arena", "oracle_query")).containsEntry("ORACLE_HOST", "arena-db");
    }

    @Test
    void resyncEvictsSecretsForSkillsTheTenantNoLongerHas() {
        SkillEnvStore store = new SkillEnvStore();
        store.resetTenant("tenant-cube", Map.of(
                "oracle_query", Map.of("ORACLE_HOST", "cube-db"),
                "http_request", Map.of("API_TOKEN", "t")));

        store.resetTenant("tenant-cube", Map.of("oracle_query", Map.of("ORACLE_HOST", "cube-db")));

        assertThat(store.get("tenant-cube", "http_request")).isEmpty();
        assertThat(store.size("tenant-cube")).isEqualTo(1);
    }

    @Test
    void theNoTenantBucketIsSeparateFromEveryTenant() {
        SkillEnvStore store = new SkillEnvStore();
        store.reset(oracleFor("single-tenant-db"));
        store.resetTenant("tenant-cube", oracleFor("cube-db"));

        // A single-tenant deployment keeps working through the no-tenant bucket, and that bucket is
        // not a shared fallback that a tenant could read.
        assertThat(store.get("oracle_query")).containsEntry("ORACLE_HOST", "single-tenant-db");
        assertThat(store.get("tenant-cube", "oracle_query")).containsEntry("ORACLE_HOST", "cube-db");
    }

    @Test
    void blankAndNullTenantsAreTheSameBucket() {
        SkillEnvStore store = new SkillEnvStore();
        store.resetTenant(null, oracleFor("db"));

        assertThat(store.get("  ", "oracle_query")).containsEntry("ORACLE_HOST", "db");
        assertThat(store.get((String) null, "oracle_query")).containsEntry("ORACLE_HOST", "db");
    }

    @Test
    void evictingATenantRemovesItsCredentials() {
        SkillEnvStore store = new SkillEnvStore();
        store.resetTenant("tenant-cube", oracleFor("cube-db"));

        store.evictTenant("tenant-cube");

        assertThat(store.get("tenant-cube", "oracle_query")).isEmpty();
        assertThat(store.hasTenant("tenant-cube")).isFalse();
    }
}
