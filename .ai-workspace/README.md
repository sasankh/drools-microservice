# .ai-workspace

Internal AI working area. **Not part of the public documentation corpus** — that lives in [`../project-documentation/`](../project-documentation/) and is the canonical reference. Files here are for the people and AI agents working *on* this repo, not for end users of the service.

If you're looking for the project itself: start at [`../project-documentation/00-system-overview.md`](../project-documentation/00-system-overview.md).

---

## Subdirectories

| Path | What it is |
|---|---|
| [`documentations/`](documentations/) | Tracking artifacts from the 2026-05-08 documentation rebuild — `CHECKLIST.md` (phase-by-phase log), `CODE_FINDINGS.md` (32+ code-vs-doc mismatches found during the rebuild), `DOCUMENTATION_PLAN.md` (per-doc spec), `SUMMARY.md` (rebuild overview) |
| [`project-plans/`](project-plans/) | Personal reference / backlog files. `security-backlog.md` (the 3 unaddressed findings from "39/42"), `project-improvement-plan.md`, `project-revisit-analysis.md`, `project-validation-plan.md`, plus the older `project.checklist.md` / `project.progress.md` / `project.documentation.md` / `project.prompt.md` |
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
