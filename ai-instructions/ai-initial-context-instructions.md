# AI Initial Context — System Instructions
**Project**: Drools Rule Engine Microservice

## Context File Location

The current context file is always at:
```
.ai-workspace/ai-initial-context/ai-initial-context-latest.md
```

**At the start of every session**: read `.ai-workspace/ai-initial-context/ai-initial-context-latest.md` to get full project context in a single read. This provides a comprehensive snapshot of the project state.

---

## Command: "ai-context-update"

When the user types **"ai-context-update"**, execute the following steps in order:

### Step 1 — Archive the current context file
Get the current Unix epoch timestamp and rename the existing latest file:
```
.ai-workspace/ai-initial-context/ai-initial-context-latest.md
→ .ai-workspace/ai-initial-context/ai-initial-context-{epoch}.md
```
Use a bash command to get the epoch: `date +%s`

### Step 2 — Write fresh context to the new latest file
Write all current context you have at that moment to:
```
.ai-workspace/ai-initial-context/ai-initial-context-latest.md
```

**Write it as-is — do not compact or summarize.** Include everything you currently know:
- What the Drools Rule Engine Microservice is
- Current phase statuses (what's complete, what's in progress, what's next)
- Full technology stack (Java 25, Spring Boot 3.5.3, Drools 10.2.0, AWS S3, Redis, Docker)
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
   Archived: .ai-workspace/ai-initial-context/ai-initial-context-{epoch}.md
   Updated:  .ai-workspace/ai-initial-context/ai-initial-context-latest.md
```

---

## CRITICAL RULES — Read Before Writing

### Rule 1: Archive naming MUST use Unix epoch timestamps ONLY
```
CORRECT:   ai-initial-context-1772101857.md
WRONG:     ai-initial-context-20260219-181912.md   (date format)
WRONG:     ai-context-latest.md                    (different prefix)
WRONG:     ai-initial-context-latest-backup.md     (invented name)
```
The archive filename format is: `ai-initial-context-{epoch}.md` — no exceptions.
Use `date +%s` to get the epoch. Do not invent alternative naming schemes.

### Rule 2: The context file MUST be comprehensive (800+ lines)
The purpose of this file is to give a future AI assistant **complete project context in a single read**. A 200-line summary defeats the purpose.

**Minimum sections required** (all must have meaningful content, not just headers):
- PROJECT OVERVIEW (what this project is, business value, capabilities)
- PROJECT STATUS (health score, phase table, coverage table, recent achievements)
- TECHNOLOGY STACK (all technologies with versions and purpose)
- REPOSITORY STRUCTURE (full directory tree with file descriptions)
- PHASE 1-6 details (what was built, key decisions, issues resolved)
- ARCHITECTURE DECISIONS (numbered list with rationale)
- CORE CONCEPTS (execution flow, refresh flow, caching behavior, error handling)
- PERFORMANCE METRICS (targets vs actuals table, memory stability results)
- SAMPLE RULES & TESTING (all 10 rules, test commands, DRL patterns)
- DOCKER & DEPLOYMENT (docker-compose setup, production config, endpoints table)
- ENVIRONMENT VARIABLES (all critical env vars)
- DEFERRED FEATURES (numbered list with status)
- GIT STATE (branch, recent commits)
- DEVELOPMENT WORKFLOW COMMANDS (java setup, build, test, docker, API testing)
- SESSION HISTORY (summary of all major sessions)
- AI WORKSPACE (context management, session logs, instructions)
- NEXT STEPS

**Size guideline**: If your output is under 800 lines, you are compacting too aggressively. The previous version should be used as a baseline — never produce something significantly shorter than the last version.

### Rule 3: "Write as-is" means FULL DETAIL, not a summary
"Write it as-is — do not compact or summarize" means:
- Include code snippets for critical sections (memory leak fix, docker-compose healthcheck, etc.)
- Include full directory trees with per-file descriptions
- Include tables (coverage, performance, sample rules, endpoints)
- Include command examples with full syntax
- Include ASCII flow diagrams where helpful

If in doubt, include more rather than less. A future AI assistant reading this file should understand the project well enough to start contributing immediately.

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
