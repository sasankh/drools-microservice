# Checklist: Clear the 11 SonarQube issues

**Plan:** [`sonar-issues-fix-plan.md`](sonar-issues-fix-plan.md) · **Created:** 2026-08-19
Baseline scan: 11 open (1 BUG + 10 CODE_SMELL). Target: 0.

- [x] Write plan + checklist to `.ai-workspace/project-plans/`
- [x] **S3077 / BUG** — `RedisCachedRuleStorage.delegate` → `AtomicReference<RuleStorage>` (field + setter + guard + 9 call sites)
- [x] **S7467** — `RuleExecutor.java:115` `ignored` → `_`
- [x] **S4276** — `@SuppressWarnings("java:S4276")` + comment on `ruleExists`
- [x] **S5778 ×2** — `AdminAuthFilterTest` hoisted `envWithProfile(...)` out of assert lambdas
- [x] **S2699 ×2** — `AdminAuthFilterTest` added `assertThatCode(...).doesNotThrowAnyException()`
- [x] **S5778 ×2** — `RuleExecutorTest` hoisted `new HashMap<>()` out of assert lambdas
- [x] **S5853** — `RefreshEventTest:33` chained the three `contains(...)` assertions
- [x] **S1130** — `RedisCachedStorageIntegrationTest:233` removed `throws Exception`
- [x] Docker `mvn -B clean spotless:apply verify` → **BUILD SUCCESS** (tests green; spotless/spotbugs/jacoco gates pass; AtomicReference refactor + IT `throws` removal compile clean)
- [x] Re-scan SonarQube → **0 open issues** (was 11: 1 BUG + 10 CODE_SMELL). Quality Gate back to 0/0.
