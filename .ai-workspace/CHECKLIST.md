# Documentation Overhaul — Execution Checklist

**Total estimated effort**: 80–110 hours across 5 phases.

Each phase produces working deliverables. We can pause/review between phases.

Legend: `[ ]` = pending, `[x]` = done, `[~]` = in progress, `[s]` = skipped/deferred.

---

## Phase 0 — Plan & Workspace (3–5 hrs)  ·  ✱ APPROVAL GATE ✱

Setup. Output: planning artifacts. After this phase the user reviews everything before writing any docs.

- [x] Run 3 Explore agents (deep code, tests/scripts/configs, docs audit)
- [x] Synthesize findings into plan
- [x] Write `.ai-workspace/SUMMARY.md`
- [x] Write `.ai-workspace/DOCUMENTATION_PLAN.md` (this checklist's source of truth)
- [x] Write `.ai-workspace/CHECKLIST.md` (this file)
- [x] Write `.ai-workspace/CODE_FINDINGS.md` (initial known findings; appended during Phase 1+)
- [x] **APPROVAL GATE — wait for user sign-off before Phase 1**

---

## Phase 1 — Foundation & Corrections (15–20 hrs)

Goal: existing docs stop being wrong; foundation in place.

### Phase 1a — Mechanical (1–2 hrs)  ✅ COMPLETE
- [x] Move `api-documentation.yml` from repo root to `project-documentation/api-reference/openapi.yml`
- [x] Rename existing 13 docs to numbered scheme:
  - [x] `architecture.md` → `04-architecture.md`
  - [x] `configuration.md` → `08-configuration.md`
  - [x] `deployment.md` → `06-deployment.md`
  - [x] `java-setup-guide.md` → `34-java-setup-guide.md`
  - [x] `jvm-optimization.md` → `24-jvm-optimization.md`
  - [x] `memory-monitoring-guide.md` → `25-memory-monitoring-guide.md`
  - [x] `rule-development.md` → `17-rule-development.md`
  - [x] `rule-generation-prompt.md` → `20-rule-generation-prompt.md`
  - [x] `rule-generation-prompt-enhanced.md` → `21-rule-generation-prompt-enhanced.md`
  - [x] `rule-generation-prompt-concise.md` → `22-rule-generation-prompt-concise.md`
  - [x] `rule-language-reference.md` → `23-rule-language-reference.md`
  - [x] `simple-start.md` → `33-simple-start.md`
  - [x] `troubleshooting.md` → `31-troubleshooting.md`
- [x] Verify no internal cross-links inside renamed files broken (8 stale links fixed in 06 + 31)

### Phase 1b — Revisions (8–10 hrs)  ✅ COMPLETE
Drift fixes from CODE_FINDINGS.md applied to existing files.

- [x] **04-architecture.md** revision
  - [x] Replace every `ruleId` with `rule_id` in JSON body examples (MDC log keys left as-is — correct)
  - [x] Update 7 security-header values to actual code (table added)
  - [x] Clarify port architecture (`/admin/*` on 8080, not separate)
  - [x] Add filter chain order showing `[-1, 0, 1, unordered]`
  - [x] Add "Component dependency map" section with failure-mode table
  - [x] Add "Atomic-Swap Rule Loading Pattern" explanation
  - [x] Add "TOCTOU-Safe Rule Lookup" explanation
  - [x] Remove hallucinated `GET /admin/rules/{ruleId}` endpoint
  - [x] Fix `@ValidData` → `@ValidRuleData` (correct annotation name)
  - [x] Update Rate Limiting Architecture with multi-tier client identification
- [x] **06-deployment.md** revision
  - [x] Replace example Dockerfile with actual (Corretto 17 Alpine multi-stage)
  - [x] Fix port-architecture diagram (admin on 8080, not separate service)
  - [x] Update RULE_SOURCE comment with actual valid values + default
- [x] **17-rule-development.md** revision
  - [x] Add prominent "traditional DRL syntax only" header note + sandbox cross-link
  - [x] Replace Drools 7.74.1 doc links with 8.44.0 equivalents
- [x] **23-rule-language-reference.md** revision
  - [x] Add prominent header disclaimer at top
  - [x] Tag 6 unused-feature sections with "[Not used in this project]"
- [x] **24-jvm-optimization.md** revision
  - [x] Remove deprecated `-XX:+UseCGroupMemoryLimitForHeap`
  - [x] Add note explaining the deprecation and Java 17 auto-detection

### Phase 1c — New foundation docs (6–8 hrs)  ✅ COMPLETE
- [x] **01-project-overview.md** — what/why/business value, performance table, audience reading paths
- [x] **02-project-structure.md** — annotated directory tree of the entire repo
- [x] **03-tech-stack.md** — every dependency with version + rationale + upgrade considerations
- [x] **05-environments-and-profiles.md** — 4 Spring profiles with full override matrix

### Phase 1 — Verification gate  ✅ PASSED
- [x] `grep -rn '"ruleId"' project-documentation/` returns only the MDC log example (camelCase is correct there)
- [x] All 13 renames complete; numbered scheme in place
- [x] No remaining drift items from F-001 through F-012 (Phase 1 scope)
- [x] No stale internal cross-links between docs
- [x] 17 docs present (13 renamed + 4 new)
- [x] CODE_FINDINGS.md reviewed — *no new findings during Phase 1; existing F-001 through F-030 cover everything seen*

### Phase 1 review (post-completion self-audit)  ✅ COMPLETE
- [x] Source-code citations spot-checked — all verified except one (caught and fixed)
- [x] Filter @Order values verified in actual code (-1, 0, 1, no-order)
- [x] Versions in 03-tech-stack.md cross-checked vs `pom.xml` (Spring 3.2.5, Drools 8.44.0.Final, AWS SDK 2.20.56) — all match
- [x] **Fix**: `RateLimitingFilter.java:65-94` citation in 04-architecture.md → corrected to `:69-94` (multi-tier client ID is in `getClientIdentifier()`, not `shouldApplyRateLimit()`)
- [x] **Fix**: 02-project-structure.md "Where to find what" table — converted to 38 clickable file links (was zero) for AI-coding-agent navigation
- [x] Cross-link audit — 18 missing targets, all are future docs (Phase 2-5); tracked
- [x] Doc lengths: 01 (100L/10.8KB) and 05 (284L/15.3KB) under planned line counts but content density fine — line count was a bad estimator; switching to content-density assessment going forward

---

## Phase 2 — APIs, Security, Infrastructure (20–25 hrs)  ✅ COMPLETE

Goal: external integrators self-sufficient. AI agents can answer API questions.

### Phase 2a — APIs & integration (10–12 hrs)  ✅ COMPLETE
- [x] **07-docker-and-compose.md** — JAVA_OPTS line-by-line + compose service-by-service (477 lines)
- [x] **09-environment-variables-reference.md** — exhaustive 67 env vars verified by `grep '@Value'` + `application.yml` parse (375 lines)
- [x] **10-api-reference.md** — prose wrapper for OpenAPI yml (624 lines) — live-verified against running stack; one example (RuleInfo response) corrected after live check
- [x] **11-integration-guide.md** — code examples in curl/Python/Java/Node.js (599 lines)
- [x] **12-error-code-catalog.md** — every error code with HTTP status + fix (441 lines)
- [x] **13-rate-limiting-and-throttling.md** — multi-tier client ID + admin exemption (367 lines)

### Phase 2b — Security trio (5–7 hrs)  ✅ COMPLETE
- [x] **14-security-architecture.md** — 8-layer model with citations (359 lines)
- [x] **15-admin-authentication.md** — admin key flow + dev-mode bypass (336 lines)
- [x] **16-drl-sandboxing.md** — definitive sandbox rules (435 lines) — every allowed/blocked claim cited line-by-line from `DrlSanitizer.java`

### Phase 2 — Verification gate  ✅ PASSED
- [x] 26 docs present (17 from Phase 1 + 9 new)
- [x] Live security headers verification: all 7 headers present with exact documented values (`X-XSS-Protection: 0`, `Cache-Control: no-store`, `CSP: default-src 'none'; frame-ancestors 'none'`, etc.)
- [x] Live rate-limit headers verification: `X-RateLimit-Limit: 1000`, `X-RateLimit-Remaining` decrements, `X-RateLimit-Reset-After` present
- [x] Live error code verification: `RULE_NOT_FOUND` with message "Rule not found: nonexistent" matches doc
- [x] Live admin-exemption verification: `/admin/health` returns NO `X-RateLimit-*` headers (rate limiter skips admin paths)
- [x] Cross-link audit: 11 missing targets remain (down from 18) — all are Phase 3-5 docs (expected)
- [x] CODE_FINDINGS.md updated with new finding F-031 (`@Value` default mismatch for `drools.validation.data.max-number-value`)

### Phase 2 review (post-completion self-audit)  ✅ COMPLETE
- [x] Cross-checked DrlSanitizer counts against actual code via Python regex parser
- [x] **Found 5 wrong count claims** — same error pattern in 3 different docs:
  - 16-drl-sandboxing.md: "18 specific prefixes" → corrected to **20**
  - 14-security-architecture.md: "18 import prefixes" → **20**, "13 blocked class names" → **12**, "15 blocked method calls" → **19**
  - 04-architecture.md (Phase 1 doc): "Allowed imports (18 prefixes)" → **20**, "Blocked classes (13)" → **12**, "Blocked methods (15)" → **19**
  - 09-environment-variables-reference.md: "~67 distinct env vars" → **66** (the actual table count)
- [x] Tables enumerating items were correct — only the prose count claims were wrong (so the substance was accurate, just the summary numbers were slightly off)
- [x] Spot-checked controller endpoints in 10-api-reference vs `@GetMapping`/`@PostMapping` annotations — all 10 endpoints documented match real signatures
- [x] Cross-checked 9 error codes (8 from `GlobalExceptionHandler`, 3 unique from filters with 1 overlap) — all 9 documented in 12-error-code-catalog
- [x] Java code in 11-integration-guide uses Java 17 records (project is on Java 17) — syntactically plausible
- [x] Verified env var count: 57 unique `@Value` annotations + ~9 vars consumed by Spring Boot conventions (`SERVER_PORT`, `MAX_HTTP_REQUEST_SIZE`, etc.) = ~66 documented vars. Coverage looks complete.
- [ ] Every error code in `12-error-code-catalog.md` reproducible
- [ ] CODE_FINDINGS.md updated with any new drift

---

## Phase 3 — Rule Authoring & Performance (15–20 hrs)

Goal: rule authors self-sufficient. Performance issues diagnosable.

### Phase 3a — Rule authoring (7–10 hrs)
- [ ] **18-rule-id-and-storage-layout.md** — ID format + S3 mapping (~250 lines, 1.5 hr)
- [ ] **19-sample-rules-cookbook.md** — all 10 rules with live curl tests (~600 lines, 4 hr) — every example produces documented output against running stack
  - [ ] pricing.discount.simple
  - [ ] pricing.discount.vip (document the discount-stacking surprise: VIP+simple = $72 not $80)
  - [ ] pricing.discount.bulk
  - [ ] pricing.discount.first-time
  - [ ] pricing.shipping.standard
  - [ ] pricing.shipping.express
  - [ ] validation.customer.age
  - [ ] validation.customer.credit
  - [ ] seasonal.holiday.discount
  - [ ] seasonal.holiday.blackfriday

### Phase 3b — Performance trio (5–7 hrs)
- [ ] **26-performance-tuning-runbook.md** — diagnostic decision tree (~500 lines, 3 hr)
- [ ] **29-circuit-breakers-and-resilience.md** — Resilience4j wiring + state behavior (~400 lines, 2 hr)
- [ ] (24, 25 already covered in Phase 1 / kept)

### Phase 3 — Verification gate
- [ ] Every cookbook example tested against live stack
- [ ] Discount-stacking behavior documented and verified ($100 VIP order → $72 final)
- [ ] Performance runbook decision tree complete (each branch leads to action)
- [ ] CODE_FINDINGS.md updated

---

## Phase 4 — Operations, Development, Advanced (20–30 hrs)

Goal: full coverage for all four audiences plus AI.

### Phase 4a — Development & operations (10–13 hrs)
- [ ] **27-development-setup.md** — onboarding + patterns + build/CI (~600 lines, 4 hr)
- [ ] **28-testing-guide.md** — test suite map + how-to (~500 lines, 3 hr) — list all tests that prove documented behavior
- [ ] **30-runbooks-and-monitoring.md** — operational procedures + monitoring setup (~700 lines, 4 hr)
- [ ] **32-getting-started.md** — <30-min quickstart (~300 lines, 1.5 hr) — live-tested

### Phase 4b — Reference & advanced (10–17 hrs)
- [ ] **35-faq.md** — 30+ Q&A drawn from gaps (~400 lines, 3 hr)
- [ ] **36-architecture-decision-records.md** — 12 ADRs + extension points appendix (~700 lines, 5 hr)
- [ ] **37-glossary.md** — Drools + project + infra terms (~250 lines, 2 hr)

### Phase 4 — Verification gate
- [ ] Every "implicit spec" claim in docs has a corresponding test cited in `28-testing-guide.md`
- [ ] Every ADR has rationale traceable to a snap-memory entry, commit, or code citation
- [ ] CODE_FINDINGS.md complete

---

## Phase 5 — Verification & Integration (5–10 hrs)

Goal: NotebookLM-ready corpus, verified end-to-end.

### Phase 5a — Final docs (1–2 hrs)
- [ ] **00-system-overview.md** — written **last** (~150 lines, 1 hr) — links to every other doc

### Phase 5b — Cross-corpus audit (2–4 hrs)
- [ ] Cross-link audit: `grep -E '\(\d{2}-' project-documentation/*.md` — every numbered link has a target file
- [ ] Citation audit: spot-check 20 random `path:line` citations against actual code
- [ ] Acronym audit: every acronym used in any doc is defined in `37-glossary.md`
- [ ] No orphan claims: every behavior claim traceable to code or test
- [ ] Frontmatter present on every doc: Audience, Last verified, Related docs

### Phase 5c — Live verification (1–2 hrs)
- [ ] Run every curl example in cookbook + integration guide + getting-started against live stack; capture outputs match docs
- [ ] Verify `19-sample-rules-cookbook.md` predictions for all 10 rules

### Phase 5d — Repository integration (1–2 hrs)
- [ ] Update root `README.md` to point at `project-documentation/00-system-overview.md`
- [ ] Update root `CLAUDE.md` tripwire to mention the new structure (the existing tripwire already says read 3 files first; update reference to point at new doc tree)
- [ ] Add `project-documentation/README.md` as a NotebookLM upload manifest (list every file with one-line description)
- [ ] Update `.ai-workspace/SUMMARY.md` with final stats (line counts, file count, total hours actual)

### Phase 5 — Final verification gate
- [ ] All 34 docs exist in `project-documentation/`
- [ ] All 34 docs uploadable as NotebookLM corpus (test upload)
- [ ] Sample NotebookLM queries return correct answers with correct citations
- [ ] All 4 audiences can navigate the corpus successfully (mental walkthrough)
- [ ] CODE_FINDINGS.md complete and ready for separate triage by user

---

## Dependencies between docs

These docs must be written in a specific order due to cross-link dependencies:

```
03-tech-stack ───────────┐
                         ↓
01-project-overview ──→ 04-architecture ──→ 14-security-architecture
                         ↓                        ↓
05-environments ───→ 06-deployment ──→ 07-docker-and-compose
                         ↓
                    08-configuration ──→ 09-env-vars-reference
                                              ↓
                                         10-api-reference ──→ 11-integration ──→ 12-error-codes
                                                                      ↓
                                                                 13-rate-limiting

16-drl-sandboxing ──→ 17-rule-development ──→ 18-rule-id-layout ──→ 19-sample-rules-cookbook
                                                                            ↓
                                                                       20,21,22 (kept prompts)
                                                                            ↓
                                                                       23-rule-language-reference

24-jvm-opt ──→ 25-memory ──→ 26-performance-runbook
                                  ↓
27-dev-setup ──→ 28-testing ──→ 29-circuit-breakers
                                       ↓
                                  30-runbooks-monitoring
                                       ↓
                                  31-troubleshooting (kept)

32-getting-started ──→ 33-simple-start (kept) ──→ 34-java-setup (kept)

36-ADRs ──→ 37-glossary

35-faq ──→ (depends on most other docs)
00-system-overview ──→ (depends on ALL other docs — written LAST)
```

---

## Skipped / deferred (out of scope)

- [s] JMeter performance test suite — Phase 4.3 deferred per existing project plan; not part of this docs work
- [s] Terraform IaC — user explicitly out of scope
- [s] Code changes — every finding goes to CODE_FINDINGS.md for separate triage
- [s] Documentation for `ai-instructions/`, `.ai-workspace/`, `.claude/`, `.vscode/`, `gc-logs/`, `heap-dumps/` — explicitly excluded
- [s] Migration guide (legacy → this service) — no legacy system to migrate from
- [s] SDK packages — no SDK exists yet
- [s] Disaster recovery / backup plan — defer until S3 versioning policy decided
- [s] Multi-tenant configuration — not applicable

---

## Tracking notes

- Each `[ ]` represents a discrete deliverable. Mark `[x]` when complete.
- Update `CODE_FINDINGS.md` whenever a new drift is discovered during writing.
- Each verification gate is a hard stop — don't proceed to next phase until prior gate is green.
- If a doc takes >50% over its time estimate, surface to user before continuing (scope creep signal).

---

**Total task count**: 70+ discrete checkable items.
**Critical path**: Phase 0 → Phase 1a (renames) → Phase 1b (revisions) → all parallel.
**Parallelizable**: Within each phase, most docs can be written in any order (with deps respected).
