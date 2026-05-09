# Stack Modernization Checklist

**Companion to**: [`stack-modernization-plan.md`](stack-modernization-plan.md)
**Started**: _(fill in when implementation starts)_
**Estimated effort**: 2–4 working days

Tick each box (`[ ]` → `[x]`) as you complete it. Do not skip phases — each phase has exit criteria that the next depends on.

---

## Phase 1 — Resolve concrete versions + pre-flight verification (1 hour)

### Maven Central version checks
- [ ] Spring Boot 3.7.x current latest GA: `_____________`
- [ ] Drools 10.x current latest GA on Maven Central: `_____________`
- [ ] Lombok 1.18.x current latest with Java 25 support: `_____________`
- [ ] AWS SDK v2 BOM current latest: `_____________`
- [ ] testcontainers current latest: `_____________`
- [ ] maven-compiler-plugin current latest: `_____________`
- [ ] maven-enforcer-plugin current latest: `_____________`
- [ ] jacoco-maven-plugin current latest: `_____________`
- [ ] spotless-maven-plugin current latest: `_____________`
- [ ] google-java-format current latest: `_____________`
- [ ] spotbugs-maven-plugin current latest: `_____________`

### Docker base image verification
- [ ] `maven:3.9-eclipse-temurin-25` exists on Docker Hub
- [ ] `amazoncorretto:25-alpine-jdk` exists on Docker Hub (else: fallback chosen → `_____________`)

### Drools target decision
- [ ] Drools 10.x is GA on Maven Central — proceed with 10.x
- OR — if not GA: fall back to Drools 9.x latest (record reason: `_____________`)
- OR — if 9.x also unavailable: stop, leave Drools at 8.x latest (plan downshifts to Java-25-only)

### Phase 1 exit gate
- [ ] All chosen versions documented in [`stack-modernization-plan.md`](stack-modernization-plan.md) "Target versions" table

---

## Phase 2 — Pom + build config (30 min)

### Compiler target ([`pom.xml`](../../pom.xml))
- [ ] `<maven.compiler.source>` 17 → 25 (line 17)
- [ ] `<maven.compiler.target>` 17 → 25 (line 18)

### Property pins
- [ ] `<spring.boot.version>` 3.2.5 → chosen 3.7.x
- [ ] `<drools.version>` 8.44.0.Final → chosen 10.x (or fallback)
- [ ] `<aws.sdk.version>` 2.20.56 → chosen latest 2.x
- [ ] `<testcontainers.version>` 1.19.7 → chosen latest
- [ ] `<lombok.version>` 1.18.30 → chosen latest with Java 25 support
- [ ] **DELETE** `<micrometer.version>` property (Spring Boot BOM controls it transitively)

### Drools dependency coordinates
- [ ] Replace `org.drools:drools-core` with `org.drools:drools-engine`
- [ ] Replace `org.drools:drools-compiler` with `org.drools:drools-engine` (or remove if redundant)
- [ ] Remove any `org.drools:drools-mvel` dependencies (deprecated)
- [ ] Remove any `org.drools:drools-engine-classic` dependencies (deprecated)
- [ ] Run `mvn dependency:tree | grep drools` to verify transitive resolution

### Maven Enforcer rule
- [ ] `<version>[17,18)</version>` → `<version>[25,26)</version>` (line 270)
- [ ] Update message: "Java 17 is required!" → "Java 25 is required!" (line 271)
- [ ] Bump enforcer plugin: `3.3.0` → 3.5.0+ (line 260)

### Build plugin bumps
- [ ] maven-compiler-plugin: `3.11.0` → 3.13.0+
- [ ] jacoco-maven-plugin: `0.8.8` → 0.8.13+
- [ ] spotless-maven-plugin: `2.36.0` → 2.45.0+
- [ ] google-java-format inside spotless: `1.17.0` → 1.24.0+
- [ ] spotbugs-maven-plugin: `4.7.3.0` → latest 4.x

### Phase 2 exit gate
- [ ] `mvn -DskipTests clean compile` succeeds on Java 25
- [ ] No transitive dependency errors in `mvn dependency:tree`

---

## Phase 3 — Java config side-files (10 min)

### [`Dockerfile`](../../Dockerfile)
- [ ] Line 4 build base: `maven:3.9-eclipse-temurin-17` → `maven:3.9-eclipse-temurin-25` (or fallback)
- [ ] Line 16 runtime base: `amazoncorretto:17-alpine-jdk` → `amazoncorretto:25-alpine-jdk` (or fallback)
- [ ] JVM flags reviewed against Java 25:
  - [ ] `+UseG1GC` still valid
  - [ ] `+UseStringDeduplication` still valid
  - [ ] `+UseCompressedOops` still valid
  - [ ] `+UseCompressedClassPointers` still valid
  - [ ] No flags removed in Java 24/25

### [`set-java-env.sh`](../../set-java-env.sh)
- [ ] All `17` references replaced with `25`
- [ ] `/usr/libexec/java_home -v 17` → `-v 25`
- [ ] Install hints updated
- [ ] Error messages updated

### [`docker-compose.yml`](../../docker-compose.yml)
- [ ] Scanned for Java references — `_____________` (likely none, verify)

### Phase 3 exit gate
- [ ] `./docker-build-test.sh` builds the image successfully on Java 25

---

## Phase 4 — Spring Boot 3.2 → 3.7 migration fixes (1–2 hours)

### `@MockBean` → `@MockitoBean` migration
- [ ] [`RuleExecutionControllerTest.java:26`](../../src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java#L26) — change import from `org.springframework.boot.test.mock.mockito.MockBean` to `org.springframework.test.context.bean.override.mockito.MockitoBean`
- [ ] [`RuleExecutionControllerTest.java:44`](../../src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java#L44) — change `@MockBean` to `@MockitoBean`
- [ ] Re-grep `@MockBean` and `org.springframework.boot.test.mock.mockito` to confirm zero remaining usage

### `application.yml` property review
- [ ] Cross-reference [`src/main/resources/application.yml`](../../src/main/resources/application.yml) against Spring Boot 3.3/3.4/3.5/3.6/3.7 migration guides
- [ ] Actuator endpoint exposure paths still valid
- [ ] `management.tracing.*` properties (Micrometer Tracing reorganization in 3.3) still valid
- [ ] `management.endpoint.health.show-details` still valid
- [ ] Resilience4j circuit-breaker configs still valid
- [ ] No `Cannot resolve placeholder` or `Property X is not bound` warnings on startup

### Auto-configuration review
- [ ] No deprecated `@AutoConfiguration` registrations in startup logs

### Phase 4 exit gate
- [ ] `mvn test` runs all 589 tests, all pass
- [ ] If 1–2 tests fail: investigated and fixed individually

---

## Phase 5 — Drools 10 migration: coordinates swap + DRL behavior validation (1 day)

### 5a. Confirm Java touch surface (15 min)
- [ ] `grep -rln "org\.drools\.\|org\.kie\." src/main src/test` run
- [ ] Files surfaced: `_____________`
- [ ] [`DroolsEngineService.java`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) — all imports resolve in Drools 10
- [ ] [`RuleExecutor.java`](../../src/main/java/com/company/drools/core/engine/RuleExecutor.java) — `KieSession` import resolves
- [ ] [`DrlSanitizer.java`](../../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) — confirmed no Drools API touchpoints (text parsing only)
- [ ] If any class isn't found: log finding and proceed to 5d

### 5b. Drools coordinates (already done in Phase 2)
- [ ] Verified — see Phase 2

### 5c. DRL behavior validation against executable model (2–3 hours)

#### Greps
- [ ] `grep -rn "(String)\|(Integer)\|(Double)\|(Long)\|(Float)" sample-rules/` — capture hits
- [ ] `grep -rn "\.add(" sample-rules/` — capture hits
- [ ] List of suspect patterns documented: `_____________`

#### Per-rule review (10 sample rules)
- [ ] [`pricing/discount/simple.drl`](../../sample-rules/pricing/discount/simple.drl) — reviewed for casts/generics/wrappers
- [ ] [`pricing/discount/vip.drl`](../../sample-rules/pricing/discount/vip.drl) — reviewed
- [ ] [`pricing/discount/bulk.drl`](../../sample-rules/pricing/discount/bulk.drl) — reviewed
- [ ] [`pricing/discount/first-time.drl`](../../sample-rules/pricing/discount/first-time.drl) — reviewed
- [ ] [`pricing/shipping/standard.drl`](../../sample-rules/pricing/shipping/standard.drl) — reviewed
- [ ] [`pricing/shipping/express.drl`](../../sample-rules/pricing/shipping/express.drl) — reviewed
- [ ] [`seasonal/holiday/discount.drl`](../../sample-rules/seasonal/holiday/discount.drl) — reviewed
- [ ] [`seasonal/holiday/blackfriday.drl`](../../sample-rules/seasonal/holiday/blackfriday.drl) — reviewed
- [ ] [`validation/customer/age.drl`](../../sample-rules/validation/customer/age.drl) — reviewed
- [ ] [`validation/customer/credit.drl`](../../sample-rules/validation/customer/credit.drl) — reviewed

#### Fixes (if any)
- [ ] List of files modified: `_____________`
- [ ] Each fix documented in working notes for ADR-014

### 5d. Targeted Java code updates (only if 5a flagged something)
- [ ] N/A — 5a passed clean
- OR — files modified: `_____________`

### 5e. Memory + behavior validation (3 hours)
- [ ] `mvn spring-boot:run -Dspring.profiles.active=local` — boots cleanly
- [ ] No `WARNING: Illegal reflective access` in startup logs
- [ ] All 10 sample rules live-tested via cookbook curl examples; outputs **byte-identical** to pre-migration
  - [ ] `pricing.discount.simple` with `amount: 100` → `amount: 90, discount: 10`
  - [ ] `pricing.discount.vip` with `amount: 100, customerType: VIP` → `amount: 80, discount: 20`
  - [ ] VIP+simple stacking → `amount: 72`
  - [ ] (continue for all 10 rules from cookbook)
- [ ] Memory leak validation:
  - [ ] 10 refreshes → <1 MiB growth
  - [ ] 50 refreshes → <2 MiB growth
  - [ ] 2000 refreshes → <40 MiB growth (original benchmark: 32.6 MiB)

### Phase 5 exit gate
- [ ] All 589 tests still pass
- [ ] No reflection warnings
- [ ] All sample rule outputs byte-identical
- [ ] Memory stability matches benchmark
- [ ] ADR-003 atomic-swap pattern verified working as-is (likely no edit)

---

## Phase 6 — Doc sweep (1–2 hours)

### Active doc updates (`Java 17` → `Java 25`, `17-alpine` → `25-alpine`, etc.)
- [ ] [`README.md`](../../README.md)
- [ ] [`CLAUDE.md`](../../CLAUDE.md)
- [ ] [`project-documentation/00-system-overview.md`](../../project-documentation/00-system-overview.md) — including "Numbers worth knowing" Java row
- [ ] [`project-documentation/03-tech-stack.md`](../../project-documentation/03-tech-stack.md) — Java entry + every plugin version line bumped to match new pom
- [ ] [`project-documentation/06-deployment.md`](../../project-documentation/06-deployment.md)
- [ ] [`project-documentation/07-docker-and-compose.md`](../../project-documentation/07-docker-and-compose.md)
- [ ] [`project-documentation/16-drl-sandboxing.md`](../../project-documentation/16-drl-sandboxing.md) — verify what Java 17 reference exists
- [ ] [`project-documentation/24-jvm-optimization.md`](../../project-documentation/24-jvm-optimization.md)
- [ ] [`project-documentation/27-development-setup.md`](../../project-documentation/27-development-setup.md)
- [ ] [`project-documentation/31-troubleshooting.md`](../../project-documentation/31-troubleshooting.md)
- [ ] [`project-documentation/32-getting-started.md`](../../project-documentation/32-getting-started.md)
- [ ] [`project-documentation/34-java-setup-guide.md`](../../project-documentation/34-java-setup-guide.md) — biggest doc edit
- [ ] [`project-documentation/37-glossary.md`](../../project-documentation/37-glossary.md)
- [ ] [`project-documentation/api-reference/openapi.yml`](../../project-documentation/api-reference/openapi.yml)
- [ ] [`project-documentation/README.md`](../../project-documentation/README.md) (manifest) — add 2026-05-09 modernization to Provenance

### Historical references (PRESERVE — do NOT edit)
- [ ] Confirmed: "Phase 6: Java 17 enforcement, 2026-02-19" in CLAUDE.md still says "Java 17"
- [ ] Confirmed: ADRs explaining the original Java 17 choice still say "Java 17"
- [ ] Confirmed: `.ai-workspace/documentations/CHECKLIST.md`, `CODE_FINDINGS.md`, `SUMMARY.md` untouched
- [ ] Confirmed: `.ai-workspace/ai-summary/`, `ai-initial-context/`, `compact-logs/`, `snap-memory/` untouched

### New ADRs ([`36-architecture-decision-records.md`](../../project-documentation/36-architecture-decision-records.md))
- [ ] **ADR-013** drafted: Java 17 → 25 + Spring Boot 3.7 + dependency sweep (2026-05-09)
  - [ ] Context section
  - [ ] Decision section
  - [ ] Consequences section
- [ ] **ADR-014** drafted: Drools 8 → 10 migration
  - [ ] Context section (executable model adoption, single `drools-engine` artifact)
  - [ ] Decision section (keep traditional DRL; preserve atomic-swap pattern; reviewed 3 executable-model behavior diffs)
  - [ ] Consequences section
- [ ] [ADR-003](../../project-documentation/36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal) updated **only if** atomic-swap implementation changed (likely no edit)

### `.ai-workspace` updates
- [ ] [`security-backlog.md`](security-backlog.md) — #30 marked closed via 2026-05-09 stack modernization
- [ ] "39/42" wording updated to "40/42" or annotated with 2026-05-09 date in 5+ places across the corpus
- [ ] [`project-improvement-plan.md`](project-improvement-plan.md) — 2026-05-09 modernization noted in status delta section

### Phase 6 exit gate
- [ ] Active docs all updated to Java 25
- [ ] Historical refs preserved (no false-positive replacements)
- [ ] Two new ADRs written
- [ ] `grep -rn "Java 17\|17-alpine\|JDK 17" --include="*.md" --include="*.sh" --include="Dockerfile" --include="pom.xml"` returns **only** historical/phase-description matches we intentionally kept

---

## Phase 7 — Live integration validation (30 min)

- [ ] `mvn clean test` — all 589 tests pass
- [ ] `mvn spring-boot:run -Dspring.profiles.active=local` — boots cleanly; no warnings in logs
- [ ] `docker build -t drools-rule-engine:latest .` — succeeds
- [ ] `docker images drools-rule-engine` shows new image; size near ~347 MB
- [ ] `docker run -d -p 8080:8080 -p 8081:8081 -e RULE_SOURCE=memory drools-rule-engine:latest`
- [ ] `curl http://localhost:8080/admin/health` returns 200 UP
- [ ] All 10 sample rules from cookbook produce expected outputs
- [ ] 10 rule refreshes — heap stays stable per `curl /admin/memory/info | jq`
- [ ] All 7 security headers present on responses:
  - [ ] X-Content-Type-Options
  - [ ] X-Frame-Options
  - [ ] Content-Security-Policy
  - [ ] Strict-Transport-Security
  - [ ] X-XSS-Protection
  - [ ] Referrer-Policy
  - [ ] Permissions-Policy

### Phase 7 exit gate
- [ ] Health = UP
- [ ] All sample rule outputs match
- [ ] Memory stable
- [ ] All 7 security headers present

---

## Final verification (when ALL phases done)

- [ ] `java -version` shows 25.x; `mvn -version` shows Java 25
- [ ] `mvn clean install` succeeds; build is reproducible **only** on Java 25 (Maven Enforcer fails on 24 or 26 — verify by trying Java 21 and Java 24)
- [ ] All 589 tests pass — `Tests run: 589, Failures: 0, Errors: 0`
- [ ] Docker image builds and runs
- [ ] All 10 sample rule outputs byte-identical to pre-migration
- [ ] Memory-leak benchmark passed (2000-refresh test <40 MiB growth)
- [ ] All 7 security headers still present
- [ ] No active `Java 17` references in docs (only historical preserved)
- [ ] [`03-tech-stack.md`](../../project-documentation/03-tech-stack.md) version table matches pom.xml exactly
- [ ] ADR-013 + ADR-014 written
- [ ] [`security-backlog.md`](security-backlog.md) #30 closed
- [ ] [`project-improvement-plan.md`](project-improvement-plan.md) delta updated

---

## Rollback procedure (if needed)

If a phase fails its exit gate and the cause isn't quickly fixable:

1. `git status` — see uncommitted changes from current phase
2. `git diff` — review what would be reverted
3. `git checkout -- <file>` — revert the failing phase's changes
4. Document the blocker in working notes
5. Decide: fall back (e.g., Drools 9.x), defer (split this work), or escalate

---

## Working notes

_(Use this section as a free-form log of issues, decisions, or observations during implementation. Don't trim — it becomes the source for ADRs and the next status delta.)_

- _(date)_ — _(note)_
