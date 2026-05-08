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
- [ ] Write `.ai-workspace/CODE_FINDINGS.md` (initial known findings; appended during Phase 1+)
- [ ] **APPROVAL GATE — wait for user sign-off before Phase 1**

---

## Phase 1 — Foundation & Corrections (15–20 hrs)

Goal: existing docs stop being wrong; foundation in place.

### Phase 1a — Mechanical (1–2 hrs)
- [ ] Move `api-documentation.yml` from repo root to `project-documentation/api-reference/openapi.yml`
- [ ] Rename existing 13 docs to numbered scheme:
  - [ ] `architecture.md` → `04-architecture.md`
  - [ ] `configuration.md` → `08-configuration.md`
  - [ ] `deployment.md` → `06-deployment.md`
  - [ ] `java-setup-guide.md` → `34-java-setup-guide.md`
  - [ ] `jvm-optimization.md` → `24-jvm-optimization.md`
  - [ ] `memory-monitoring-guide.md` → `25-memory-monitoring-guide.md`
  - [ ] `rule-development.md` → `17-rule-development.md`
  - [ ] `rule-generation-prompt.md` → `20-rule-generation-prompt.md`
  - [ ] `rule-generation-prompt-enhanced.md` → `21-rule-generation-prompt-enhanced.md`
  - [ ] `rule-generation-prompt-concise.md` → `22-rule-generation-prompt-concise.md`
  - [ ] `rule-language-reference.md` → `23-rule-language-reference.md`
  - [ ] `simple-start.md` → `33-simple-start.md`
  - [ ] `troubleshooting.md` → `31-troubleshooting.md`
- [ ] Verify no internal cross-links inside renamed files broken (grep + spot-check)

### Phase 1b — Revisions (8–10 hrs)
Drift fixes from CODE_FINDINGS.md applied to existing files.

- [ ] **04-architecture.md** revision (3–4 hrs)
  - [ ] Replace every `ruleId` with `rule_id` in JSON examples
  - [ ] Update 7 security-header values to actual code
  - [ ] Clarify port architecture (`/admin/*` on 8080, not separate)
  - [ ] Add filter chain order diagram showing `[-1, 0, 1, unordered]`
  - [ ] Add "Component dependency map" subsection
  - [ ] Add atomic-swap KieContainer pattern explanation
  - [ ] Add TOCTOU-safe lookup pattern explanation
- [ ] **06-deployment.md** revision (2 hrs)
  - [ ] Replace example Dockerfile with actual (Corretto 17 Alpine multi-stage)
  - [ ] Fix port-architecture diagram (admin on 8080, not separate service)
  - [ ] Update healthcheck command to match actual `wget --spider`
  - [ ] Live-test every command in the doc
- [ ] **17-rule-development.md** revision (1.5 hrs)
  - [ ] Add prominent "traditional DRL syntax only" header note
  - [ ] Replace Drools 7.74.1 doc links with 8.x equivalents
  - [ ] Verify all DRL examples pass DrlSanitizer
- [ ] **23-rule-language-reference.md** revision (0.5 hrs)
  - [ ] Add prominent disclaimer at top about feature subset
  - [ ] Tag unused-feature sections (rule units, OOPath, DataStream) with "[Not used in this project]"
- [ ] **24-jvm-optimization.md** revision (1 hr)
  - [ ] Remove deprecated `-XX:+UseCGroupMemoryLimitForHeap` (line 41)
  - [ ] Add note about Java 17 cgroup auto-detection
  - [ ] Update production targets

### Phase 1c — New foundation docs (6–8 hrs)
- [ ] **01-project-overview.md** — what/why/business value (~250 lines, 1.5 hr)
- [ ] **02-project-structure.md** — annotated directory tree (~300 lines, 1.5 hr)
- [ ] **03-tech-stack.md** — versions + rationale (~250 lines, 1.5 hr)
- [ ] **05-environments-and-profiles.md** — 4 Spring profiles (~400 lines, 2 hr)

### Phase 1 — Verification gate
- [ ] `grep -rn 'ruleId' project-documentation/` returns no JSON-field-name occurrences (only Java identifiers)
- [ ] All 13 renames complete; no leftover undecorated names
- [ ] All curl examples in revised `06-deployment.md` produce documented output
- [ ] CODE_FINDINGS.md updated with any new drift discovered during Phase 1

---

## Phase 2 — APIs, Security, Infrastructure (20–25 hrs)

Goal: external integrators self-sufficient. AI agents can answer API questions.

### Phase 2a — APIs & integration (10–12 hrs)
- [ ] **07-docker-and-compose.md** — JAVA_OPTS line-by-line + compose service-by-service (~600 lines, 3 hr)
- [ ] **09-environment-variables-reference.md** — exhaustive 60+ env vars (~700 lines, 3 hr) — verify by `grep '@Value'` + `application.yml` parse
- [ ] **10-api-reference.md** — prose wrapper for OpenAPI yml (~600 lines, 3 hr) — every endpoint live-tested
- [ ] **11-integration-guide.md** — code examples in 4 languages (~500 lines, 2 hr) — each example tested end-to-end
- [ ] **12-error-code-catalog.md** — every error code with HTTP status + fix (~400 lines, 1.5 hr)
- [ ] **13-rate-limiting-and-throttling.md** — multi-tier client ID + admin exemption (~300 lines, 1 hr)

### Phase 2b — Security trio (5–7 hrs)
- [ ] **14-security-architecture.md** — 8-layer model with citations (~600 lines, 3 hr)
- [ ] **15-admin-authentication.md** — admin key flow + dev-mode bypass (~250 lines, 1 hr)
- [ ] **16-drl-sandboxing.md** — definitive sandbox rules (~500 lines, 2 hr) — every allowed/blocked claim cited from `DrlSanitizer.java` + verified by test

### Phase 2 — Verification gate
- [ ] Every endpoint in `10-api-reference.md` curl-verified against running stack
- [ ] All 4 language examples in `11-integration-guide.md` produce identical output
- [ ] `16-drl-sandboxing.md` allowlist/blocklist matches `DrlSanitizer.java` exactly (line-by-line audit)
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
