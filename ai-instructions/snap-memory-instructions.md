# 📸 Snap Memory System - Universal Instructions for AI Assistants

## Purpose
Create comprehensive session logs that enable future AI assistants to fully reconstruct project context, understand decisions made, and continue work seamlessly.

---

## Activation Command
**When the user says "snap-memory" or "create snap memory", execute this protocol.**

---

## File Creation

### Naming Convention
```
.ai-workspace/snap-memory/snap-memory-{epochtime}.md
```
- Use current Unix epoch timestamp (e.g., `1769668074`)
- Creates chronological ordering automatically
- **Location**: `.ai-workspace/snap-memory/` directory (inside .ai-workspace)

### Header Template
```markdown
# Snap Memory - {Human-Readable Date}
**Epoch**: {timestamp}
**Session Duration**: {start time} - {end time}
**Project**: {project name}
**Focus Area**: {main area of work this session}
```

---

## CRITICAL RULES — Read Before Writing

### Rule 1: Always use `date +%s` for the epoch — never estimate or calculate manually
```bash
# CORRECT — run this command and use the output
date +%s
# Example output: 1771577000

# WRONG — do not manually estimate, calculate, or round timestamps
# WRONG — do not use a future time or session-end prediction
```
The epoch in the filename MUST match the actual time the file is created. If the epoch doesn't match real time, snap-memories will sort out of order and confuse future sessions.

### Rule 2: Check existing snap-memories before creating a new one
Before writing a new snap-memory, list existing files to understand what's already documented:
```bash
ls -t .ai-workspace/snap-memory/ | head -5
```
Then read the most recent one to avoid duplicating content. Each snap-memory should document **new work from this session only** — not re-document work already captured in a previous snap-memory.

### Rule 3: Sections must have real content, not just headers
At minimum, these sections MUST contain meaningful content (not placeholders or empty bullets):
- **SESSION OVERVIEW** — What was done, why, how
- **TECHNICAL CHANGES** — Files modified with descriptions
- **PROBLEM SOLVING** — Errors encountered and how they were resolved (or "None" if clean session)
- **PROJECT STATE** — Git branch, build status, uncommitted changes
- **NEXT SESSION PREP** — What comes next, where to pick up

Sections 3 (CODE ARCHITECTURE), 7 (DECISION LOG), and 8 (KNOWLEDGE CAPTURE) can be brief or omitted if the session didn't involve architectural changes, major decisions, or new learnings.

### Rule 4: One snap-memory per session — do not split or merge
- Each snap-memory documents ONE continuous working session
- Do not create multiple snap-memories for the same session
- Do not combine multiple sessions into one snap-memory
- If a session is very long, it's fine to have a long snap-memory

---

## Complete Snap Memory Structure

### 1. SESSION OVERVIEW

#### What Was Done
- High-level summary of all work completed
- Major features implemented
- Bugs fixed
- Refactoring completed
- Documentation updates

#### Why This Work
- Original problem or requirement
- User request or issue being addressed
- Technical debt being resolved
- Performance/security/UX improvements

#### How It Was Accomplished
- Approach taken and methodology
- Key algorithms or patterns used
- Libraries/tools leveraged
- Step-by-step implementation summary

#### Session Context
- Where the project was at session start
- What prompted this particular work
- Dependencies on previous sessions
- Overall project phase/milestone

---

### 2. TECHNICAL CHANGES

#### Files Modified
```
src/path/to/file.ext - [Created|Modified|Deleted|Renamed]
  - Brief description of changes
  - Why this file was changed
  - Related files that depend on this change
```

#### Commands Executed
```bash
# Build commands
command --flags arguments

# Test commands
test-runner --options

# Deployment commands
deploy-script --target production

# Any other CLI operations
```

#### Dependencies Changed
**Added:**
- `package-name@version` - Purpose and why chosen
- `library-name@version` - Use case

**Updated:**
- `package-name`: `old-version` → `new-version` - Reason for update

**Removed:**
- `package-name@version` - Why no longer needed

#### Configuration Changes
- Environment variables added/modified
- Config file updates (CI/CD, build tools, linters, etc.)
- Infrastructure changes (database schema, API routes, cloud resources)
- Authentication/authorization rule changes
- Feature flags toggled

---

### 3. CODE ARCHITECTURE

#### New Components/Modules Created
- Component/module name and purpose
- Public API / exported functions
- Dependencies and relationships
- Usage examples

#### Modified Components/Modules
- What changed and why
- API changes (breaking vs. non-breaking)
- Migration notes if applicable

#### Design Patterns Applied
- Patterns used (MVC, Observer, Factory, etc.)
- Architecture style (monolithic, microservices, serverless)
- Data flow patterns
- State management approach

#### Integration Points
- How new code integrates with existing systems
- External APIs or services connected
- Database interactions
- Event flows and messaging

---

### 4. PROBLEM SOLVING

#### Errors Encountered
**Error 1:**
- Error message/stack trace
- When it occurred
- Root cause identified
- Solution applied
- Prevention measures

**Error 2:**
- [Same structure]

#### Debugging Process
- Investigation steps taken
- Tools used (debuggers, profilers, logging)
- Dead ends explored
- Final breakthrough moment

#### Workarounds vs. Fixes
- Temporary workarounds applied (with TODO to fix properly)
- Permanent fixes implemented
- Technical debt created or resolved

---

### 5. TESTING & VALIDATION

#### Tests Written
- Unit tests added (file paths, coverage)
- Integration tests added
- E2E tests added
- Test utilities or fixtures created

#### Manual Testing Performed
- Features tested manually
- Browser/device combinations tested
- Edge cases validated
- User workflows verified

#### Testing Results
- Pass/fail status
- Coverage metrics (if applicable)
- Performance benchmarks
- Outstanding test failures and reasons

#### Validation Checklist
- [ ] Code compiles/builds successfully
- [ ] All tests pass
- [ ] Linter passes with no errors
- [ ] Type checker passes (if applicable)
- [ ] Accessibility validated
- [ ] Security scan completed
- [ ] Performance acceptable

---

### 6. PROJECT STATE

#### Version Control Status
**Branch:** `branch-name`
**Base Branch:** `main` or `develop`
**Status:** Clean / Uncommitted changes / Merge conflicts

**Recent Commits:**
```
abc1234 - Commit message
def5678 - Commit message
```

**Untracked/Staged Files:**
- List any uncommitted work and why

#### Build Status
- ✅ Development build: [Success/Failure]
- ✅ Production build: [Success/Failure]
- ⚠️ Warnings: [Count and nature]
- 🚨 Errors: [Count and details]

#### Deployment Status
- Environment deployed to (dev/staging/prod)
- Deployment method used
- Rollback plan if needed
- Feature flags or gradual rollout status

#### Performance Metrics
- Build time: before → after
- Bundle size: before → after
- Runtime performance improvements
- Memory usage changes
- API response times

---

### 7. DECISION LOG

#### Architecture Decisions
**Decision:** [What was decided]
**Alternatives Considered:**
1. Option A - Pros/Cons
2. Option B - Pros/Cons
3. Option C - Pros/Cons

**Chosen:** Option X
**Rationale:** [Why this option was selected]
**Trade-offs:** [What was sacrificed]
**Reversibility:** [Easy/Difficult to undo, how to undo]

#### Technology Choices
- Libraries/frameworks chosen and why
- Alternatives that were rejected
- Long-term implications
- Migration path if we need to change

#### UX/Design Decisions
- Interface design choices
- User flow decisions
- Accessibility considerations
- Mobile vs. desktop approach

---

### 8. KNOWLEDGE CAPTURE

#### Things Learned
- New techniques or patterns discovered
- API quirks or gotchas encountered
- Best practices identified
- Anti-patterns to avoid

#### Documentation Updates
- README.md changes
- API documentation updates
- Code comments added
- Architecture diagrams updated

#### Gotchas & Pitfalls
- Things that aren't obvious
- Easy mistakes to make
- Configuration that must be exact
- Order-of-operations requirements

---

### 9. OUTSTANDING WORK

#### Known Issues
1. **Issue:** Description
   - **Severity:** Critical/High/Medium/Low
   - **Impact:** Who/what is affected
   - **Workaround:** Temporary solution if available
   - **Next Steps:** How to properly fix

#### TODO Items
- [ ] Task description - Priority (High/Med/Low) - Estimated effort
- [ ] Task description - Priority - Estimated effort

#### Technical Debt
- Shortcuts taken that need cleanup
- Code that needs refactoring
- Tests that need to be written
- Documentation gaps

#### Blocked Items
- What is blocked
- What is blocking it
- Steps to unblock
- Priority level

---

### 10. NEXT SESSION PREP

#### Immediate Next Steps
1. First thing to tackle (most important)
2. Second priority
3. Third priority

#### Context for Next Session
- Where to pick up
- Files to review first
- Key context to understand
- Potential challenges ahead

#### Questions to Resolve
- Unanswered questions from this session
- Decisions that need stakeholder input
- Research needed
- Clarifications required

#### Resource Links
- Relevant documentation URLs
- Stack Overflow solutions referenced
- GitHub issues or PRs
- Design mockups or specs

---

## Writing Guidelines for AI Assistants

### Be Specific
- ❌ "Fixed a bug"
- ✅ "Fixed null pointer exception in UserService.authenticate() when email is undefined"

### Be Complete
- Include file paths, line numbers when relevant
- Show before/after code snippets for significant changes
- Document the full error message, not just "got an error"

### Be Contextual
- Explain WHY decisions were made, not just WHAT was done
- Include alternatives that were considered
- Note dependencies between changes

### Be Honest
- Document failed approaches (saves time later)
- Admit when a workaround was used instead of proper fix
- Note when you're uncertain about something

### Be Forward-Looking
- Help the next AI (or human) understand what comes next
- Flag areas that need attention
- Suggest improvements or refactoring opportunities

---

## Usage Examples

### For Backend Development
Focus on: API routes, database schemas, authentication, business logic, performance, security

### For Frontend Development
Focus on: Components, state management, routing, styling, responsive design, accessibility

### For DevOps/Infrastructure
Focus on: CI/CD changes, deployment configs, environment variables, monitoring, scaling

### For Data Science/ML
Focus on: Model architectures, training runs, dataset changes, hyperparameters, evaluation metrics

### For Mobile Development
Focus on: Platform-specific changes, native modules, permissions, app store submissions

---

## Benefits of This System

### For Future AI Assistants
- Full context reconstruction in minutes vs. hours of code reading
- Understanding of "why" behind decisions
- Awareness of pitfalls and gotchas
- Clear continuation points

### For Human Developers
- Comprehensive session documentation
- Decision rationale preserved
- Knowledge transfer made easy
- Onboarding acceleration

### For Project Management
- Accurate progress tracking
- Effort estimation data
- Risk identification
- Dependency mapping

---

## Template Quick Reference

```markdown
# Snap Memory - {Date}
**Epoch**: {timestamp}
**Project**: {name}
**Focus**: {area}

## SESSION OVERVIEW
- What:
- Why:
- How:
- Context:

## TECHNICAL CHANGES
- Files:
- Commands:
- Dependencies:
- Configs:

## CODE ARCHITECTURE
- New:
- Modified:
- Patterns:
- Integration:

## PROBLEM SOLVING
- Errors:
- Debugging:
- Solutions:

## TESTING
- Tests:
- Manual:
- Results:
- Validation:

## PROJECT STATE
- Git:
- Build:
- Deploy:
- Performance:

## DECISIONS
- Architecture:
- Technology:
- Design:

## KNOWLEDGE
- Learned:
- Gotchas:
- Documentation:

## OUTSTANDING
- Issues:
- TODOs:
- Debt:
- Blocked:

## NEXT SESSION
- Steps:
- Context:
- Questions:
- Resources:
```

---

**Last Updated**: 2026-02-26
**Version**: 2.1 (Universal/Agnostic — added Critical Rules section)
**Applicable To**: All project types, languages, and architectures
