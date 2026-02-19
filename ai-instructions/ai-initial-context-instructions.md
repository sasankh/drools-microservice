# AI Initial Context — System Instructions
**Project**: Drools Rule Engine Microservice

## Context File Location

The current context file is always at:
```
ai-workspace/ai-initial-context/ai-initial-context-latest.md
```

**At the start of every session**: read `ai-workspace/ai-initial-context/ai-initial-context-latest.md` to get full project context in a single read. This provides a comprehensive snapshot of the project state.

---

## Command: "ai-context-update"

When the user types **"ai-context-update"**, execute the following steps in order:

### Step 1 — Archive the current context file
Get the current Unix epoch timestamp and rename the existing latest file:
```
ai-workspace/ai-initial-context/ai-initial-context-latest.md
→ ai-workspace/ai-initial-context/ai-initial-context-{epoch}.md
```
Use a bash command to get the epoch: `date +%s`

### Step 2 — Write fresh context to the new latest file
Write all current context you have at that moment to:
```
ai-workspace/ai-initial-context/ai-initial-context-latest.md
```

**Write it as-is — do not compact or summarize.** Include everything you currently know:
- What the Drools Rule Engine Microservice is
- Current phase statuses (what's complete, what's in progress, what's next)
- Full technology stack (Java 17, Spring Boot 3.x, Drools 8.44.0, AWS S3, Redis, Docker)
- Repository structure (files, what each does)
- Implementation details for completed phases (Phase 1-5 details, key notes, gotchas)
- Architecture decisions (storage abstraction, caching strategy, security hardening)
- Core concepts (rule execution, multi-tier caching, rule ID transformation)
- Current development status and any ongoing work
- Sample rules and LocalStack integration
- Performance metrics and optimization details
- Deferred features (if any)
- Git state (branch, recent commits, uncommitted files)
- Docker setup and deployment configuration
- Immediate next steps

**Standard structure to follow:**
```
# Drools Rule Engine Microservice - Full AI Context Document
**Last Updated**: {date}
**Purpose**: Read ONLY this file to get full project context.

## PROJECT OVERVIEW
## PROJECT STATUS
## TECHNOLOGY STACK
## REPOSITORY STRUCTURE
## PHASE 1: CORE INFRASTRUCTURE (COMPLETED)
## PHASE 2: STORAGE & CACHING (COMPLETED)
## PHASE 3: PRODUCTION READINESS (COMPLETED)
## PHASE 4: TESTING & DOCUMENTATION (COMPLETED - Phase 4.4)
## PHASE 5: DEPLOYMENT & INFRASTRUCTURE (COMPLETED)
## ARCHITECTURE DECISIONS
## CORE CONCEPTS
## PERFORMANCE METRICS
## SAMPLE RULES & TESTING
## DOCKER & DEPLOYMENT
## DEFERRED FEATURES
## GIT STATE
## DEVELOPMENT WORKFLOW COMMANDS
## NEXT STEPS
```

### Step 3 — Confirm to the user
After writing, confirm:
```
✅ Context updated.
   Archived: ai-workspace/ai-initial-context/ai-initial-context-{epoch}.md
   Updated:  ai-workspace/ai-initial-context/ai-initial-context-latest.md
```

---

## Notes

- **Never delete the archived files** — they form a history of how context evolved over time.
- The `ai-initial-context-latest.md` is the single source of truth for future sessions.
- Run `ai-context-update` at the end of any session where significant decisions were made or plans changed.
- **Relationship to snap-memory**:
  - `ai-initial-context` = comprehensive project state (what exists, where we are)
  - `snap-memory/*.md` = session-by-session implementation history (what was done, how, why)
  - Both systems complement each other for complete project continuity
- If `ai-initial-context-latest.md` does not exist yet (first run), skip Step 1 and go straight to Step 2.
- **Integration with existing documentation**:
  - This context file supplements (doesn't replace) CLAUDE.md, README.md, project.progress.md
  - Use this for quick AI onboarding; use other files for detailed reference
  - Keep this file synchronized with major project milestones
