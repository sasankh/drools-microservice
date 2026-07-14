# Pre-compiled kjar Deployment Plan

**Status:** Draft — not yet started
**Created:** 2026-05-11
**Owner:** TBD
**Driver:** Cold-start performance at production scale (3–5 ECS tasks × 10,000+ rules)

---

## 1. Context & motivation

### 1.1 The problem

At the target deployment scale, the current **runtime-compilation** model produces unacceptable cold-start behaviour:

- Load-test baseline: 1,000 rules compile in ~46s → **10,000 rules ≈ 7–8 minutes per task**
- ECS auto-scaling: a new task takes 8 minutes to become serving-ready
- Full `/admin/refresh-rules` is 8 minutes of degraded serving on every refresh
- Storage layer fetches 10,000 individual `.drl` files from S3 on every cold start

### 1.2 Root cause

`DroolsConfig` builds an empty `KieContainer` at startup; `RuleCompiler` then re-compiles every DRL into a fresh `KieFileSystem` → `KieBuilder.buildAll()` cycle at runtime. The compilation work (DRL → Java AST → bytecode → KieBase) repeats on every instance, every refresh.

### 1.3 Solution

Pre-compile all rules in CI into a versioned **kjar** (Drools-flavoured `.jar` containing compiled rule classes + `kmodule.xml`). Runtime loads the kjar via classloader — milliseconds instead of minutes.

### 1.4 Expected outcome

| Metric | Today | With kjar |
|---|---|---|
| Cold start (10k rules) | ~8 min | **~5–15 seconds** |
| Full refresh (10k rules) | ~8 min | **~5–15 seconds** |
| Cross-instance load | 10,000 S3 GETs per task | 1 S3 GET per task |
| Deployment artifact | Implicit (S3 DRLs at runtime) | Versioned, immutable, auditable |

---

## 2. Current state — verified code trace

| Component | File | Behaviour |
|---|---|---|
| `KieContainer` bean | `DroolsConfig.java:38–58` | Built from empty `KieFileSystem` at startup. ReleaseId `com.company.drools:rules-runtime:1.0.0`. |
| Rule compilation | `RuleCompiler.java:35–97` | Per-load `KieFileSystem` populated with all DRLs, `buildAll()`, ReleaseId `1.0.N` where N is a counter. |
| Container update | `DroolsEngineService.java:182–183` | `kieContainer.updateToVersion(newReleaseId)` — atomic in-place swap; old module cleaned at `:224` via `kieRepository.removeKieModule()`. |
| DRL source | `S3RuleStorage.java:74` | Fetches raw `.drl` bytes per rule ID via `s3Client.getObject()`. |
| Rule ID → S3 key | `S3RuleStorage.java:322` | `pricing.discount.simple` → `pricing/discount/simple.drl` |
| Refresh flow | `AdminController.java:401–447` | Bulk: `storage.getAllRules()` → `droolsEngineService.loadRules(rules)` → single compilation cycle. |
| pom packaging | `pom.xml:11` | `<packaging>jar</packaging>` (Spring Boot uber-jar). No `kie-maven-plugin`. |

---

## 3. Target architecture

### 3.1 Two artifacts

1. **Service jar** (`drools-microservice`) — unchanged. Spring Boot fat jar containing the service code.
2. **Rules kjar** (`drools-rules-kjar`) — NEW. Built by a separate Maven module, pushed to S3 as a versioned binary.

The service jar is now decoupled from rule content — same image runs in stage and prod, only the kjar version differs.

### 3.2 New Maven module: `rules-kjar/`

```
rules-kjar/
├── pom.xml                                     # <packaging>kjar</packaging>
└── src/main/resources/
    ├── META-INF/kmodule.xml                    # KieBase + KieSession definitions
    └── rules/                                  # mirrors current sample-rules/ layout
        ├── pricing/discount/simple.drl
        ├── pricing/discount/bulk.drl
        ├── ...
        └── (10,000 .drl files)
```

### 3.3 `pom.xml` for the kjar module

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.company.drools</groupId>
  <artifactId>rules-kjar</artifactId>
  <version>${revision}</version>            <!-- timestamp-based, set in CI -->
  <packaging>kjar</packaging>

  <properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
    <drools.version>10.2.0</drools.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.drools</groupId>
      <artifactId>drools-engine</artifactId>
      <version>${drools.version}</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.kie</groupId>
        <artifactId>kie-maven-plugin</artifactId>
        <version>${drools.version}</version>
        <extensions>true</extensions>     <!-- enables kjar packaging -->
        <configuration>
          <failBuildOnError>true</failBuildOnError>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

### 3.4 `kmodule.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<kmodule xmlns="http://www.drools.org/xsd/kmodule">
  <kbase name="rules" packages="*">
    <ksession name="rules-session" type="stateless"/>
  </kbase>
</kmodule>
```

Single `KieBase` containing every rule. Matches the current "one container, all rules" model so the existing `RuleExecutor.executeRule()` path doesn't need restructuring.

### 3.5 Source-of-truth options

| Option | Storage | Rule edit flow | Recommendation |
|---|---|---|---|
| A. Git as SoT | DRLs in `rules-kjar/src/main/resources/rules/` | PR → review → merge → CI builds kjar | **Recommended.** Code-review for rule changes, audit trail, branching for environment-specific rule sets. |
| B. S3 as SoT (current) | DRLs in S3, CI pulls before build | Upload to S3 → trigger build job | Keeps current authoring UX but loses review/audit. |
| C. Hybrid | Git for baseline, S3 for hotfix overlays | PR for normal, S3 upload for emergency | More complex, more flexible. Future work. |

This plan assumes **Option A**. Option C is documented as a future extension.

---

## 4. Build pipeline (CI/CD)

### 4.1 GitHub Actions workflow

```yaml
name: Build rules kjar

on:
  push:
    branches: [main]
    paths:
      - 'rules-kjar/**'
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: write          # for OIDC AWS auth
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - name: Generate version
        id: ver
        run: echo "REVISION=1.0.$(date -u +%Y%m%d%H%M%S)" >> $GITHUB_ENV
      - name: Build kjar
        working-directory: rules-kjar
        run: mvn -B clean package -Drevision=$REVISION
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: us-east-1
      - name: Upload to S3
        run: |
          aws s3 cp rules-kjar/target/rules-kjar-$REVISION.jar \
            s3://${RULE_BUCKET_NAME}/kjars/rules-kjar-$REVISION.jar
          aws s3 cp rules-kjar/target/rules-kjar-$REVISION.jar \
            s3://${RULE_BUCKET_NAME}/kjars/LATEST.jar
          echo "$REVISION" > version.txt
          aws s3 cp version.txt s3://${RULE_BUCKET_NAME}/kjars/LATEST.version
      - name: Notify deployment (stage)
        run: |
          curl -X POST https://stage.drools.example.com/admin/refresh-rules \
            -H "X-Admin-API-Key: ${{ secrets.ADMIN_API_KEY_STAGE }}" \
            -d "{\"version\":\"$REVISION\"}"
```

### 4.2 Versioning

`1.0.YYYYMMDDHHMMSS` — sortable, debuggable, unique. Could swap for content-hash if reproducible builds become a requirement.

### 4.3 S3 layout

```
s3://drools-rules/
├── kjars/
│   ├── rules-kjar-1.0.20260511020000.jar      # immutable historical versions (retain N latest)
│   ├── rules-kjar-1.0.20260511030000.jar
│   ├── rules-kjar-1.0.20260511040000.jar
│   ├── LATEST.jar                              # copy of current
│   └── LATEST.version                          # text file with current version string
└── (legacy per-DRL storage retained for hybrid/fallback during transition)
```

S3 lifecycle policy: retain 30 newest kjars; archive or delete older.

---

## 5. Runtime loading

### 5.1 New `RuleStorage` impl — `KjarRuleStorage`

```java
@Component
@ConditionalOnProperty(name = "rule.source", havingValue = "kjar")
public class KjarRuleStorage implements RuleStorage {

  private final S3Client s3Client;
  private final String bucketName;
  private final String keyPrefix;          // "kjars/"
  private final KieServices kieServices;
  private final AtomicReference<ReleaseId> currentReleaseId = new AtomicReference<>();
  private final Map<String, Rule> ruleMetadataCache = new ConcurrentHashMap<>();

  public Optional<KieModule> loadLatestKjar() {
    String version = readVersionPointer();  // GET kjars/LATEST.version

    if (currentReleaseId.get() != null
        && currentReleaseId.get().getVersion().equals(version)) {
      return Optional.empty();              // already current
    }

    byte[] kjarBytes = downloadKjar(version);  // GET kjars/rules-kjar-{version}.jar
    Resource resource = kieServices.getResources().newByteArrayResource(kjarBytes);
    KieModule kieModule = kieServices.getRepository().addKieModule(resource);
    currentReleaseId.set(kieModule.getReleaseId());

    rebuildMetadataCache(kieModule);
    return Optional.of(kieModule);
  }

  public Optional<KieModule> loadSpecificKjar(String version) {
    // Used for rollback. Same as loadLatestKjar but with explicit version.
  }

  // RuleStorage interface methods — synthesise Rule objects from the kjar's
  // resource list so AdminController.listRules() etc. keep working.
  @Override public Optional<Rule> getRule(String ruleId) { ... }
  @Override public List<Rule> getAllRules() { ... }
  @Override public boolean ruleExists(String ruleId) { ... }
  @Override public long getTotalRuleCount() { ... }
  @Override public List<String> getRuleIds() { ... }

  // No-op or unsupported in kjar mode
  @Override public void saveRule(Rule rule) {
    throw new UnsupportedOperationException("Rules are immutable in kjar mode");
  }
  @Override public void deleteRule(String ruleId) {
    throw new UnsupportedOperationException("Rules are immutable in kjar mode");
  }
}
```

### 5.2 Adapter in `DroolsEngineService`

```java
public void loadFromKjar(KieModule kieModule) {
  writeLock.lock();
  try {
    ReleaseId oldReleaseId = kieContainer.getReleaseId();
    ReleaseId newReleaseId = kieModule.getReleaseId();

    Results results = kieContainer.updateToVersion(newReleaseId);
    if (results.hasMessages(Message.Level.ERROR)) {
      throw new RuleLoadingException("kjar swap failed: " + results.getMessages());
    }

    if (oldReleaseId != null && !oldReleaseId.equals(newReleaseId)) {
      kieRepository.removeKieModule(oldReleaseId);
    }

    rebuildLoadedRulesMap(kieModule);
  } finally {
    writeLock.unlock();
  }
}
```

The existing `RuleExecutor.executeRule()` path is unchanged.

### 5.3 `DroolsConfig` — conditional bean

```java
@Bean
@ConditionalOnProperty(name = "rule.source", havingValue = "kjar")
public KieContainer kjarKieContainer(KieServices ks, KjarRuleStorage kjarStorage) {
  KieModule initial = kjarStorage.loadLatestKjar()
      .orElseThrow(() -> new IllegalStateException("No kjar available at startup"));
  return ks.newKieContainer(initial.getReleaseId());
}

@Bean
@ConditionalOnProperty(name = "rule.source", havingValue = "s3", matchIfMissing = true)
public KieContainer legacyKieContainer(KieServices ks) {
  // existing implementation unchanged
}
```

### 5.4 Configuration

#### Environment variables

```
RULE_SOURCE=kjar                   # switches storage backend
KJAR_BUCKET=drools-rules           # S3 bucket
KJAR_KEY_PREFIX=kjars/             # path within bucket
KJAR_VERSION=LATEST                # "LATEST" or specific version like "1.0.20260511020000"
KJAR_POLL_INTERVAL_S=0             # 0 = disabled; >0 enables KieScanner-style polling
```

#### `application.yml` additions

```yaml
rule:
  source: ${RULE_SOURCE:s3}              # s3 (default) | kjar | local | memory
kjar:
  bucket: ${KJAR_BUCKET:drools-rules}
  key-prefix: ${KJAR_KEY_PREFIX:kjars/}
  version: ${KJAR_VERSION:LATEST}
  poll-interval-seconds: ${KJAR_POLL_INTERVAL_S:0}
```

---

## 6. Refresh strategy

### 6.1 Full refresh (production path)

1. `POST /admin/refresh-rules` triggered (manual or CI webhook)
2. `KjarRuleStorage.loadLatestKjar()` reads `LATEST.version`, downloads jar if newer
3. `DroolsEngineService.loadFromKjar(kieModule)` performs atomic `kieContainer.updateToVersion()` swap
4. Old `KieModule` removed via `KieRepository.removeKieModule()` (same pattern as today)
5. Refresh completes in ~5–15 seconds (S3 GET + classloader load)

### 6.2 Single-rule refresh

**Not supported in pure kjar mode.** A single rule change requires a new kjar build. Documented as a deliberate trade-off: gain 8min→15s full-refresh; lose single-rule hot-patching.

Two mitigations if single-rule patching is needed:

- **Mitigation A — Hybrid mode (future).** Legacy `S3RuleStorage` still wired; admin endpoint `POST /admin/rules/{ruleId}/override` accepts a DRL and overlays it via the existing `loadOrReplaceRule()` path on top of the kjar baseline. Overrides cleared on next kjar swap. Defer until requested.
- **Mitigation B — Fast-track CI.** A CI job that publishes a new kjar in <60s on single-rule changes. Less code complexity, but requires fast CI.

### 6.3 Optional auto-polling

Set `KJAR_POLL_INTERVAL_S=60` to enable a `KieScanner`-equivalent background task that polls `LATEST.version` every minute and auto-refreshes. Off by default — manual refresh is safer for production.

---

## 7. Backward compatibility & rollback

### 7.1 Feature flag

`RULE_SOURCE=kjar` switches the entire backend. Default remains `RULE_SOURCE=s3` for dev/local. Toggle without redeploying the service jar — restart with new env var.

### 7.2 Rollback paths

- **In place (preferred).** `POST /admin/refresh-rules` with `{"version":"1.0.20260511020000"}` reverts to a previous kjar still in S3.
- **Code-level.** Set `RULE_SOURCE=s3` and restart — falls back to runtime DRL compilation. Slower cold start but guaranteed to work with legacy storage layout. Use only if kjar mode itself is broken.

### 7.3 Dual-write transition

During migration: CI builds both the kjar AND uploads individual DRLs to S3. Service can switch between backends without losing data. After 2–4 weeks of kjar-only running, decommission per-DRL S3 layout.

---

## 8. Phases & effort

| Phase | Scope | Effort | Risk |
|---|---|---|---|
| 1. New `rules-kjar` module + 17 sample rules | Maven module, kmodule.xml, kie-maven-plugin. Verify `mvn package` produces a loadable kjar. Unit test that loads it via `KieServices.newKieContainer`. | 1 day | Low |
| 2. `KjarRuleStorage` + `DroolsConfig` conditional bean | New storage impl, conditional KieContainer bean. Profile-based switch. | 2–3 days | Low — execution path untouched |
| 3. CI pipeline | GitHub Actions workflow: build kjar → upload to S3 → version pointer. Includes 10,000-rule corpus generation for build-time benchmarking. | 1–2 days | Low |
| 4. Integration tests | LocalStack-based: build kjar from `sample-rules/`, upload to LocalStack S3, service loads via kjar mode, executes rules, verifies results match S3 mode. Also test version rollback. | 2 days | Medium |
| 5. Load test the new path | Re-run `scripts/run-load-test.sh --kjar-mode` with 10,000 synthetic rules. Verify cold start <30s, full refresh <30s, no memory regression. | 2 days | Medium — need 10k-rule corpus |
| 6. Documentation + migration guide | Update `27-development-setup.md`, `30-runbooks-and-monitoring.md`, `36-architecture-decision-records.md` (new ADR-015 for kjar). | 1 day | Low |
| 7. Phased rollout | Stage env first with kjar; soak 1 week; then prod. Keep legacy `s3` mode hot-swappable. | 1–2 weeks elapsed | Low |

**Total dev effort:** ~10 dev-days.
**Calendar time:** 3–4 weeks including stage soak.

---

## 9. Risks & open questions

### 9.1 Risk: kjar build time at 10,000 rules

CI compile is **the same compile work** — just moved upstream. If 10,000 rules take 8 minutes in CI, that's the new release latency.

**Mitigation:** investigate parallel compilation flags on `kie-maven-plugin`. Possibly incremental builds keyed off file hash — rebuild only changed packages. Worst case, accept 8min release latency since it's offline.

### 9.2 Risk: kjar memory footprint

Pre-compiled bytecode + RETE network may be larger or smaller than runtime-compiled. Load-test fact: 1,000 runtime-compiled rules → ~47 MB at rest. Need to measure 10k kjar-loaded equivalent.

**Mitigation:** provision `-Xmx4g` per task initially; tune after load test.

### 9.3 Risk: Drools 10 `kie-maven-plugin` stability

Drools 10 is recent; `kie-maven-plugin` 10.x has had documented packaging issues in early releases.

**Mitigation:** run a proof-of-concept in Phase 1 before committing to full plan. If broken, fallback is to build kjar manually via `KieBuilder` invoked from a Maven `exec-maven-plugin` invocation.

### 9.4 Open question: artifact storage

S3 (this plan) vs Maven repo (Nexus, Artifactory, AWS CodeArtifact). Maven repo gives proper `mvn deploy`/`KieScanner` integration but adds infra. S3 is simpler and we already have it. Decision: **S3** unless a Maven repo is already provisioned.

### 9.5 Open question: per-environment rule sets

Stage and prod may want different rules. Options: (1) separate kjars per env, (2) one kjar with env-tagged rules using metadata, (3) overlay model. **Defer.**

### 9.6 Open question: rule authoring UX regression

Today: edit DRL, S3 upload, `/admin/refresh-rules/{id}`, see effect in ~1s. New world: edit DRL, PR, merge, CI build (1–8 min), refresh service.

**Significant UX regression for rapid iteration.** Need a "fast-track" pipeline or hybrid override mechanism (Mitigation A above) if devs iterate frequently in stage.

---

## 10. Critical files

### New

- `rules-kjar/pom.xml`
- `rules-kjar/src/main/resources/META-INF/kmodule.xml`
- `rules-kjar/src/main/resources/rules/**/*.drl` (initially copies of `sample-rules/`)
- `src/main/java/com/company/drools/storage/KjarRuleStorage.java`
- `src/test/java/com/company/drools/storage/KjarRuleStorageTest.java`
- `src/test/java/com/company/drools/integration/KjarIntegrationTest.java`
- `.github/workflows/build-rules-kjar.yml`
- `project-documentation/40-kjar-deployment.md` (new doc)
- ADR-015 entry in `project-documentation/36-architecture-decision-records.md`

### Modified

- `src/main/java/com/company/drools/config/DroolsConfig.java` — conditional bean per `RULE_SOURCE`
- `src/main/java/com/company/drools/core/engine/DroolsEngineService.java` — add `loadFromKjar()` method
- `src/main/java/com/company/drools/storage/StorageFactory.java` — `kjar` case
- `src/main/resources/application.yml` — new properties
- `pom.xml` — convert to multi-module reactor (add `rules-kjar/` and existing service)
- `project-documentation/00-system-overview.md` — architecture overview update
- `project-documentation/04-architecture.md` — replace runtime-compile section
- `project-documentation/26-performance-tuning-runbook.md` — new cold-start guidance
- `project-documentation/36-architecture-decision-records.md` — add ADR-015
- `README.md` — note kjar mode in deployment section
- `CLAUDE.md` — note kjar mode

---

## 11. Verification — acceptance criteria

1. `mvn package` from project root builds both service jar and rules kjar
2. Integration test loads kjar from LocalStack S3, executes all 17 sample rules with expected outputs
3. Load test (`scripts/run-load-test.sh --kjar-mode`) with 10,000 synthetic rules:
   - Cold start P99 < 30s
   - Full refresh P99 < 30s
   - Heap at rest within 2× of legacy s3 mode
   - All existing 597 unit/integration tests still pass
4. Rollback test: deploy version V1, refresh to V2, refresh back to V1 — verify rule outputs flip and back
5. `GET /admin/rules` returns the same shape in both `s3` and `kjar` modes (synthesised `Rule` metadata is compatible)
6. Stage soak: 1 week of normal traffic with `RULE_SOURCE=kjar`, no incidents, latency within baseline

---

## 12. Recommendation summary

- **Do it.** At 3–5 tasks × 10k rules, the cold-start cliff is real (8 min) and only kjar eliminates it.
- **Phase it.** Build the artifact module + new storage path first. Don't touch the execution path. Soak in stage before flipping prod.
- **Keep `s3` mode hot-swappable** as the fallback for at least one quarter post-launch.
- **Defer single-rule hot-patching** unless devs demand it — the bulk-refresh trade-off is acceptable for the cold-start win.

---

## 13. Companion files

Implementation checklist: [`kjar-precompilation-checklist.md`](kjar-precompilation-checklist.md)
