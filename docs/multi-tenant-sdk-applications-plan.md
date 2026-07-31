# Multi-tenant SDK applications — implementation plan

**Status:** for review — nothing implemented yet
**Author:** drafted 2026-07-29
**Spans:** `vortox/backend`, `vortox/frontend`, `vortox-agent-sdk/vortox-agent-sidecar`, `vortox-agent-sdk/vortox-agent-core`

---

## 1. Problem

One `vortox-agent-sidecar` container serves an embedding application (TNAM) that is itself
multi-tenant. Today the container holds a single `vsk_` SDK key that belongs to exactly one Vortox
tenant, so every institution's chat resolves to that one tenant: wrong secrets, wrong agent config,
wrong usage attribution, and no way to enable or disable the assistant per institution.

Running one sidecar per institution is out of scope — the deployment model can't support it.

### What already works in our favour

`apps/tnam/web/jar/src/main/java/com/secutix/web/controller/rest/TnamChatContextEnricher.java:31-33`
already injects, **server-side inside TNAM's authenticated session**:

```java
ctx.put("authenticatedUser", ContextProviderFactory.getContextProvider().getCurrentUserName());
ctx.put("institutionCode",   ContextProviderFactory.getContextProvider().getCurrentInstitutionCode());
ctx.put("application",       "TNAM");
```

It reaches the sidecar through TNAM's own proxy (`VortoxChatProxyFactory`,
`DEFAULT_SIDECAR_URL = http://localhost:7862`), so those three keys are not browser-forgeable. Today
`AgentController.chat` inlines them into the prompt as `## Current Page Context` and nothing
authenticates or authorises on them. **The plan is to promote this from prompt decoration to a
first-class, verified request scope.**

---

## 2. Settled decisions

| Decision | Outcome |
|---|---|
| Sidecar topology | One pooled container serving all institutions |
| SDK key semantics | **Authenticator only** — identifies the deployment, never the tenant |
| Business logic subject | `(tenant × application)` — secrets, agent config, skills, quotas, tracking |
| Key ownership | Platform-level, created by super admin, per application type |
| Default enablement | Applications active for all tenants by default |
| Tenant view | Application + usage, **without** the key |
| Skills in the sidecar | **Per-tenant on disk**, strict isolation, no shared fallback |

The key stays the sole identifier. The institution code is a *scope selector* validated against
server-side registration — never a credential and never trusted as given.

---

## 3. Target model

### 3.1 Identity vs configuration

These must be separate, because "active by default for all tenants" is impossible if the row that
maps `CUBE → tenant` is also the row that enables it.

```
TenantExternalCode                    ← MANDATORY. The security boundary.
  application_type   "TNAM"
  external_code      "CUBE"
  tenant_id          → tenant.tenant_id
  UNIQUE (application_type, external_code)

SdkApplicationTenantBinding           ← OPTIONAL. Overrides only; absent row ⇒ active with defaults.
  sdk_application_id  → sdk_application.id
  tenant_id
  enabled             (default true)
  agent_id            nullable — per-tenant linked agent
  allowed_skills      nullable — narrows the app-level list
  rate_limit_rpm, daily_request_limit, monthly_token_limit   nullable — fall back to the app
  created_at, last_used_at
  UNIQUE (sdk_application_id, tenant_id)
```

The `UNIQUE (application_type, external_code)` constraint is what stops two tenants claiming `CUBE`.
`Tenant` already has a `metadata jsonb` column that would tempt us to store the code there — don't:
it loses the uniqueness constraint that is doing the security work.

Quotas must move to the binding. Today `rate_limit_rpm` / `daily_request_limit` /
`monthly_token_limit` live on `SdkApplication`, so under one shared key a single institution's
traffic would throttle every other institution on the deployment.

### 3.2 Request resolution

```
X-Vortox-Api-Key: vsk_…                 ← identifies the deployment
X-Vortox-Tenant-Code: CUBE              ← NEW: selects the scope
X-Vortox-Application: TNAM              ← NEW: application type
X-Sdk-Instance-Id: …                    ← unchanged

  1. key → SdkApplication  (findByConnectionTokenAndActiveTrue)
  2. app.tenantId != null  → LEGACY single-tenant app: TenantContext.set(app.tenantId); done
  3. app.tenantId == null  → PLATFORM app:
       a. tenant code header REQUIRED, else 403
       b. TenantExternalCode(applicationType, code) → tenantId, else 403
       c. binding(app, tenantId).enabled == false → 403
       d. TenantContext.set(tenantId); stash binding as request attribute
  4. quota check against binding, falling back to app
```

Step 2 is what makes this incremental: **every existing tenant-owned key keeps working unchanged.**

---

## 4. Phases

Phases 1–3 are load-bearing. Phases 4–5 are the bulk of the sidecar work.

### Phase 1 — Schema and entities (backend) — ✅ DONE

Flyway is enabled (`baseline-version: 25`, `out-of-order: true`) *and* `ddl-auto: update` is on.
Migrations carry the constraints; the entities declare the same `@UniqueConstraint`s so both paths
agree.

| File | Change |
|---|---|
| `db/migration/V95__sdk_application_types.sql` | `sdk_applications.application_type`, `tenant_id` explicitly nullable, index on type |
| `db/migration/V96__tenant_external_codes.sql` | `tenant_external_codes` + `UNIQUE (application_type, external_code)` |
| `db/migration/V97__sdk_application_tenant_bindings.sql` | `sdk_application_tenant_bindings` + `UNIQUE (sdk_application_id, tenant_id)`, FK cascade |
| `entity/SdkApplication.java` | added `applicationType` + `isPlatformLevel()`; documented the nullable tenant |
| `entity/TenantExternalCode.java` | **new** — deliberately *not* `TenantAware` |
| `entity/SdkApplicationTenantBinding.java` | **new** — deliberately *not* `TenantAware`, `enabled` defaults true |
| `repository/TenantExternalCodeRepository.java` | **new** |
| `repository/SdkApplicationTenantBindingRepository.java` | **new** |
| `test/entity/PlatformLevelSdkApplicationTest.java` | **new** — 6 tests |

No behaviour change. Verified: 801 backend tests pass, 0 failures.

**Two corrections found while implementing:**

- Table names are **plural** (`sdk_applications`, `sdk_runs`, `tenants`, `skills`). Earlier drafts of
  this plan used singular names; the migrations use the real ones.
- The `TenantEntityListener` concern is **resolved, no workaround needed.** The listener already
  skips stamping when `TenantContext` is empty — its javadoc names this case explicitly
  ("SUPER_ADMIN — record remains globally visible"). `SecretService.upsertShared` needs its
  force-null workaround only because it runs *with* an ambient tenant. A super admin creating a
  platform application has none, so `tenant_id` stays null naturally.
  `PlatformLevelSdkApplicationTest` pins this, since a silent change to the listener would make
  platform keys quietly acquire their creator's tenant and collapse the design back to
  single-tenant with no visible error.

**Deferred to Phase 2:** the `UNIQUE (application_type, external_code)` constraint is enforced by
the database, which the current unit-test suite can't exercise (only one `@SpringBootTest` exists;
Testcontainers is available but unused for this). Add an explicit service-layer duplicate check with
a clear error when the registration endpoint lands, and unit-test that.

### Phase 2 — Super-admin application types and key issuance

| File | Change |
|---|---|
| `controller/ApplicationTypeController.java` | **new**, super-admin only — CRUD application types; issue/rotate platform `vsk_` keys |
| `controller/SdkApplicationController.java` | `create` currently stamps `tenantId = TenantContext.get()` (line 63-65). Keep that path for tenant-owned apps; add the platform path that leaves `tenantId` null |
| `controller/TenantExternalCodeController.java` | **new**, super-admin only — register `(applicationType, code) → tenant` |
| `service/SdkApplicationService.java` | `listForTenant` currently filters by `tenantId`; add resolution of platform apps visible to a tenant via bindings |
| `frontend/src/pages/admin/ApplicationTypesPage.jsx` | **new** — super-admin screen (siblings: `CreateTenantPage.jsx`, `TenantDetailPage.jsx`) |
| `frontend/src/pages/admin/TenantDetailPage.jsx` | add external-code registration + per-application enable/disable |
| `frontend/src/App.js` | route registration |

### Phase 3 — Tenant-resolving `SdkApiKeyFilter` (fail closed) — ✅ DONE

| File | Change |
|---|---|
| `security/SdkApiKeyFilter.java` | Resolves the tenant per §3.2, sets/clears `TenantContext` around the chain, exposes `SDK_TENANT_ATTR` / `SDK_BINDING_ATTR`, adds `rejectForbidden(code, message)` |
| `entity/SdkApplication.java` | `isPlatformLevel()` redefined — see below |
| `controller/SdkGatewayController.java` | `resolveAgentId(...)` prefers the tenant's binding agent over the application's; `toAgentConfig(...)` extracted from the block duplicated across `/config` and `/instances/register` |
| `test/security/SdkApiKeyFilterTenantResolutionTest.java` | **new** — 11 tests |
| `test/controller/{User,Tenant}ControllerTest.java` | `@MockBean`s for the filter's two new repositories (`@WebMvcTest` loads the security filters) |

Verified: 812 backend tests pass, 0 failures.

**Security decision — the application type is never taken from a header.** It comes from the
authenticated `SdkApplication` row. The same external code may map to different tenants under
different application types, so honouring a caller-supplied type would let one deployment select
another product's mapping and reach a tenant it was never granted. `X-Vortox-Application` is
therefore ignored server-side; a test pins that.

**Backward-compatibility fix found by the existing suite.** `isPlatformLevel()` was first written as
`tenantId == null`, which broke `SdkApiKeyFilterTest.rpmWindows_isolatedPerApp`. That was a real
signal, not a fixture problem: a super admin creating an SDK application *today* already produces a
`tenant_id = NULL` row, because `TenantEntityListener` skips stamping without an ambient tenant.
Those keys would have started returning 403. Multi-tenancy is now opted into explicitly:

```java
tenantId == null && applicationType != null && !applicationType.isBlank()
```

An untyped, tenantless key keeps running unscoped exactly as before, with a warning logged so the
rows are findable.

**Quotas deliberately unchanged in this phase.** A per-tenant *limit* checked against an
application-wide *count* would be worse than today's behaviour, so limits and counts move together
in Phase 6 once `sdk_runs.tenant_id` exists. The filter carries a comment saying so.

This phase fixes the tenant-derivation gap behind the GLOBAL-secrets bug (§Phase 7).

**Fail-closed rules — these are the whole point of the phase:**

- Platform-level key with **no** tenant code header → `403`, never "no tenant / no filter".
- Tenant code with no `TenantExternalCode` row → `403`, never string-matched against `tenant.tenant_id`.
- Binding `enabled = false` → `403` with a distinguishable body so the widget can say something useful.
- `SecretService.resolveAll` must never reach its unfiltered `findByScope(GLOBAL)` branch
  (`SecretService.java:171`) on an SDK request.

### Phase 4 — Two-tier skills in the sidecar

Vortox **already** models the two tiers we need. `entity/Skill.java:25`:

```java
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId OR tenant_id IS NULL")
```

- `tenant_id IS NULL` → **shared** skill, visible to every tenant. All 10 skills in the current TNAM
  deployment are this tier — they are the product toolkit, not institution-specific.
- `tenant_id` non-null → **tenant-private**, created by that tenant (per the entity comment, via
  Brain chat's `CreateSkillTool`).

`skillRegistry.getAllSkills(TenantContext.get())` already returns *shared ∪ this tenant's own*; it
only needs the tenant to actually be set, which Phase 3 delivers. **No scope field to add and no
change to the Vortox skill model.** The gap is that the sidecar flattens both tiers into one shared
directory.

Sidecar disk layout mirrors the two tiers — two top-level buckets, so a tenant id can never collide
with the shared directory name:

```
/app/skills/shared/<name>/SKILL.md
/app/skills/tenants/<tenantId>/<name>/SKILL.md
```

Resolution for tenant T is `shared ∪ tenants/T`. The shared tier is the application's own code —
equivalent to being baked into the image — so this satisfies "never access another tenant's skills"
as stated.

**Name collisions need an explicit precedence rule.** The filter admits *both* rows when a tenant
defines a skill named like a shared one, so a registry keyed by name picks whichever the result
ordering yields last — non-deterministic, and true today independent of multi-tenancy.
Recommendation: allow the shadow (customising a standard skill is legitimate), define **tenant
wins**, log it, and surface it in the tenant view.

**Status: ✅ DONE** — sidecar 48 tests pass, backend 824, both suites green.

| File | Change |
|---|---|
| `core/gateway/VortoxGateway.java` | `TENANT_CODE_HEADER` sent per call; `fetchSkills(tenantCode)` and `getForTenant(...)`. Tenant is a per-call parameter, never constructor state |
| `controller/SdkGatewayController.java` | `/skills/sync` now returns `shared: true\|false` from `tenant_id IS NULL` |
| `sidecar/skill/SkillRegistry.java` | Two-tier: shared map + `Map<tenantId, Map<name, …>>`; `findFor`/`subsetFor`/`allFor`/`countFor`, `replaceTenantTier`, `sanitizeSegment` |
| `sidecar/skill/SkillEnvStore.java` | Keyed by tenant; `resetTenant` replaces one tenant's values only |
| `sidecar/skill/ScriptToolExecutor.java` | `forTenant(tenantId)`; workspace `<root>/<tenant>/<runId>`; reaper walks one level deeper |
| `sidecar/skill/VortoxSkillSyncService.java` | Shared tier eager; tenant tiers lazy via `ensureTenantSynced`, refreshed on the hourly poll; per-tenant webhook scope |
| `sidecar/api/{AgentRunRequest,AgentChatRequest}.java` | Structured `tenantCode`; `AgentRunRequest` keeps an 8-arg overload for single-tenant callers |
| `sidecar/api/AgentController.java`, `service/AgentService.java` | Tenant resolved once at the boundary, then passed explicitly; tenant-bound executor built per run |
| `sidecar/api/VortoxSkillWebhookController.java` | Optional `tenantCode` in the reload body |

**Design notes from implementation:**

- **Shared skills keep the flat layout** (`<skillsPath>/<name>/`), with only tenant-private skills
  under the reserved `tenants/<tenantId>/`. The skills directory is a host bind-mount in real
  deployments, so relocating existing directories would strand them. The running `tnam-agent` keeps
  working untouched.
- **No-tenant overloads mean *shared only*, never "everything".** A caller that forgets to pass a
  tenant gets a missing-skill bug, never another tenant's body. Pinned by a test.
- **`forTenant` is the structural guarantee.** `ToolExecutor.execute` has no tenant parameter, so
  once a run holds a tenant-bound executor neither it nor `ReactLoop` can name a different tenant.
  The tenant is a captured value, not ambient state — a `ThreadLocal` would leak across
  `ChatRunService`'s worker pool.
- **`shared` absent in the sync response is treated as shared**, so an older backend keeps the
  previous single-tier behaviour rather than filing everything under one tenant.
- **Phase 5's env-store change landed here**, not separately: per-tenant sync calling the old global
  `reset()` would have wiped every other tenant's secrets. The two were one change.
| `sidecar/skill/ScriptToolExecutor.java` | Becomes **tenant-bound per run** (see §5). Workspace root becomes `<root>/<tenantId>/<runId>` |
| `sidecar/api/AgentChatRequest.java` | Add structured `tenantCode`, `applicationType`, `authenticatedUser` (stop relying on the free-text `context` map) |
| `sidecar/api/AgentRunRequest.java` | Carry tenant explicitly |
| `sidecar/api/AgentController.java` | Resolve tenant once at the boundary; reject requests without one |
| `sidecar/api/VortoxSkillWebhookController.java` | Accept a tenant in the reload signal so a single tenant's change doesn't force a full resync |
| `core/chatproxy/ChatContextEnricher.java`, `legacyproxy/ChatContextEnricher.java` | Extend the SPI so TNAM's enricher sets the structured fields, not just `ctx` entries |

**Isolation rules:**

1. **No global fallback, ever.** An unknown skill for tenant A is an error — never a lookup in a
   shared map or another tenant's map.
2. Skill bodies are only ever read from the run's own tenant directory.
3. Path confinement (already implemented) extends to `<root>/<tenantId>/<runId>`, so a skill cannot
   reach another tenant's workspace even with an absolute path.

### Phase 5 — Per-tenant secrets and config caches in the sidecar

Both caches are single-valued today. Left as-is, CUBE's Oracle password is injected into another
institution's skill run. **This is the highest-severity item in the plan.**

This phase is **unconditional and independent of Phase 4's tiering**: `syncSkills` resolves
`envVars` from the requesting tenant's secrets, so a *shared* `oracle_query` body still runs with
different `ORACLE_*` credentials per institution. The credentials are the isolation boundary; the
skill bodies mostly are not. Phases 4 and 5 should therefore not ship separately.

**Status: ✅ DONE** — sidecar 51 tests pass. The `SkillEnvStore` half shipped with Phase 4 (see above);
the agent-config half is below.

| File | Change |
|---|---|
| `sidecar/skill/SkillEnvStore.java` | Keyed by tenant — **landed with Phase 4**, since per-tenant sync could not be done safely without it |
| `sidecar/service/AnthropicKeyRefreshService.java` | `cachedAgentConfig` single `AtomicReference` → `Map<tenantCode, config>`, lazily populated on first sight, all known tenants refreshed on the 60s poll |
| `sidecar/service/AgentService.java` | Resolves `agentConfig` for the request's tenant |
| `sidecar/api/AgentController.java` | `/link-status?tenantCode=…` answers per tenant |
| `test/api/AgentControllerLinkStatusTenantTest.java` | **new** — 3 tests |

**Design notes:**

- **The platform Anthropic key stays global**; only the untenanted refresh maintains it, so a
  per-tenant fetch cannot clear it as a side effect. The *agent's own* API key comes from
  `agentConfig` and is therefore per tenant.
- **A `NO_AGENT` sentinel** distinguishes "fetched, this tenant has no linked agent" from "never
  fetched", so a tenant without an agent isn't refetched on every message.
- **A 4xx for one tenant evicts that tenant's cached config** rather than leaving it running on a
  stale one — its access may have just been disabled in Vortox.
- **No fallback to another tenant or to the no-tenant bucket.** Borrowing a config would mean the
  wrong model, the wrong system prompt and the wrong agent API key, all silently.

Cache invalidation: `/config` polls every 60s, so flipping `enabled = false` takes up to a minute to
bite. Either accept that lag or push it like the existing skills-reload webhook.

### Phase 6 — Per-tenant usage tracking

`SdkRun` has `runId`, `taskId`, `agentId`, `instanceId`, `applicationName`, `task`, `model`,
`status`, `result`, `inputTokens`, `outputTokens`, `startedAt` — **no `tenantId`** — and every
aggregate in `SdkRunRepository` is `ByAppId`.

**Status: ✅ DONE** — backend 829 tests, sidecar 51, frontend suites green.

| File | Change |
|---|---|
| `db/migration/V98__sdk_run_tenant.sql` | `sdk_runs.tenant_id` (nullable) + index on `(app_id, tenant_id, started_at)` |
| `entity/SdkRun.java` | added `tenantId` |
| `repository/SdkRunRepository.java` | `(appId, tenantId)` variants of all four aggregates |
| `controller/SdkGatewayController.java` | `POST /runs` stamps the tenant from the resolved request, never the body; `mayAccess(...)` guards `updateRun`/`getRun` |
| `security/SdkApiKeyFilter.java` | Limits from binding-then-application; counts tenant-scoped; RPM windows keyed `appId\|tenantId` |
| `controller/SdkApplicationController.java` | `tenantUsage(...)` on `/available` |
| `core/gateway/VortoxGateway.java` | Tenant header on `post`/`put`, so run create/update carry it |
| `service/AgentService.java` | Reports the resolved agent id instead of the literal `"sidecar-agent"` |
| `frontend/src/pages/SdkApplications.jsx` | Per-tenant usage on the provisioned-applications panel |
| `test/security/SdkApiKeyFilterTenantQuotaTest.java` | **new** — 5 tests |

**A cross-tenant access bug found and fixed here.** `updateRun` and `getRun` authorised on
`appId` alone. That was correct while a key belonged to one tenant, but on a pooled application every
tenant shares the `appId` — so one institution could read and overwrite another's runs by id.
`mayAccess(...)` now also requires the tenant to match. Runs with a null tenant (predating attribution,
or from a key with no tenant dimension) are still checked on the application only, so an upgrade
cannot orphan runs that are in flight.

**Quotas, deferred from Phase 3, landed properly:** limit *and* count move together. A tenant's
binding limit wins over the application's; counts and RPM windows are per `(application, tenant)` for
pooled applications and stay application-wide for legacy keys. Pinned by tests on both paths.

**`sdk_runs.tenant_id` is nullable with no backfill.** There is nothing to backfill it from — the
tenant was implicit in the key, and deriving it from `sdk_applications` would be a guess for any key
since re-pointed. Untenanted rows keep counting toward application-wide totals, as they always did.

### Phase 7 — GLOBAL secrets for unlinked applications — ✅ DONE

**Status:** backend 834 tests pass. This is the fix for the symptom that started the whole thread —
`ORACLE_HOST ❌ Not configured` on an unlinked sidecar.

| File | Change |
|---|---|
| `service/SecretService.java` | New `resolveAll(agentId, projectId, explicitTenantId)`; the two-argument form delegates and is unchanged |
| `controller/SdkGatewayController.java` | `syncSkills` degrades to the GLOBAL tier when no agent is linked, using the request tenant; fails closed with a warning when neither exists |
| `test/service/SecretServiceTenantScopingTest.java` | **new** — 5 tests |

**The tenant is passed explicitly rather than derived**, which differs from what this plan first
proposed ("`resolveTenantId` gains the request tenant as its first source"). Making the ambient
context the first source would still leave the failure mode intact: derivation's last resort *is*
`TenantContext`, and when that is empty too, the GLOBAL lookup falls through to an unfiltered
`findByScope(GLOBAL)` across every tenant. Naming the tenant makes correctness independent of
thread-local state, and an added overload leaves every existing caller untouched.

**Fail-closed at the call site rather than inside `resolveAll`.** With no agent *and* no tenant there
is nothing to scope by, so `syncSkills` sends no secrets and logs it. `resolveAll`'s own behaviour is
deliberately left alone: tightening it would change semantics for the backend's async skill
executors, which is a separate change with its own blast radius.

---

## 5. Cross-cutting rule: how the tenant travels

**Never via `ThreadLocal`.** `ChatRunService` hands every run to a `chat-run-worker` thread pool, so
an ambient tenant would leak across tenants exactly as `TenantContext` already fails on the
backend's `@Async("skillExecutor")` pool — the documented reason `SecretService.resolveTenantId`
derives the tenant from the agent rather than trusting ambient context.

Resolve the tenant **once** at the controller boundary and pass it explicitly:

```
AgentController → AgentRunRequest → ChatRunService → AgentService → ScriptToolExecutor
```

`ToolExecutor.execute(toolName, params, runId)` has no tenant slot
(`vortox-agent-core/.../spi/ToolExecutor.java:27`). Rather than change the core SPI, stop injecting
`ScriptToolExecutor` as a singleton `@Component` and have `AgentService` construct a
**tenant-bound instance per run** via a factory holding that tenant's registry and env store.

A run then physically holds no reference to any other tenant's skills or secrets — cross-tenant
access stops being something we check for at runtime and becomes something the object graph cannot
express. Keep a defensive assertion as well, but the structure is the guarantee.

---

## 6. Test plan

Security tests come first; they are the deliverable, not an afterthought.

**Backend**
- Platform key with no tenant header → 403 (not "no tenant").
- Unregistered tenant code → 403; specifically, a code equal to a real `tenant.tenant_id` but with
  no `TenantExternalCode` row is still rejected.
- Two `TenantExternalCode` rows with the same `(applicationType, code)` → constraint violation.
- Binding `enabled = false` → 403, distinguishable body.
- Legacy tenant-owned key → unchanged behaviour, no headers needed (regression).
- `resolveAll` never reaches unfiltered `findByScope(GLOBAL)` on an SDK request.
- Quota exhaustion for tenant A does not throttle tenant B on the same key.
- Usage aggregates split by `(appId, tenantId)`.

**Sidecar**
- Tenant A's run cannot resolve a skill defined only for tenant B (no fallback).
- Tenant A's run cannot read tenant B's workspace by absolute path (extends the existing
  `ScriptToolExecutorWorkspaceTest`).
- `SkillEnvStore` returns tenant A's env for tenant A and nothing for tenant B.
- One tenant's `/skills/sync` does not clear another tenant's env or skills.
- Concurrent runs for different tenants on the `chat-run-worker` pool keep their own tenant —
  the test that would have caught a `ThreadLocal` implementation.
- Sidecar with no tenant on the request fails closed.

---

## 7. Backward compatibility and rollout

- Existing tenant-owned `vsk_` keys keep working through the `tenantId != null` branch. No flag day.
- Phase 1 is inert; Phases 2–3 are additive behind a super-admin screen.
- **Rollout hazard:** a pooled sidecar running *old* code with a *new* platform key sends no tenant
  header. The backend must 403 rather than degrade. Write that test before the feature.
- Deploy order: backend (1→3) before sidecar (4→5), so the sidecar's new headers have somewhere to
  land. The reverse order fails closed, which is the correct failure direction.

---

## 8. Open questions

1. **Enable/disable ownership.** Recommendation: writes in super admin only, tenant view read-only.
   Tenant self-service disable is easy to add later and hard to take back.
2. **~~Skill bodies per tenant vs per application.~~** *Resolved:* two tiers, mirroring the
   `tenant_id IS NULL` / non-null split `Skill` already implements. See Phase 4. Remaining sub-decision:
   confirm **tenant-wins** precedence when a tenant shadows a shared skill name, versus rejecting the
   name at definition time.
3. **Multiple external codes per tenant.** Schema allows it (`UNIQUE` is on
   `(applicationType, code)`, not on `tenantId`). Confirm that's wanted.
4. **`authenticatedUser` use.** Currently prompt context only. Should it scope anything — memory,
   audit, per-user quotas — or stay informational?
5. **Session concept.** A user-initiated "new session" that clears history and resets the workspace
   was agreed in principle. It should nest under the tenant (`<root>/<tenant>/<session>`), so it is
   sequenced after Phase 4 rather than built on the current per-`runId` scoping.

---

## 9. Out of scope — tracked separately

- Sidecar container runs as **root** (no `USER` in `vortox-agent-sidecar/Dockerfile`) and `./skills`
  is mounted read-write. Recommend a non-root user and a `:ro` skills mount as defence in depth for
  skills that build paths internally rather than from parameters.
- `tools-local/agent-sidecar/.env` holds a live `sk-ant-oat01-…` and `vsk_…` in plaintext — rotate
  if that file has ever been shared or committed.
- Token exchange (deployment key → short-lived per-tenant token, RFC 8693-style) as a later
  hardening. It layers on top of this model without changing the API shape.
- `AgentController.chat` hardcodes `maxIterations = 75` (line 175), so a linked agent's own
  `maxIterations` never applies to widget chats.

---

## 10. Already delivered (2026-07-29)

Prerequisite work, merged and verified against the running `tnam-agent` container:

- Per-run workspace confinement in `ScriptToolExecutor` — `<root>/<runId>`, path-valued parameters
  resolved and rejected if they escape, `../` and symlink escapes closed, TTL reaper that only
  touches runId-shaped directories.
- `execute_command` disabled by default in the sidecar (`sidecar.enable-execute-command`).
  `AgentConfig.Builder` defaults it to `true` and `AgentService` never overrode it, so the agent had
  an unconfined root shell that bypassed `ScriptToolExecutor` entirely.
- Verified live: `file_read /proc/1/environ` → rejected; `file_write report.csv` → lands in the run's
  own workspace.

These are what make per-tenant filesystem isolation in Phase 4 meaningful rather than cosmetic.
