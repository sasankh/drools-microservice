# Pre-compiled kjar Deployment — Implementation Checklist

**Status:** Not started
**Plan:** [`kjar-precompilation-plan.md`](kjar-precompilation-plan.md)
**Branch:** TBD (suggest `feature/kjar-deployment`)
**Target effort:** ~10 dev-days
**Target calendar time:** 3–4 weeks including stage soak

---

## Phase 0 — Pre-flight & proof-of-concept

Goal: prove `kie-maven-plugin` 10.2.0 works before committing to the full plan.

- [ ] Create scratch directory `/tmp/kjar-poc/`
- [ ] Build a minimal `pom.xml` with `<packaging>kjar</packaging>` and `kie-maven-plugin` 10.2.0
- [ ] Add `META-INF/kmodule.xml` with a single `<kbase name="rules" packages="*">`
- [ ] Add 1 trivial `.drl` file under `src/main/resources/rules/`
- [ ] Run `mvn -B clean package` — verify it produces `target/<artifact>-<version>.jar`
- [ ] Verify the jar contains compiled `.class` files (not just `.drl`) under the expected layout
- [ ] Write a 10-line Java program that loads the kjar via `KieServices.newKieContainer(ReleaseId)` and fires a session — verify rules execute
- [ ] **Decision gate:** if any of the above fails, escalate. Document fallback (manual `KieBuilder` via `exec-maven-plugin`).
- [ ] Document POC findings in `kjar-poc-findings.md` (this file or a new one)

---

## Phase 1 — New `rules-kjar` Maven module

Goal: integrate the kjar build into the project as a sibling Maven module.

### 1.1 Module skeleton

- [ ] Decide branch name and create it: `git checkout -b feature/kjar-deployment`
- [ ] Create `rules-kjar/` directory
- [ ] Create `rules-kjar/pom.xml` per plan section 3.3
- [ ] Create `rules-kjar/src/main/resources/META-INF/kmodule.xml` per plan section 3.4
- [ ] Copy all 17 files from `sample-rules/` to `rules-kjar/src/main/resources/rules/`
- [ ] Convert root `pom.xml` to multi-module reactor: add `<modules><module>rules-kjar</module><module>.</module></modules>` (or split into parent + service module — TBD during implementation)
- [ ] Update root `<packaging>` if needed (parent should be `pom`)

### 1.2 Build verification

- [ ] `mvn -B clean install` from project root — verify both modules build
- [ ] Verify `rules-kjar/target/rules-kjar-1.0.0-SNAPSHOT.jar` exists
- [ ] Inspect the jar: `unzip -l rules-kjar/target/*.jar` — must contain `META-INF/kmodule.xml`, compiled rule `.class` files, and the original `.drl` files
- [ ] Verify all 17 rules compile cleanly with `failBuildOnError=true`

### 1.3 Unit test loading

- [ ] Add a test module or test class in `rules-kjar/src/test/java/`
- [ ] Write a test that:
  - [ ] Resolves the just-built kjar from local Maven repo via `ReleaseId`
  - [ ] Loads it with `KieServices.get().newKieContainer(releaseId)`
  - [ ] Executes 1 rule (e.g. `pricing.discount.simple`)
  - [ ] Asserts expected output
- [ ] `mvn test` from `rules-kjar/` passes

### 1.4 Phase 1 gate

- [ ] `mvn clean install` builds clean
- [ ] 17 rules compile into kjar
- [ ] Unit test demonstrates kjar loads and executes
- [ ] `git commit` — "feat(kjar): Phase 1 — new rules-kjar module with kie-maven-plugin"

---

## Phase 2 — `KjarRuleStorage` + conditional `DroolsConfig`

Goal: service can run with `RULE_SOURCE=kjar` and load rules from a kjar binary.

### 2.1 KjarRuleStorage implementation

- [ ] Create `src/main/java/com/company/drools/storage/KjarRuleStorage.java`
- [ ] Implement `RuleStorage` interface
- [ ] Constructor injection: `S3Client`, `KieServices`, config (`kjar.bucket`, `kjar.key-prefix`, `kjar.version`)
- [ ] `loadLatestKjar()` method per plan section 5.1
- [ ] `loadSpecificKjar(String version)` for explicit-version requests (used by rollback)
- [ ] `readVersionPointer()` — `GET s3://bucket/<prefix>LATEST.version`
- [ ] `downloadKjar(version)` — `GET s3://bucket/<prefix>rules-kjar-<version>.jar` → byte array
- [ ] `rebuildMetadataCache(KieModule)` — populate `ruleMetadataCache` from kjar resource listing
- [ ] Implement `getRule`, `getAllRules`, `ruleExists`, `getTotalRuleCount`, `getRuleIds` from metadata cache
- [ ] `saveRule` / `deleteRule` throw `UnsupportedOperationException`
- [ ] `@ConditionalOnProperty(name = "rule.source", havingValue = "kjar")` on the class
- [ ] Logging: rule count, version loaded, download size, total time

### 2.2 DroolsEngineService.loadFromKjar()

- [ ] Add `loadFromKjar(KieModule kieModule)` method per plan section 5.2
- [ ] Acquire `writeLock` (same lock as `loadRules` and `loadOrReplaceRule`)
- [ ] Call `kieContainer.updateToVersion(newReleaseId)`
- [ ] Check `Results` for errors; throw if any
- [ ] Remove old `KieModule` via `kieRepository.removeKieModule(oldReleaseId)` (outside lock per existing pattern, line 224)
- [ ] Update `loadedRules` map from kjar metadata
- [ ] Update `ruleMetadata` map
- [ ] Log: old version → new version, swap duration

### 2.3 DroolsConfig conditional beans

- [ ] Modify `src/main/java/com/company/drools/config/DroolsConfig.java`
- [ ] Add `kjarKieContainer(KieServices, KjarRuleStorage)` bean with `@ConditionalOnProperty(name = "rule.source", havingValue = "kjar")`
- [ ] Annotate existing `kieContainer()` bean with `@ConditionalOnProperty(name = "rule.source", havingValue = "s3", matchIfMissing = true)`
- [ ] Ensure `KieServices` bean is shared by both
- [ ] Verify Spring boots cleanly with `RULE_SOURCE=s3` (regression check)
- [ ] Verify Spring boots cleanly with `RULE_SOURCE=kjar` and a kjar in LocalStack

### 2.4 application.yml updates

- [ ] Add to `src/main/resources/application.yml`:
  ```yaml
  rule:
    source: ${RULE_SOURCE:s3}
  kjar:
    bucket: ${KJAR_BUCKET:drools-rules}
    key-prefix: ${KJAR_KEY_PREFIX:kjars/}
    version: ${KJAR_VERSION:LATEST}
    poll-interval-seconds: ${KJAR_POLL_INTERVAL_S:0}
  ```
- [ ] Add to `.env.example`
- [ ] Update profile-specific yml fragments if needed (dev/docker/prod)

### 2.5 StorageFactory update

- [ ] Modify `src/main/java/com/company/drools/storage/StorageFactory.java`
- [ ] Add `kjar` case returning the `KjarRuleStorage` bean
- [ ] Update tests for StorageFactory

### 2.6 AdminController adaptation

- [ ] Verify `/admin/rules` still works in kjar mode (uses `RuleStorage.getAllRules()`)
- [ ] Verify `/admin/refresh-rules` calls `KjarRuleStorage.loadLatestKjar()` → `DroolsEngineService.loadFromKjar()`
- [ ] Add support for `POST /admin/refresh-rules` body with `{"version":"..."}` — calls `loadSpecificKjar(version)` for rollback
- [ ] `POST /admin/refresh-rules/{ruleId}` returns 405 or 400 in kjar mode (with helpful error message)

### 2.7 Phase 2 gate

- [ ] All existing 597 tests pass with `RULE_SOURCE=s3` (regression)
- [ ] Manual test: service starts with `RULE_SOURCE=kjar`, loads kjar from LocalStack, executes rules
- [ ] `mvn compile spotbugs:check` clean
- [ ] `git commit` — "feat(kjar): Phase 2 — KjarRuleStorage and conditional DroolsConfig"

---

## Phase 3 — CI pipeline

Goal: pushing to `main` builds and publishes a new kjar version.

### 3.1 GitHub Actions workflow

- [ ] Create `.github/workflows/build-rules-kjar.yml` per plan section 4.1
- [ ] Trigger: `push` on `main` paths `rules-kjar/**` + `workflow_dispatch`
- [ ] Steps: checkout → setup Java 25 → generate timestamp version → `mvn package` → AWS OIDC auth → S3 upload → notify service
- [ ] Configure GitHub secrets: `AWS_ROLE_ARN`, `ADMIN_API_KEY_STAGE`, `ADMIN_API_KEY_PROD`
- [ ] Configure GitHub vars: `RULE_BUCKET_NAME`, `STAGE_URL`, `PROD_URL`

### 3.2 Versioning + S3 layout

- [ ] Decide on retention: keep last 30 kjars in S3, lifecycle-delete older
- [ ] Add lifecycle policy (Terraform/CloudFormation/manual)
- [ ] Verify `LATEST.jar` and `LATEST.version` get updated atomically (upload version.txt LAST)

### 3.3 IAM role

- [ ] Create AWS IAM role for GitHub Actions OIDC
- [ ] Permissions: `s3:PutObject` on `s3://drools-rules/kjars/*`, `s3:ListBucket` on bucket
- [ ] Trust policy: GitHub OIDC, restrict to specific repo + branch
- [ ] Add role ARN to GitHub secret `AWS_ROLE_ARN`

### 3.4 First end-to-end CI run

- [ ] Push a trivial change to `rules-kjar/`
- [ ] Verify workflow succeeds
- [ ] Verify a versioned kjar appears in `s3://drools-rules/kjars/`
- [ ] Verify `LATEST.jar` and `LATEST.version` are updated
- [ ] (Optional in stage) verify the service's `/admin/refresh-rules` was called

### 3.5 Phase 3 gate

- [ ] CI builds and uploads on push to `main`
- [ ] Manual `workflow_dispatch` works
- [ ] `git commit` — "ci(kjar): Phase 3 — GitHub Actions workflow for kjar build + S3 upload"

---

## Phase 4 — Integration tests

Goal: automated end-to-end coverage from LocalStack S3 to executed rules.

### 4.1 LocalStack-based integration test

- [ ] Create `src/test/java/com/company/drools/integration/KjarIntegrationTest.java`
- [ ] Test setup: `@SpringBootTest`, `RULE_SOURCE=kjar`, LocalStack TestContainers
- [ ] Before test: build a sample kjar with 17 rules, upload to LocalStack S3 at `kjars/rules-kjar-1.0.test.jar` and `kjars/LATEST.jar`, upload `LATEST.version` text file
- [ ] Test 1: service loads kjar at startup, all 17 rules listed via `/admin/rules`
- [ ] Test 2: `POST /execute-rule` succeeds for `pricing.discount.simple` with expected output
- [ ] Test 3: full refresh — replace kjar in S3 with v2, `POST /admin/refresh-rules`, verify new rule outputs
- [ ] Test 4: rollback — `POST /admin/refresh-rules` with `{"version":"1.0.test"}` reverts
- [ ] Test 5: kjar missing in S3 → service startup fails with clear error
- [ ] Test 6: `POST /admin/refresh-rules/{ruleId}` returns 405 with explanatory message

### 4.2 KjarRuleStorage unit tests

- [ ] Create `src/test/java/com/company/drools/storage/KjarRuleStorageTest.java`
- [ ] Mock `S3Client`, test version pointer read
- [ ] Test no-op when current version matches
- [ ] Test download + KieModule registration
- [ ] Test `getRule()` / `getAllRules()` return synthesised Rule objects
- [ ] Test `saveRule()` / `deleteRule()` throw `UnsupportedOperationException`
- [ ] Test S3 download failure → wraps as `RuleStorageException`

### 4.3 Cross-mode equivalence test

- [ ] Run all 17 sample rules through `s3` mode and capture results
- [ ] Run same 17 rules through `kjar` mode (loaded from a kjar built from same DRLs)
- [ ] Assert results are identical

### 4.4 Phase 4 gate

- [ ] All new tests pass
- [ ] All existing 597 tests pass
- [ ] `mvn clean verify` succeeds
- [ ] `git commit` — "test(kjar): Phase 4 — integration + unit tests for KjarRuleStorage"

---

## Phase 5 — Load test

Goal: prove cold start <30s and full refresh <30s at 10,000 rules.

### 5.1 10,000-rule corpus

- [ ] Extend `scripts/lib/corpus.sh` to optionally produce DRLs into `rules-kjar/src/main/resources/rules/` (overriding the 17 samples)
- [ ] Generate 10,000 synthetic DRLs covering the existing template patterns
- [ ] Build the kjar from this 10,000-rule corpus — measure CI time
- [ ] Upload to LocalStack S3

### 5.2 Add `--kjar-mode` to load test orchestrator

- [ ] Modify `scripts/run-load-test.sh` to support `--kjar-mode`
- [ ] In kjar mode: skip per-DRL S3 upload phase; instead build + upload the kjar
- [ ] In kjar mode: start app with `RULE_SOURCE=kjar`

### 5.3 Performance comparison

- [ ] Run `./scripts/run-load-test.sh --kjar-mode` end-to-end with 10,000 rules
- [ ] Phase 0–8 acceptance criteria:
  - [ ] Cold start (Phase 2): P99 < 30s — **was 8 min in s3 mode**
  - [ ] Full refresh: P99 < 30s — **was 8 min in s3 mode**
  - [ ] Phase 3 baseline P99 < 200ms (unchanged from s3)
  - [ ] Phase 7 soak heap drift < 100 MB
  - [ ] Zero errors in all phases
- [ ] Compare heap usage at rest: kjar mode vs s3 mode (target: within 2×)
- [ ] Compare execution latency P99: kjar mode vs s3 mode (target: identical)

### 5.4 Phase 5 gate

- [ ] Documented performance numbers in `scripts/load-test-results/<timestamp>/summary.md`
- [ ] No regressions vs s3 mode in execution latency
- [ ] Cold start and refresh meet targets
- [ ] `git commit` — "perf(kjar): Phase 5 — load test results — cold start 8min → 15s"

---

## Phase 6 — Documentation

### 6.1 New docs

- [ ] Create `project-documentation/40-kjar-deployment.md`:
  - [ ] Overview: why kjar, what changes, what doesn't
  - [ ] How to build a kjar locally
  - [ ] How CI builds and uploads
  - [ ] How to roll back to a previous version
  - [ ] How to switch between `s3` and `kjar` modes
  - [ ] Rule authoring workflow (Git PR → CI → S3)
  - [ ] Limitations (no single-rule refresh in pure kjar mode)
- [ ] Add ADR-015 in `project-documentation/36-architecture-decision-records.md`:
  - [ ] Title: "ADR-015: Pre-compiled kjar deployment for cold-start at scale"
  - [ ] Status: Accepted (date when merged)
  - [ ] Context: 8-min cold start at 10k rules
  - [ ] Decision: kjar artifact built in CI, loaded at runtime
  - [ ] Consequences: positive (cold start, auditability), negative (no hot-patch, CI dependency)
  - [ ] Alternatives considered: KieScanner+Maven repo, keep status quo

### 6.2 Updated docs

- [ ] `project-documentation/00-system-overview.md` — note kjar mode in architecture overview
- [ ] `project-documentation/04-architecture.md` — replace runtime-compile section with kjar+s3 dual-mode
- [ ] `project-documentation/26-performance-tuning-runbook.md` — new cold-start guidance referencing kjar
- [ ] `project-documentation/27-development-setup.md` — how to build and test the kjar locally
- [ ] `project-documentation/30-runbooks-and-monitoring.md` — kjar refresh runbook, rollback runbook
- [ ] `project-documentation/09-environment-variables-reference.md` — new `KJAR_*` vars
- [ ] `project-documentation/02-project-structure.md` — add `rules-kjar/` directory
- [ ] `README.md` — Quick Start callout for kjar mode in deployment section
- [ ] `CLAUDE.md` — note kjar in tech stack and Recent change log

### 6.3 Phase 6 gate

- [ ] All linked docs updated
- [ ] `grep` confirms no stale references to "runtime DRL compilation as primary path"
- [ ] `git commit` — "docs(kjar): Phase 6 — kjar deployment documentation + ADR-015"

---

## Phase 7 — Phased rollout

Goal: ship to prod with confidence.

### 7.1 Stage deployment

- [ ] Deploy service jar (latest main) to stage with `RULE_SOURCE=kjar`
- [ ] Verify stage loads the latest kjar from S3
- [ ] Smoke test: hit `/admin/health`, execute 5 sample rules
- [ ] Verify metrics: `drools.kjar.load.duration`, `drools.kjar.version.current`

### 7.2 Stage soak

- [ ] Run normal stage traffic for 1 week
- [ ] Monitor:
  - [ ] No new error patterns in logs
  - [ ] P99 latency within baseline
  - [ ] Heap usage stable
  - [ ] No incidents
- [ ] Trigger a rule change via CI → verify auto-deploy to stage succeeds
- [ ] Trigger a rollback (admin endpoint with previous version) → verify

### 7.3 Production deployment

- [ ] Schedule prod deploy window
- [ ] Pre-deploy: verify latest kjar exists in prod S3 bucket
- [ ] Deploy service jar to prod with `RULE_SOURCE=kjar`
- [ ] Roll out via ECS rolling deployment (one task at a time)
- [ ] Watch:
  - [ ] Cold start time per task (should be ~15s, not 8min)
  - [ ] Error rate
  - [ ] Latency

### 7.4 Post-deploy soak

- [ ] Monitor for 48 hours
- [ ] If clean, declare success
- [ ] Schedule deprecation of `RULE_SOURCE=s3` for one quarter out

### 7.5 Phase 7 gate

- [ ] Prod running on kjar mode for 1 week with no incidents
- [ ] Cold start time confirmed in prod metrics
- [ ] Rollback rehearsed in stage

---

## Phase 8 — Decommissioning (3 months post-launch)

- [ ] Remove dual-write from CI (stop uploading per-DRL files)
- [ ] Drop `RULE_SOURCE=s3` support (deprecate, then remove `S3RuleStorage` + per-DRL code paths)
- [ ] Update docs to remove dual-mode discussion
- [ ] Final cleanup commit

---

## Sign-offs (fill in as you go)

| Phase | Owner | Date completed | Notes |
|---|---|---|---|
| 0. POC | | | |
| 1. Module | | | |
| 2. Storage + Config | | | |
| 3. CI | | | |
| 4. Tests | | | |
| 5. Load test | | | |
| 6. Docs | | | |
| 7. Rollout | | | |
| 8. Decom | | | (target +3 months) |

---

## Risk log (update as risks emerge)

| Date | Risk | Severity | Mitigation | Status |
|---|---|---|---|---|
| 2026-05-11 | `kie-maven-plugin` 10.x stability uncertain | High | Phase 0 POC | Open |
| 2026-05-11 | Build time at 10k rules unknown | Med | Phase 1 measurement | Open |
| 2026-05-11 | Memory footprint vs runtime compile unknown | Med | Phase 5 load test | Open |
| 2026-05-11 | Authoring UX regression | Med | Defer hybrid mode; doc-only mitigation | Open |

---

## Verification commands

```bash
# Phase 1 — build the kjar
cd rules-kjar && mvn clean install

# Phase 1 — inspect kjar contents
unzip -l rules-kjar/target/rules-kjar-*.jar | head -20

# Phase 2 — full build with both modules
mvn -B clean install -pl rules-kjar,. -am

# Phase 4 — run kjar integration tests only
mvn test -Dtest=KjarIntegrationTest,KjarRuleStorageTest

# Phase 4 — full regression
mvn clean verify -Dtest='!S3StorageIntegrationTest'

# Phase 5 — load test
./scripts/run-load-test.sh --kjar-mode

# Phase 7 — manual rollback
curl -X POST http://stage.example.com/admin/refresh-rules \
  -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"version":"1.0.20260511020000"}'
```
