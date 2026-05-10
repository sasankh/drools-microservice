# .ai-workspace

Internal AI working area. **Not part of the public documentation corpus** — that lives in [`../project-documentation/`](../project-documentation/) and is the canonical reference. Files here are for the people and AI agents working *on* this repo, not for end users of the service.

If you're looking for the project itself: start at [`../project-documentation/00-system-overview.md`](../project-documentation/00-system-overview.md).

---

## Subdirectories

| Path | What it is |
|---|---|
| [`documentations/`](documentations/) | Tracking artifacts from the 2026-05-08 documentation rebuild — `CHECKLIST.md` (phase-by-phase log), `CODE_FINDINGS.md` (32+ code-vs-doc mismatches found during the rebuild), `DOCUMENTATION_PLAN.md` (per-doc spec), `SUMMARY.md` (rebuild overview) |
| [`project-plans/`](project-plans/) | Personal reference / backlog files. **Active**: `stack-modernization-plan.md` + `stack-modernization-checklist.md` (Java 17→25 + Spring Boot 3.5.3 + Drools 10.2.0 modernization, executed 2026-05-09), `e2e-validation-plan.md` + `e2e-validation-checklist.md` (full docker-compose end-to-end validation against the modernized stack), `e2e-validation-findings.md` (two pre-existing bugs surfaced during e2e validation), `fix-findings-plan.md` + `fix-findings-checklist.md` (the fix work for both findings — completed 2026-05-10), `load-test-plan.md` + `load-test-checklist.md` (production-readiness load + stress + 1-hour soak with 1,000 rules in S3 + sample-rules cookbook expansion + a re-runnable orchestrator at `scripts/run-load-test.sh`; in progress 2026-05-10), `security-backlog.md` (now 40/42; finding #30 closed via stack modernization), `project-improvement-plan.md` (2026-02 sprint with 2026-05-08/09 status deltas). **Historical**: `project-revisit-analysis.md`, `project-validation-plan.md`, `project.checklist.md`, `project.progress.md`, `project.documentation.md`, `project.prompt.md` |
| [`ai-summary/`](ai-summary/) | Older AI-generated summary docs from prior sessions. The full per-finding security fix log lives at [`ai-summary/security-fix-plan.md`](ai-summary/security-fix-plan.md) |
| [`ai-initial-context/`](ai-initial-context/) | Legacy consolidated context files. The `ai-initial-context-latest.md` here is **superseded** by [`../project-documentation/00-system-overview.md`](../project-documentation/00-system-overview.md) — kept for history |
| [`compact-logs/`](compact-logs/) | Conversation-compaction logs from prior Claude Code sessions — useful for "what was happening when I last left off?" |
| [`snap-memory/`](snap-memory/) | Output from the `snap-memory` workflow. Workflow itself defined in [`../ai-instructions/snap-memory-instructions.md`](../ai-instructions/snap-memory-instructions.md) |

---

## Where do I add new things?

- **A doc-vs-code mismatch found during work** → append to [`documentations/CODE_FINDINGS.md`](documentations/CODE_FINDINGS.md)
- **A backlog item or "revisit later" note** → new file in [`project-plans/`](project-plans/)
- **A new public-facing doc** → does **not** go here. It goes in [`../project-documentation/`](../project-documentation/) and gets added to the manifest there.
- **A snap-memory of the current session** → follow [`../ai-instructions/snap-memory-instructions.md`](../ai-instructions/snap-memory-instructions.md); output lands in [`snap-memory/`](snap-memory/)

## Conventions

- Files here can be terse and dated — they're working notes, not polished references.
- Use `git mv` when relocating files so history is preserved.
- The 2026-05-08 rebuild moved the four top-level tracking files (`CHECKLIST.md`, `CODE_FINDINGS.md`, `DOCUMENTATION_PLAN.md`, `SUMMARY.md`) into [`documentations/`](documentations/) — references in the public corpus point at the new location.
