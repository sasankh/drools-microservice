# Stack Modernization Plan: Java 17 → 25, Spring Boot 3.2.5 → 3.7.x, Drools 8.44 → 10.x

**Date drafted**: 2026-05-09
**Author**: Drafted with AI assistance; reviewed by user
**Status**: Approved — implementation pending
**Scope owner**: Sasankh BC

---

## Context

The project is currently running Java 17 LTS (~4.5 years old, two LTS generations behind) with a dependency stack that's been pinned since the project's early phases:
- Spring Boot 3.2.5 (April 2024)
- Drools 8.44.0.Final (early 2024)
- Lombok 1.18.30, AWS SDK 2.20.56, Micrometer 1.12.4, plus older Maven plugins
- Maven Enforcer plugin currently fails the build on anything other than Java 17

The decision: instead of a clean Java 17 → 21 (zero library bumps) bump, invest in the **maximum modernization path** in one coordinated effort:
- Java 25 LTS (released Sept 2025)
- Spring Boot 3.7.x (current latest)
- **Drools 10.x** (current latest major)
- All transitive dependency bumps

Bundling all of this into one coordinated change has two advantages:
1. **Closes deferred security finding #30** ("Outdated dependencies — skipped per user") in the same change.
2. **Pays the test/regression cost once**, instead of three times for three separate bumps.

The trade-off: the change touches more files than a Java-only bump would. But the migration cost is much lower than initially feared because Drools 10 explicitly preserves the traditional API the project already uses (see Verification below).

---

## Verification done before plan finalization

| Source | What it confirmed |
|---|---|
| [Drools 10 release notes](https://kie.apache.org/docs/10.0.x/drools/drools/release-notes/index.html) | Drools 10 baselines on JDK 17+. `drools-engine-classic` and `drools-mvel` deprecated; use `drools-engine`. Traditional DRL syntax still works. KIE Server and Business Central retired (this project uses neither). |
| [Drools 10 migration guide](https://kie.apache.org/docs/10.0.x/drools/drools/migration-guide/index.html) | **"All APIs and DRL syntax are compatible"** between Drools 8 and 10. Traditional KieServices/KieContainer/KieBase/KieSession is **"still supported but discouraged"**. Three documented executable-model behavior gotchas listed below. |
| [Drools 10 introduction](https://kie.apache.org/docs/10.0.x/drools/drools/introduction/index.html) | Confirms JDK 17 minimum. Lists "Rule Language Reference (Traditional)" as a first-class doc section. |
| [Drools 10 traditional DRL reference](https://kie.apache.org/docs/10.0.x/drools/drools/language-reference-traditional/index.html) | **"The traditional syntax is still fully supported."** No deprecation timeline. The one documented limitation is `accumulate` with inline custom code — this project's 10 sample rules don't use `accumulate`. |
| Spring Boot 3.x release timeline | 3.6 (Nov 2025) was the first line with official Java 25 support. 3.7 (May 2026) is the current cadence release. |
| Local codebase grep | Only 1 file uses `@MockBean` ([`RuleExecutionControllerTest.java:26,44`](../../src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java#L26)) — straightforward `@MockitoBean` migration. No usage of `accumulate` in any of the 10 sample DRL files. |

**Three documented executable-model behavior gotchas** (the actual remaining risk surface):
1. **Invalid type coercion**: `(String) intValue` is rejected under executable model (was tolerated by MVEL).
2. **Strict generics**: can't add a `String` to a `List<BigDecimal>` (MVEL was permissive).
3. **Wrapper coercion**: `10` doesn't auto-coerce to `Long` — need `10L`.

These three are why Phase 5c is a careful structured DRL review, not a wave-through.

---

## What does NOT need to change (verified)

- **Java code in [`DroolsEngineService.java`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java)** — KieServices/KieContainer/KieBase/KieSession API still works as-is.
- **The atomic-swap pattern** from [ADR-003](../../project-documentation/36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal) — still works.
- **`KieContainer.dispose()` memory-leak fix** — still applies (Drools 10 doesn't change `KieContainer` lifecycle).
- **[`RuleExecutor.java`](../../src/main/java/com/company/drools/core/engine/RuleExecutor.java)** — KieSession execution path unchanged.
- **[`DrlSanitizer.java`](../../src/main/java/com/company/drools/core/engine/DrlSanitizer.java)** — only parses DRL strings; doesn't touch Drools API.
- **[`LocalLRUCache.java`](../../src/main/java/com/company/drools/cache/LocalLRUCache.java)** — caches `KieBase` objects; `KieBase` is still the same type in Drools 10.
- **[ADR-001](../../project-documentation/36-architecture-decision-records.md#adr-001-traditional-drl-syntax-only-not-rule-units--oopath)** (traditional DRL syntax only) — confirmed still supported in Drools 10.

---

## Target versions (concrete)

> Pin to current latest stable at implementation time. Below are the floors. Phase 1 finalizes them by checking Maven Central.

| Component | Current | Target floor | Maven coords |
|---|---|---|---|
| Java | 17 | **25** | — |
| Spring Boot | 3.2.5 | **3.7.x** *(latest stable)* | `org.springframework.boot:spring-boot-dependencies` |
| **Drools** | **8.44.0.Final** | **10.x latest stable** (fall back: 9.x latest if 10.x not GA; final fallback: latest 8.x with no migration) | `org.drools:drools-engine` (single aggregator replacing `drools-core` + `drools-compiler` + `drools-engine-classic` + `drools-mvel`) |
| AWS SDK v2 BOM | 2.20.56 | *latest 2.x stable* | `software.amazon.awssdk:bom` |
| Lombok | 1.18.30 | **1.18.36+** (Java 25 annotation-processing support) | `org.projectlombok:lombok` |
| testcontainers | 1.19.7 | *latest 1.x* | `org.testcontainers:testcontainers` |
| Micrometer | 1.12.4 | *transitively from Spring Boot BOM* — delete the `<micrometer.version>` property | `io.micrometer:micrometer-core` |
| Resilience4j | (transitive) | *transitively from Spring Boot BOM* | `io.github.resilience4j:resilience4j-spring-boot3` |
| maven-compiler-plugin | 3.11.0 | **3.13.0+** | `org.apache.maven.plugins:maven-compiler-plugin` |
| maven-enforcer-plugin | 3.3.0 | **3.5.0+** | `org.apache.maven.plugins:maven-enforcer-plugin` |
| jacoco-maven-plugin | 0.8.8 | **0.8.13+** | `org.jacoco:jacoco-maven-plugin` |
| spotless-maven-plugin | 2.36.0 | **2.45.0+** | `com.diffplug.spotless:spotless-maven-plugin` |
| google-java-format | 1.17.0 | **1.24.0+** (Java 25 syntax) | (transitively via spotless) |
| spotbugs-maven-plugin | 4.7.3.0 | *latest 4.x stable* | `com.github.spotbugs:spotbugs-maven-plugin` |
| Maven build base image | `maven:3.9-eclipse-temurin-17` | `maven:3.9-eclipse-temurin-25` (verify tag) | — |
| Runtime base image | `amazoncorretto:17-alpine-jdk` | `amazoncorretto:25-alpine-jdk` (verify tag) | — |

---

## Effort estimate

**2–4 working days total.**

Initial estimate of 2–3 weeks was based on the pessimistic assumption that Drools 10 would force a rule-runtime API rewrite. The migration guide explicitly preserves the traditional API, so the cost is bounded by:
- Pom + plugin bumps (~1 hour)
- Side-files (Dockerfile, scripts) (~10 min)
- Spring Boot migration (1 known `@MockBean`, plus property review) (~1–2 hours)
- DRL behavior validation against executable model (~2–3 hours)
- Memory + integration testing (~3 hours)
- Doc sweep across ~14 active doc files (~1–2 hours)
- New ADRs (~1 hour)

---

## Phase plan

The change is large enough that it's executed in clearly bounded phases with a working build at the end of each.

### Phase 1: Resolve concrete versions + pre-flight verification (1 hour)

Read-only verification before any edit. Critical because choices here drive the rest of the plan.

1. **Maven Central versions** — query for the current latest GA of: Spring Boot 3.7.x, Drools 10.x, Lombok 1.18.x, AWS SDK 2.x, testcontainers, all Maven plugins listed above.
2. **Verify Docker Hub tags exist**: `maven:3.9-eclipse-temurin-25` and `amazoncorretto:25-alpine-jdk`. Fall-back order if missing: `amazoncorretto:25-alpine` → `eclipse-temurin:25-alpine` → `eclipse-temurin:25-jdk-alpine`.
3. **Verify Drools 10.x is GA on Maven Central** (not just docs). If only milestone/RC: decide between waiting or falling back to Drools 9.x latest. Document the chosen Drools version.
4. **Re-read this plan with the concrete versions in hand**; update the version table above with chosen pins.

**Exit criteria**:
- Concrete versions chosen for every line in the version table
- Drools target finalized (10.x or 9.x)
- All Docker tags confirmed available

**Blocker handling**: if Drools 10 isn't GA, the plan automatically downshifts to 9.x. If 9.x also isn't suitable, plan splits — Java 25 + Spring Boot bump only, Drools stays.

---

### Phase 2: Pom + build config (30 min)

Single-file edit to [`pom.xml`](../../pom.xml).

1. **Compiler source/target** ([pom.xml:17-18](../../pom.xml#L17-L18)):
   - `<maven.compiler.source>17</maven.compiler.source>` → `25`
   - `<maven.compiler.target>17</maven.compiler.target>` → `25`
2. **Property pins**:
   - `<spring.boot.version>3.2.5</spring.boot.version>` → chosen 3.7.x
   - `<drools.version>8.44.0.Final</drools.version>` → chosen Drools 10.x
   - `<aws.sdk.version>2.20.56</aws.sdk.version>` → chosen latest 2.x
   - `<testcontainers.version>1.19.7</testcontainers.version>` → chosen latest
   - `<micrometer.version>1.12.4</micrometer.version>` → **delete this property** (Spring Boot BOM controls it)
   - `<lombok.version>1.18.30</lombok.version>` → 1.18.36+
3. **Drools dependency coordinates** — replace these:
   ```xml
   <!-- OLD -->
   <dependency>
     <groupId>org.drools</groupId>
     <artifactId>drools-core</artifactId>
     <version>${drools.version}</version>
   </dependency>
   <dependency>
     <groupId>org.drools</groupId>
     <artifactId>drools-compiler</artifactId>
     <version>${drools.version}</version>
   </dependency>
   <!-- (and any drools-mvel, drools-engine-classic) -->
   ```
   With this:
   ```xml
   <!-- NEW -->
   <dependency>
     <groupId>org.drools</groupId>
     <artifactId>drools-engine</artifactId>
     <version>${drools.version}</version>
   </dependency>
   ```
4. **Maven Enforcer rule** ([pom.xml:269-272](../../pom.xml#L269-L272)):
   - `<version>[17,18)</version>` → `<version>[25,26)</version>`
   - Update message: "Java 17 is required!" → "Java 25 is required!"
   - Bump enforcer plugin: `3.3.0` → `3.5.0+`
5. **Plugin version bumps**:
   - maven-compiler-plugin: `3.11.0` → `3.13.0+`
   - jacoco-maven-plugin ([pom.xml:240](../../pom.xml#L240)): `0.8.8` → `0.8.13+`
   - spotless-maven-plugin ([pom.xml:286](../../pom.xml#L286)): `2.36.0` → `2.45.0+`
   - google-java-format inside spotless ([pom.xml:290](../../pom.xml#L290)): `1.17.0` → `1.24.0+`
   - spotbugs-maven-plugin: `4.7.3.0` → latest 4.x

**Exit criteria**: `mvn -DskipTests clean compile` succeeds on Java 25.

**Failure modes**:
- Drools doesn't compile on Java 25 → fall back to Drools 9.x or 8.x latest
- Transitive AWS SDK module pulls a too-old version → use `<dependencyManagement>` to pin
- Some plugin version doesn't exist yet → use latest known stable

---

### Phase 3: Java config side-files (10 min)

1. **[`Dockerfile`](../../Dockerfile)**:
   - Line 4: `FROM maven:3.9-eclipse-temurin-17 AS build` → `maven:3.9-eclipse-temurin-25` (or fallback per Phase 1)
   - Line 16: `FROM amazoncorretto:17-alpine-jdk` → `amazoncorretto:25-alpine-jdk` (or fallback)
   - JVM flags (lines 27-44): review each against Java 25; G1GC + StringDeduplication + CompressedOops + CompressedClassPointers all stable. Double-check none have been removed in Java 24/25 (rare).
2. **[`set-java-env.sh`](../../set-java-env.sh)**:
   - Replace all `17` references with `25` (`/usr/libexec/java_home -v 25`, install hints, error messages)
3. **[`docker-compose.yml`](../../docker-compose.yml)**: scan for any pinned Java references — likely none, verify.

**Exit criteria**: `./docker-build-test.sh` builds the image successfully on Java 25.

---

### Phase 4: Spring Boot 3.2 → 3.7 migration fixes (1–2 hours)

5 minor versions of Spring Boot changes. Most non-breaking, but a handful matter.

**Known footprint based on grep**:

1. **`@MockBean` deprecation** (Spring Boot 3.4 deprecated, 3.5+ removed)
   - Affected: [`src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java`](../../src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java) — line 26 (import) + line 44 (annotation)
   - Fix: replace `import org.springframework.boot.test.mock.mockito.MockBean;` with `import org.springframework.test.context.bean.override.mockito.MockitoBean;`
   - Replace `@MockBean private ...` with `@MockitoBean private ...`
   - Only 1 file affected (verified by grep `@MockBean` returns 1 match).

2. **`application.yml` property review** — read Spring Boot 3.3/3.4/3.5/3.6/3.7 migration guides for any renamed/removed properties this project uses. Likely candidates:
   - Actuator endpoint exposure paths
   - `management.tracing.*` (Micrometer Tracing reorganization in 3.3)
   - `management.endpoint.health.show-details` (still works, but verify)
   - Resilience4j circuit-breaker configs

3. **Servlet API**: already on `jakarta.servlet`; no migration needed. The `javax.*` strings in [`DrlSanitizer.java:89-92`](../../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L89) are JDK packages (script, naming, management, net.ssl), not servlet — unaffected.

4. **Tomcat 11** (Spring Boot 3.5+) — should be transparent; this project doesn't customize Tomcat.

5. **Auto-configuration** — Spring Boot 3.4 reorganized some `@AutoConfiguration` registration. Watch startup logs for `Cannot resolve placeholder` or `Property X is not bound`.

**Exit criteria**: `mvn test` runs all 589 tests, all pass.

**Likely failure modes**:
- 1–2 tests fail because of changed default behavior — investigate individually
- A property in `application.yml` is silently ignored — Spring Boot logs will warn

---

### Phase 5: Drools 10 migration — coordinates swap + DRL behavior validation (1 day)

**Confirmed by the migration guide**: traditional KieServices/KieContainer/KieBase/KieSession API still works. **No code rewrite required.** This phase is essentially the artifact swap (already done in Phase 2) plus DRL behavior validation under the executable model.

#### 5a. Confirm Java touch surface is unaffected (15 min)

```bash
grep -rln "org\.drools\.\|org\.kie\." src/main src/test
```

Expected files:
- [`DroolsEngineService.java`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) — uses `KieServices`, `KieContainer`, `KieBase`, `KieFileSystem`, `KieBuilder`, `KieRepository`. All still in Drools 10.
- [`RuleExecutor.java`](../../src/main/java/com/company/drools/core/engine/RuleExecutor.java) — uses `KieSession`. Still in Drools 10.
- [`DrlSanitizer.java`](../../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) — text parsing only. Unaffected.

Verify each import resolves under the new `drools-engine` artifact. If a class isn't found, that triggers Phase 5d.

#### 5b. (skipped — Drools coordinates already swapped in Phase 2)

#### 5c. Validate all 10 sample DRL files against executable-model behavior (2–3 hours)

Drools 10's `drools-engine` defaults to the **executable model** (Drools 8 used MVEL via `drools-engine-classic`). Three concrete behavior differences:

| Difference | Symptom | What to grep in [`sample-rules/`](../../sample-rules/) |
|---|---|---|
| Invalid type coercion: `(String) intValue` rejected | DRL compile error | `grep -rn "(String)\|(Integer)\|(Double)\|(Long)\|(Float)" sample-rules/` |
| Generics strict: can't add wrong type to typed list | DRL compile error | `grep -rn "\.add(" sample-rules/` |
| Wrapper coercion: `10` not auto-Long | Behavior diff (numeric output changes) | Look for numeric literals used where Long is expected; verify each `* 0.20` etc. |

Process:
1. Run the two greps; capture all hits.
2. Read each of the 10 sample rules end-to-end with the three rules in mind. The sample rules use `Map<String, Object>` and `Double` math — cast safety matters.
3. For each potential issue, fix the DRL. Common fix: change `(Double)$amount` to `((Number)$amount).doubleValue()`, or use explicit `Long`/`Double` literals.

**Exit criteria 5c**: all 10 sample DRL files compile cleanly under the executable model and produce identical rule outputs.

#### 5d. Targeted Java code updates if any (TBD — only if 5a surfaces something)

Likely zero files. Surface only if Phase 5a finds an import that's been removed.

#### 5e. Memory + behavior validation (3 hours)

1. Run application: `mvn spring-boot:run -Dspring.profiles.active=local`
2. Watch for `WARNING: Illegal reflective access` (Java 25 strict mode)
3. **Live-test all 10 sample rules** from [`19-sample-rules-cookbook.md`](../../project-documentation/19-sample-rules-cookbook.md). Outputs must be **byte-identical** to current production. Critical examples:
   - `pricing.discount.simple` with `amount: 100` → `amount: 90, discount: 10`
   - `pricing.discount.vip` with `amount: 100, customerType: VIP` → `amount: 80, discount: 20`
   - VIP+simple stacking → `amount: 72`
4. Run the memory-leak validation pattern from the original Phase 6 fix:
   - 10 refreshes → expect <1 MiB growth
   - 50 refreshes → expect <2 MiB growth
   - **2000 refreshes** → expect <40 MiB growth (the original 32.6 MiB is the benchmark)

**Exit criteria for Phase 5 overall**:
- All 589 tests pass (no test changes expected beyond Phase 4)
- `mvn spring-boot:run` boots cleanly with **no reflection warnings**
- All 10 sample rules produce **byte-identical** outputs to pre-migration
- Memory stability matches or exceeds the original Phase 6 benchmark
- ADR-003 atomic-swap pattern still works as-documented (no edit required)

**If 5c surfaces DRL behavior differences requiring rewrites**: add to scope; document in ADR-014; verify each rewrite produces the same numeric output.

---

### Phase 6: Doc sweep (1–2 hours)

324 string occurrences of `Java 17`, `17-alpine`, `JDK 17` across 74 files. NOT a global `sed` replace — too many false positives in historical text.

**Active references** (replace `Java 17` → `Java 25`, `17-alpine` → `25-alpine`, etc.):
- [`README.md`](../../README.md) — install instructions, prerequisites, quickstart
- [`CLAUDE.md`](../../CLAUDE.md) — tech stack, setup, common commands
- [`project-documentation/00-system-overview.md`](../../project-documentation/00-system-overview.md) — tech stack table, "Numbers worth knowing"
- [`03-tech-stack.md`](../../project-documentation/03-tech-stack.md) — Java entry; **all plugin versions** need updating to match new pom
- [`06-deployment.md`](../../project-documentation/06-deployment.md) — deploy instructions
- [`07-docker-and-compose.md`](../../project-documentation/07-docker-and-compose.md) — Dockerfile walkthrough
- [`24-jvm-optimization.md`](../../project-documentation/24-jvm-optimization.md) — JVM flag commentary
- [`27-development-setup.md`](../../project-documentation/27-development-setup.md) — onboarding
- [`31-troubleshooting.md`](../../project-documentation/31-troubleshooting.md) — Java install troubleshooting
- [`32-getting-started.md`](../../project-documentation/32-getting-started.md) — quickstart
- [`34-java-setup-guide.md`](../../project-documentation/34-java-setup-guide.md) — full install guide; **largest doc edit**
- [`37-glossary.md`](../../project-documentation/37-glossary.md) — Java entry
- [`api-reference/openapi.yml`](../../project-documentation/api-reference/openapi.yml) — tech stack note
- [`16-drl-sandboxing.md`](../../project-documentation/16-drl-sandboxing.md) — verify if any Java 17 reference exists

**Historical references** (preserve as-is — these describe past events):
- "Phase 6: Java 17 enforcement, 2026-02-19" in CLAUDE.md
- Any ADR text that explained the original Java 17 choice
- `.ai-workspace/documentations/CHECKLIST.md`, `CODE_FINDINGS.md`, `SUMMARY.md` — historical artifacts

**New ADRs in [`36-architecture-decision-records.md`](../../project-documentation/36-architecture-decision-records.md)**:
- **ADR-013: Java 17 → 25 + Spring Boot 3.7 + dependency sweep (2026-05-09)**
  - Context: original Java 17 was current best at project start; by 2026-05 it's 2 LTS generations behind; dependency sweep was deferred as #30.
  - Decision: bundle Java 25 + Spring Boot 3.7 + Lombok + plugin bumps + Drools 10, closing #30.
  - Consequences: 5-minor-version Spring Boot jump; coordinated test run validates everything.
- **ADR-014: Drools 8 → 10 migration (executable model adoption, single `drools-engine` artifact)**
  - Context: Drools 10 deprecated `drools-engine-classic`/`drools-mvel`; defaults to executable model.
  - Decision: adopt `drools-engine`; keep traditional DRL syntax; preserve KieContainer atomic-swap pattern (ADR-003 unchanged); validated against migration guide.
  - Consequences: three documented executable-model behavior gotchas reviewed against all 10 sample DRL files in Phase 5c; no Java code changes required.

**Other doc updates**:
- [`project-documentation/README.md`](../../project-documentation/README.md) (manifest) — add 2026-05-09 modernization note in Provenance section
- [`security-backlog.md`](security-backlog.md) — mark #30 as closed via 2026-05-09 stack modernization; update "39/42" to "40/42" or add date-stamped note
- [`project-improvement-plan.md`](project-improvement-plan.md) — append 2026-05-09 modernization to status delta

**Exit criteria**: `grep -rn "Java 17\|17-alpine\|JDK 17" --include="*.md" --include="*.sh" --include="Dockerfile" --include="pom.xml"` returns ONLY historical/phase-description matches that we intentionally kept.

---

### Phase 7: Live integration validation (30 min)

After all edits, full end-to-end verification.

1. `mvn clean test` — all 589 tests pass
2. `mvn spring-boot:run -Dspring.profiles.active=local` — boots cleanly; check logs for warnings
3. `docker build -t drools-rule-engine:latest .` — image builds; size near ~347 MB
4. `docker run -d -p 8080:8080 -p 8081:8081 -e RULE_SOURCE=memory drools-rule-engine:latest`
5. `curl http://localhost:8080/admin/health` → expect 200 UP
6. Run all 10 sample rules from the cookbook — outputs match
7. 10 rule refreshes — `curl /admin/memory/info | jq` shows stable heap
8. 7 security headers still present:
   ```bash
   curl -I http://localhost:8080/admin/health | \
     grep -E "X-Content-Type|X-Frame|Content-Security|Strict-Transport|X-XSS|Referrer-Policy|Permissions-Policy"
   ```

**Exit criteria**: Health = UP, all 10 sample rule outputs match, memory stable, all 7 security headers present.

---

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Drools 10 not GA on Maven Central | Low — release notes site exists at 10.0.x URL | Medium — fall back to 9.x latest | Phase 1 step 3 verifies on Maven Central |
| Drools 10 deprecates traditional DRL | **Confirmed not** ("All APIs and DRL syntax are compatible") | — | — |
| Drools 10 atomic-swap pattern broken | Low — KieContainer API unchanged per migration guide | Medium — would invalidate ADR-003 | Phase 5e memory test catches; fall back to KieScanner if needed |
| `KieContainer.dispose()` lifecycle changed | Low — migration guide doesn't flag this | Medium — would re-introduce OOM | Phase 5e memory test catches |
| **DRL behavior differences under executable model** (the actual remaining risk) | **Medium** — three documented diffs (casts, generics, wrapper coercion) | Low — fixable per-rule | Phase 5c structured review of all 10 DRL files |
| Java 25 strict reflection breaks Drools 10 internals | Low — Drools 10 baselines on JDK 17 (same encapsulation rules as 25) | Medium | Phase 5e catches; mitigate with `--add-opens` JVM flags in Dockerfile if needed |
| `drools-engine` aggregator missing a transitive dep | Low | Low | `mvn dependency:tree` after coordinates swap; pin missing artifact explicitly |
| Spring Boot 3.7 has a property rename we missed in `application.yml` | Medium | Low — Spring logs warnings | Read 3.3/3.4/3.5/3.6/3.7 migration guides as part of Phase 4 |
| `@MockBean` migration breaks more than the 1 file found | Low | Low — grep was authoritative | Re-grep after Phase 4 |
| `amazoncorretto:25-alpine-jdk` Docker tag doesn't exist | Low | Low — fallback chain documented | Phase 1 verifies tag |
| 1–2 tests fail due to behavior changes (timing, mocking) | Medium | Low — investigate individually | 589-test suite is the safety net |
| Java 25 GC behavior differs significantly from 17 | Low | Medium — performance regression possible | Memory-leak test scenarios from prior Phase 6 are the rerun template |
| Doc sweep accidentally rewrites historical phase descriptions | Low | Low — readability problem, not correctness | Spot-check after sweep; "historical references" list is the keep-list |

---

## Rollback strategy

Single-commit-per-phase strategy is recommended so individual phases can be reverted independently:

- **Phase 2 (pom only)**: if Drools breaks, revert pom and stay on Java 17 stack
- **Phase 3 (Dockerfile + scripts)**: independent; can revert if image fails to build
- **Phase 4 (test fixes)**: independent
- **Phase 6 (docs)**: independent

Final commit (Phase 7) ties everything together. If Phase 7 reveals a serious issue, revert from there.

---

## Files modified — complete list

**Build/runtime (5 files)**:
- [`pom.xml`](../../pom.xml) — Java target, Spring Boot, Lombok, all plugin versions, Maven Enforcer rule
- [`Dockerfile`](../../Dockerfile) — both base images
- [`set-java-env.sh`](../../set-java-env.sh) — Java 17 → 25
- [`docker-compose.yml`](../../docker-compose.yml) — verify any Java refs (likely none)
- [`src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java`](../../src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java) — `@MockBean` → `@MockitoBean`

**Possibly edited if Phase 4/5 surfaces issues (TBD)**:
- [`src/main/resources/application.yml`](../../src/main/resources/application.yml) — Spring Boot 3.7 property renames if any
- 0–N files in [`sample-rules/`](../../sample-rules/) — only if Phase 5c surfaces executable-model behavior diffs

**Doc files (~14+ active + 2 new ADRs)**:
- [`README.md`](../../README.md), [`CLAUDE.md`](../../CLAUDE.md)
- [`00-system-overview.md`](../../project-documentation/00-system-overview.md), [`03-tech-stack.md`](../../project-documentation/03-tech-stack.md), [`06-deployment.md`](../../project-documentation/06-deployment.md), [`07-docker-and-compose.md`](../../project-documentation/07-docker-and-compose.md), [`16-drl-sandboxing.md`](../../project-documentation/16-drl-sandboxing.md), [`24-jvm-optimization.md`](../../project-documentation/24-jvm-optimization.md), [`27-development-setup.md`](../../project-documentation/27-development-setup.md), [`31-troubleshooting.md`](../../project-documentation/31-troubleshooting.md), [`32-getting-started.md`](../../project-documentation/32-getting-started.md), [`34-java-setup-guide.md`](../../project-documentation/34-java-setup-guide.md), [`37-glossary.md`](../../project-documentation/37-glossary.md)
- [`api-reference/openapi.yml`](../../project-documentation/api-reference/openapi.yml)
- [`README.md`](../../project-documentation/README.md) (manifest)
- [`36-architecture-decision-records.md`](../../project-documentation/36-architecture-decision-records.md) — new ADR-013 + ADR-014

**.ai-workspace updates**:
- [`security-backlog.md`](security-backlog.md) — close #30
- [`project-improvement-plan.md`](project-improvement-plan.md) — note 2026-05-09 modernization in delta

**Out of scope** (do NOT touch):
- `.ai-workspace/documentations/CHECKLIST.md`, `CODE_FINDINGS.md`, `DOCUMENTATION_PLAN.md`, `SUMMARY.md` — historical artifacts
- `.ai-workspace/ai-summary/`, `ai-initial-context/`, `compact-logs/`, `snap-memory/` — historical
- `ai-instructions/` — generic templates, not project-specific
- All application Java code (no behavior changes; only test mock annotation)

---

## Out of scope (deliberately deferred)

- **Adopting virtual threads** in application code — Java 21+ supports them; enabling in Spring Boot is a separate behavioral change. Custom rule-execution thread pool is well-tuned; touching it should be its own decision after baseline.
- **Drools 9.x migration** — only triggered if Drools 10 isn't GA. If 10 is GA, skip.
- **Adopting Drools Rule Units / OOPath** — explicitly rejected by ADR-001; remains rejected.
- **JMeter performance suite** at Java 25 — still on the backlog from Week 2 Day 10 of `project-improvement-plan.md`; useful but not required for this upgrade.
- **CI/CD GitHub Actions** — separate Week 3 Day 14 backlog item.
- **Prometheus + Grafana stack** — separate Week 3 backlog item.

---

## Final verification checklist

When this plan is fully done:

- [ ] `java -version` shows 25.x; `mvn -version` shows Java 25
- [ ] `mvn clean install` succeeds; build is reproducible only on Java 25 (Maven Enforcer fails on 24 or 26)
- [ ] All 589 tests pass — `Tests run: 589, Failures: 0, Errors: 0`
- [ ] Docker image builds; size near 347 MB; container starts; `/admin/health` returns 200 UP
- [ ] All 10 sample rules return documented outputs (cookbook curls match byte-for-byte)
- [ ] Memory stable across 10 rule refreshes
- [ ] All 7 security headers still present
- [ ] No `Java 17` references in active docs (only historical phase descriptions and ADRs preserved)
- [ ] [`03-tech-stack.md`](../../project-documentation/03-tech-stack.md) version table matches pom.xml exactly
- [ ] New ADR-013 written (Java 25 + Spring Boot 3.7)
- [ ] New ADR-014 written (Drools 10 migration)
- [ ] [ADR-003](../../project-documentation/36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal) updated only if implementation changed (likely no edit)
- [ ] Security finding #30 marked closed in [`security-backlog.md`](security-backlog.md); "39/42" updated or annotated
- [ ] [`project-improvement-plan.md`](project-improvement-plan.md) delta updated
- [ ] Memory-leak validation re-run in Drools 10: 2000-refresh test produces <40 MiB growth (matching original benchmark)
- [ ] All 10 sample rules from [`19-sample-rules-cookbook.md`](../../project-documentation/19-sample-rules-cookbook.md) produce **byte-identical** outputs vs. pre-migration

---

## Companion files

- [`stack-modernization-checklist.md`](stack-modernization-checklist.md) — actionable phase-by-phase checklist with checkboxes; track progress here as work proceeds
- [`security-backlog.md`](security-backlog.md) — the deferred security findings; #30 closes as a result of this work
