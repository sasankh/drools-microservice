# 38 · For AI Agents

| | |
|---|---|
| **Audience** | Future AI sessions (Claude Code, Codex, agents) working on this repo |
| **Purpose** | Distill the verification rules and pitfalls learned during the 2026-05-08 documentation overhaul, so future agents don't re-make the same mistakes |
| **Last verified against** | This conversation's overhaul findings + [`.ai-workspace/documentations/CODE_FINDINGS.md`](../.ai-workspace/documentations/CODE_FINDINGS.md) on 2026-05-08 (refreshed 2026-05-10 for stack modernization + load test) |
| **Related docs** | [00-system-overview.md](00-system-overview.md), [12-error-code-catalog.md](12-error-code-catalog.md), [36-architecture-decision-records.md](36-architecture-decision-records.md), [`../.ai-workspace/README.md`](../.ai-workspace/README.md) |

---

## The two non-negotiable rules

1. **The canonical reference is [`00-system-overview.md`](00-system-overview.md).** Every other doc is reachable from there. When in doubt, start there and follow the role-based reading paths.

2. **Verify before you claim. Cite `path/file.ext:line` for every assertion.** Numerical claims (counts, sizes, line numbers), behavioral claims (what an endpoint returns, what a filter blocks), and architectural claims (which layer holds which lock) all need a code citation. Doc-vs-doc citations don't count — chase to the source.

If you can't cite, don't write. If you cited and the cite is wrong, the doc is wrong.

---

## Common pitfalls (with concrete examples from this overhaul)

### 1. Don't invent metric names

**What we found**: 6+ fictitious metric names invented by earlier doc passes — `drools.rule.execution.duration`, `drools.rule.execution.errors`, `drools.s3.requests`, `drools.s3.errors`, `drools.s3.latency`, `drools.threadpool.active/queued/completed`. None exist in code.

**The actual names** are in `meterRegistry.timer/counter/gauge` calls across the codebase. Real ones include `drools.rule.execution.{time,error,success}`, `drools.api.{requests,errors,response.time}`, `drools.cache.{hits,misses,evictions,size}`, `drools.storage.operation.time`. Thread pool metrics are Spring Boot auto-instrumented (`executor.*`); circuit breaker metrics come from Resilience4j (`resilience4j.circuitbreaker.*`).

**The rule**: never write a metric name unless you've grepped `meterRegistry.` for it. The names that "sound right" are usually wrong.

### 2. Don't invent error codes

**What we found**: `README.md` listed 10 error codes including `RULE_COMPILATION_ERROR`, `STORAGE_ERROR`, `CACHE_ERROR` — none of which exist in code.

**The actual codes** (10 total, verified) are catalogued in [12-error-code-catalog.md](12-error-code-catalog.md), defined in [`GlobalExceptionHandler.java`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java) (8 codes) plus [`AdminAuthFilter.java`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java) (`UNAUTHORIZED`) and [`RateLimitingFilter.java`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java) (`RATE_LIMIT_EXCEEDED`).

**The rule**: error codes are an enumerable set. Look at the actual exception/filter code, not at what *seems plausible*.

### 3. Don't trust counts — verify them

**What we found**: every count we typed from memory during the rebuild was off by some amount.
- "56 Java source files" → actual 57 (`find src/main/java -name "*.java" | wc -l`)
- "9 distinct error codes" → actual 10
- "18 sandbox import prefixes / 13 blocked classes / 15 blocked methods" → actual 20 / 12 / 19
- "66 cases" in `DrlSanitizerTest` → actual 23
- "~1,181 tests" → actual 589

**The rule**: counts are cheap to verify (`find … | wc -l`, `grep -c`, `wc -l`). Always verify before quoting; never copy a count from another doc that hasn't been re-verified.

### 4. Watch for silent passes in validators

**Found as F-032**: `RuleIdValidator` calls `.trim()` on the input, so a rule ID with trailing whitespace **passes validation** — only to fail later in storage as `RULE_NOT_FOUND`. The user gets a misleading error.

**The rule**: when you see input-massaging in a validator (`trim`, `toLowerCase`, `replace`), assume it's silently changing semantics. Document the gotcha; don't paper over it. Other places to suspect: any DTO field with `@JsonProperty` mapping (renames hide bugs), any `if (x != null && !x.isEmpty())` guard (empty after trim is different from empty before trim).

### 5. The "39/42 security findings" claim has a qualifier

The corpus repeats "39 of 42 security findings closed" in 5+ places. The 3 unaddressed are:
- **#28 (HIGH)** Redis without auth/TLS — skipped per user, only one with real prod risk
- **#30 (MEDIUM)** Outdated dependencies — skipped per user
- **#38 (INFO)** KieContainer disposal safety — already documented in code

Full breakdown in [`.ai-workspace/project-plans/security-backlog.md`](../.ai-workspace/project-plans/security-backlog.md). Don't restate "39/42" without knowing what the 3 are; if you're advising on a production deploy, surface #28 specifically.

### 6. Live-test runtime behavior, don't reason about it

**What we found**: an earlier doc claimed VIP $100 → $80. We documented $72 (after stacking). Verifying via live curl produced $72 — the doc was right; the README example showing $80 was a single-rule-only case. Stacking behavior of multiple rules in the same KieBase is **not obvious from reading the source**.

**The rule**: when documenting runtime behavior — especially anything involving rule stacking, ordering, caching, or state — run the actual curl. The cookbook in [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) is the gold standard: every example was live-tested.

---

## Workflow conventions

### When you find a code-vs-doc mismatch
Append it to [`.ai-workspace/documentations/CODE_FINDINGS.md`](../.ai-workspace/documentations/CODE_FINDINGS.md) with file:line citation, severity, and recommended fix. Doc-side fixes can usually be applied directly. Code-side fixes get logged for separate triage — **don't change code as part of doc work** unless explicitly asked.

### When you reorganize files
Use `git mv` to preserve history. The 2026-05-08 rebuild moved four files into `.ai-workspace/documentations/` — references in the public corpus were updated in the same change.

### When the change is non-trivial
Use plan mode. The user prefers reviewing a written plan before edits, especially for multi-file changes or anything that touches `project-documentation/`. Past plans live at `~/.claude/plans/`.

### When you need session memory
Two workflows are documented in [`../ai-instructions/`](../ai-instructions/):
- `snap-memory` — write-only memory snapshot of the current session
- `ai-context-update` — refresh the `.ai-workspace/ai-initial-context/` files

Output for `snap-memory` lands in [`../.ai-workspace/snap-memory/`](../.ai-workspace/snap-memory/).

### Before adding a new doc to the public corpus
- Does it have an audience the existing docs don't serve? (If not, extend an existing doc.)
- Will someone keep it verified against code? (Stale docs are worse than missing ones.)
- Update [00-system-overview.md](00-system-overview.md) "Numbers worth knowing" doc count and [`README.md`](README.md) manifest in the **same** change.

---

## Where the lessons live

| Source | What's there |
|---|---|
| [`.ai-workspace/documentations/CODE_FINDINGS.md`](../.ai-workspace/documentations/CODE_FINDINGS.md) | Catalog of code-vs-doc mismatches discovered during the overhaul (32+ items, all severity Low; doc-fixes applied, code-side recommendations logged) |
| [`.ai-workspace/project-plans/security-backlog.md`](../.ai-workspace/project-plans/security-backlog.md) | The 3 unaddressed security findings from "39/42" — what they are, prod risk |
| [`.ai-workspace/documentations/CHECKLIST.md`](../.ai-workspace/documentations/CHECKLIST.md) | Phase-by-phase rebuild log including the retroactive Phase 4+5 rigorous review (which caught the metric-name and count bugs above) |
| [36-architecture-decision-records.md](36-architecture-decision-records.md) | ADRs covering the non-obvious design choices (Drools 10 `KieContainer.updateToVersion` rule loading — see ADR-003 with 2026-05-10 update, write-lock-on-get LRU, AdminAuthFilter instead of Spring Security, traditional DRL only, Drools 10 + Java 25 modernization) |

---

## AI-adjacent directory map (one-liner each)

For full detail see [`../.ai-workspace/README.md`](../.ai-workspace/README.md).

| Directory | Purpose |
|---|---|
| [`../.ai-workspace/documentations/`](../.ai-workspace/documentations/) | Verified tracking artifacts from the 2026-05-08 doc rebuild |
| [`../.ai-workspace/project-plans/`](../.ai-workspace/project-plans/) | Personal reference / backlog files |
| [`../.ai-workspace/ai-summary/`](../.ai-workspace/ai-summary/) | Older AI-generated summaries (full security-fix-plan here) |
| [`../.ai-workspace/ai-initial-context/`](../.ai-workspace/ai-initial-context/) | Legacy consolidated context (superseded by 00-system-overview) |
| [`../.ai-workspace/compact-logs/`](../.ai-workspace/compact-logs/) | Compaction logs from prior Claude Code sessions |
| [`../.ai-workspace/snap-memory/`](../.ai-workspace/snap-memory/) | `snap-memory` workflow output |
| [`../ai-instructions/`](../ai-instructions/) | Workflow protocol files for `snap-memory` and `ai-context-update` |

---

## A short pre-action checklist

Before claiming you finished a task that touched docs or code:

- [ ] Every numerical claim verified with a fresh `grep`/`find`/`wc -l`
- [ ] Every code citation opened and re-read at the cited lines
- [ ] Every cross-link in edited docs resolves (no orphans)
- [ ] Any new mismatches logged to `CODE_FINDINGS.md`
- [ ] Counts in [00-system-overview.md](00-system-overview.md) "Numbers worth knowing" updated if affected
- [ ] No fictitious metric names, error codes, or test counts introduced

When in doubt: cite, or don't claim.
