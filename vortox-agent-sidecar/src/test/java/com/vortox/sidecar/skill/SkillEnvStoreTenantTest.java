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
