# Universal Documentation Update Prompt

This prompt analyzes existing documentation and keeps it synchronized with code changes.

---

## 📋 THE UPDATE PROMPT (Copy from here)

I need to update the existing documentation for this project to reflect current code changes.

### Phase 1: Discovery & Analysis

**Step 1: Identify Documentation**
- Find all existing documentation (usually in `project-documentation/` directory)
- List all documentation files and their topics
- Check for `.ai-workspace/DOCUMENTATION_PLAN.md` (AI workspace) to understand original structure
- Note the documentation style and format used

**Step 2: Analyze Recent Changes**
- Check git log for recent commits (last 30-90 days, or since last doc update)
- Identify modified files and their change types:
  - New features added
  - Features removed or deprecated
  - API changes (endpoints, schemas, parameters)
  - Configuration changes (env vars, settings)
  - **Infrastructure changes** (new services, resources, Terraform/IaC modifications)
  - **Terraform/IaC changes** (check terraform/, cloudformation/, cdk/ directories)
  - Dependency updates
  - Bug fixes that affect documented behavior
  - New deep-dive subsystems (provisioning, API patterns, webhooks, etc.)

**Step 3: Identify Documentation Gaps**
Compare current code against existing documentation to find:
- ❌ New code not yet documented
- ❌ Documented features that no longer exist
- ❌ Changed APIs with outdated schemas
- ❌ New configuration options not listed
- ❌ Updated architecture not reflected in diagrams
- ❌ New errors/error codes not in catalog
- ❌ Changed workflows or data flows
- ❌ Updated dependencies or tech stack
- ❌ **Infrastructure/Terraform changes not reflected** (new resources, changed configs, updated IaC)
- ❌ **New deep-dive topics needed** (complex subsystems that warrant dedicated documentation)
- ❌ New runbooks needed for new operations
- ❌ Outdated troubleshooting guides

**Step 4: Create Update Plan**

Generate `.ai-workspace/UPDATE_PLAN.md` (in AI workspace) with:

```markdown
# Documentation Update Plan
Generated: [DATE]
Last Updated: [DATE OF LAST DOC CHANGE]

## Changes Detected

### Code Changes (from git)
- [commit hash] [date] - Brief description of change
- [which files affected]
- [documentation impact]

### Documentation Gaps

#### Critical (breaks understanding)
- [ ] File X: Feature Y added but not documented
- [ ] Doc A: API endpoint Z changed schema

#### Important (outdated information)
- [ ] File B: Configuration C no longer used
- [ ] Doc D: Diagram needs update for new architecture

#### Minor (improvements)
- [ ] File E: Add new troubleshooting section
- [ ] Doc F: Update version numbers

## Update Tasks

### [Document Name 1]
**File**: project-documentation/XX-document-name.md
**Last Modified**: [date]
**Status**: ⚠️ Needs Update

**Changes Required**:
1. Add section for new Feature X
2. Update API schema for endpoint Y
3. Remove deprecated configuration Z
4. Update diagram with new component

**Source Files to Reference**:
- path/to/new/feature.go
- path/to/changed/api.go

**Estimated Time**: 30 minutes

---

### [Document Name 2]
...

## Summary
- Total Documents: X
- Need Updates: Y
- Up to Date: Z
- Estimated Total Time: N hours

## Validation Checklist
After updates:
- [ ] All new features documented
- [ ] All deprecated features removed or marked
- [ ] All API schemas current
- [ ] All configuration options listed
- [ ] All diagrams reflect current architecture
- [ ] All error codes cataloged
- [ ] All runbooks cover current operations
- [ ] Cross-references still valid
```

**Step 5: Generate Project-Specific Prompt**

Create `.ai-workspace/PROJECT_UPDATE_PROMPT.md` (custom prompt for this project in AI workspace):

```markdown
# [Project Name] Documentation Update Prompt

## Quick Update (Use this for routine updates)

Update the documentation based on these recent changes:

**Changed Files**:
- [list of files from git diff]

**Documentation to Review** (in project-documentation/):
- [list of potentially affected docs]

**Specific Updates Needed**:
1. [specific task from update plan]
2. [specific task from update plan]
...

**Validation**:
- [ ] Verify against current source code
- [ ] Check all cross-references
- [ ] Update "Last Updated" dates
- [ ] Regenerate any auto-generated content

---

## Full Sync (Use this for major updates)

Perform a complete documentation refresh:

1. Re-read all source files in [key directories]
2. Compare against all docs in project-documentation/ directory
3. Update every outdated section
4. Add any missing documentation
5. Remove any obsolete content
6. Regenerate all diagrams
7. Verify all code examples still work
8. Check all links and references

Focus areas based on recent changes:
- [area 1]: [specific changes]
- [area 2]: [specific changes]
```

### Phase 2: Implementation

**Step 6: Execute Updates**
- Update documents according to the plan
- Preserve existing structure and style
- Maintain consistency with other documents
- Include actual code snippets from current source
- Update diagrams (Mermaid) if architecture changed
- Fix any broken cross-references
- Update "Last Updated" dates

**Step 7: Validation**
- ✅ All identified gaps are addressed
- ✅ No references to removed/deprecated features
- ✅ All API schemas match current code
- ✅ All configuration options are current
- ✅ All diagrams reflect current architecture
- ✅ Code examples work with current codebase
- ✅ Cross-references are valid

**Step 8: Generate Update Summary**

Create `.ai-workspace/UPDATE_SUMMARY.md` (in AI workspace):

```markdown
# Documentation Update Summary
Date: [DATE]

## What Was Updated
- [Document 1]: Added X, Updated Y, Removed Z
- [Document 2]: Updated schema for endpoint A
- [Document 3]: New troubleshooting section for B

## Changes Incorporated
- [N commits] from [date range]
- [N new features] documented
- [N deprecated features] removed
- [N API changes] reflected

## Next Review
Recommended: [DATE] or after next major release

## Auto-Update Prompt
Use this for next incremental update:
[Generated project-specific prompt based on this project's patterns]
```

---

## 🎯 When to Use This Prompt

### Routine Updates (Every Sprint/Release)
Use this when:
- New features were added
- APIs changed
- Configuration updated
- Before each release

### Major Sync (Quarterly or After Major Changes)
Use this when:
- Architecture significantly changed
- Major refactoring occurred
- Documentation hasn't been updated in months
- Tech stack was upgraded

### Continuous Maintenance
Integrate with CI/CD:
- Run after merging to main branch
- Automatically detect doc drift
- Flag outdated documentation in PRs

---

## 🔧 Advanced: Automated Detection

For maximum automation, this prompt can:

1. **Parse git log** for structural changes:
   ```
   git log --since="90 days ago" --name-only --pretty=format:"%H|%ad|%s"
   ```

2. **Detect API changes**:
   - Compare current OpenAPI/Swagger specs with documented schemas
   - Parse route definitions in code vs documented endpoints

3. **Find configuration drift**:
   - Compare env vars in code with documented config
   - Check Terraform outputs vs documented infrastructure

4. **Identify new errors**:
   - Grep for new error codes/messages
   - Compare with error catalog

5. **Track dependency changes**:
   - Compare package.json / go.mod / requirements.txt
   - Update tech stack documentation

---

## 📊 Output Files

After running this update prompt:

1. **`.ai-workspace/UPDATE_PLAN.md`** (AI workspace)
   - What needs updating and why
   - Task list with priorities
   - Time estimates

2. **`.ai-workspace/PROJECT_UPDATE_PROMPT.md`** (AI workspace)
   - Custom prompt for THIS project
   - Quick update vs full sync options
   - Project-specific focus areas

3. **`.ai-workspace/UPDATE_SUMMARY.md`** (AI workspace, after implementation)
   - What was changed
   - Commits incorporated
   - Next review date

4. **Updated `project-documentation/` files**
   - All documentation synchronized with code

**Note**: `.ai-workspace/` = AI workspace for planning. `project-documentation/` = actual documentation files.

---

## 💡 Best Practices

### Keep Docs Fresh
- Update docs **in the same PR** as code changes
- Review docs **before each release**
- Schedule quarterly documentation audits

### Make It Easy
- Use the generated PROJECT_UPDATE_PROMPT.md for routine updates
- Keep .ai-workspace/ files in git for history
- Document your documentation update frequency

### Automate Where Possible
- Add pre-commit hooks to remind about doc updates
- CI checks for outdated documentation
- Auto-generate API docs from code

### Track Documentation Debt
- Tag TODOs in docs with issue numbers
- Keep a DOCUMENTATION.md changelog
- Note "Last Verified" dates in each doc

---

## 🚀 Example Usage

### Scenario 1: After Sprint (Quick Update)
```bash
# In your project directory
# Paste the UPDATE PROMPT
# Claude generates update plan
# Review plan → Approve → Updates execute
# Result: 5-10 docs updated in 1-2 hours
```

### Scenario 2: Major Release (Full Sync)
```bash
# Paste the UPDATE PROMPT with "full sync" flag
# Claude does comprehensive comparison
# Generates detailed update plan
# Result: Complete doc refresh
```

### Scenario 3: Continuous (Automated)
```bash
# After each merge to main:
# CI runs update prompt
# Creates PR with doc updates
# Team reviews and merges
```

---

## 🔄 Integration with Original Documentation

This update prompt works with documentation created using the **UNIVERSAL_DOCUMENTATION_PROMPT**:
- Preserves the original structure
- Maintains the same style and format
- Updates content while keeping organization
- Works with .ai-workspace/ planning files

---

**Last Updated**: 2026-03-21
**Version**: 2.0
**Companion to**: UNIVERSAL_DOCUMENTATION_PROMPT.md v2.0
**Lessons from**: Kompliant-App 41-document suite
