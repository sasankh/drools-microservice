# Documentation Lifecycle: Complete Workflow

A complete system for creating and maintaining project documentation using AI.

---

## 🔄 The Complete Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│                     NEW PROJECT                              │
│                          ↓                                   │
│         UNIVERSAL_DOCUMENTATION_PROMPT.md                    │
│                          ↓                                   │
│          [Analyze → Plan → Create Docs]                      │
│                          ↓                                   │
│              Complete Documentation                          │
│                          ↓                                   │
│                    Upload to NotebookLM                      │
│                          ↓                                   │
├─────────────────────────────────────────────────────────────┤
│                   TIME PASSES / CODE CHANGES                 │
├─────────────────────────────────────────────────────────────┤
│                          ↓                                   │
│          UNIVERSAL_UPDATE_PROMPT.md                          │
│                          ↓                                   │
│      [Detect Changes → Plan Updates → Update Docs]          │
│                          ↓                                   │
│              Documentation Synchronized                      │
│                          ↓                                   │
│                Re-upload to NotebookLM                       │
│                          ↓                                   │
└──────────────────[Repeat Update Cycle]──────────────────────┘
```

---

## 📚 Phase 1: Initial Documentation (CREATE)

### When to Use
- New project needs documentation
- Existing project with no/minimal docs
- Complete documentation refresh needed

### The Prompt
Use: **`UNIVERSAL_DOCUMENTATION_PROMPT.md`** or **`QUICK_PROMPT.txt`**

### Process
1. **Copy prompt** into Claude Code in project directory
2. **Claude analyzes** entire codebase
3. **Generates planning files** in `.ai-workspace/` (AI workspace for plans/checklists):
   - `DOCUMENTATION_PLAN.md` - Detailed specs
   - `CHECKLIST.md` - Task breakdown
   - `SUMMARY.md` - Overview
4. **Review plan** and request adjustments if needed
5. **Approve implementation**
6. **Claude writes** all docs in `project-documentation/` directory
7. **Upload to NotebookLM** for interactive Q&A

### Output
- ✅ Complete documentation suite in `project-documentation/` (15-45+ docs typically)
- ✅ Covers visual overview, architecture, infrastructure/IaC, APIs, operations, advanced deep dives
- ✅ Ready for onboarding and NotebookLM
- ✅ Planning artifacts in `.ai-workspace/` (AI workspace)
- ✅ Estimated: 20-150 hours of comprehensive work depending on project complexity

---

## 🔄 Phase 2: Ongoing Maintenance (UPDATE)

### When to Use

#### Routine Updates (Every Sprint/Release)
- After new features added
- APIs changed
- Configuration updated
- **Frequency**: Every 2-4 weeks

#### Major Sync (Quarterly)
- Architecture changes
- Major refactoring
- Tech stack upgrades
- **Frequency**: Every 3 months

#### Before Releases
- Ensure docs reflect release state
- **Frequency**: Before each major release

### The Prompt
Use: **`UNIVERSAL_UPDATE_PROMPT.md`** or **`QUICK_UPDATE_PROMPT.txt`**

### Process
1. **Copy update prompt** into Claude Code
2. **Claude detects changes**:
   - Reads git log
   - Compares code vs docs in `project-documentation/`
   - Identifies gaps
3. **Generates update plan** in `.ai-workspace/` (AI workspace):
   - `UPDATE_PLAN.md` - What needs updating
   - `PROJECT_UPDATE_PROMPT.md` - Custom prompt for future
   - `UPDATE_SUMMARY.md` - Summary after completion
4. **Review update plan**
5. **Approve updates**
6. **Claude updates** documentation in `project-documentation/`
7. **Re-upload to NotebookLM**

### Output
- ✅ Documentation synchronized with code
- ✅ New features documented
- ✅ Deprecated content removed
- ✅ Diagrams and examples updated
- ✅ Estimated: 1-8 hours depending on changes

---

## 📂 File Structure

After using both prompts, your project will have:

```
your-project/
├── project-documentation/                   # All documentation files
│   ├── README.md                           # Navigation index
│   ├── 00-system-architecture-diagram.md   # Visual overview
│   ├── 01-project-overview.md
│   ├── 02-project-structure.md
│   ├── ...                                 # 15-45+ documents across 11 categories (0-10)
│   └── XX-last-document.md
│
├── .ai-workspace/                          # AI workspace (plans, checklists, analysis)
│   ├── README.md                           # This folder explained
│   │
│   ├── UNIVERSAL_DOCUMENTATION_PROMPT.md   # Template: Create docs
│   ├── QUICK_PROMPT.txt                    # Quick version: Create
│   │
│   ├── UNIVERSAL_UPDATE_PROMPT.md          # Template: Update docs
│   ├── QUICK_UPDATE_PROMPT.txt             # Quick version: Update
│   │
│   ├── DOCUMENTATION_LIFECYCLE.md          # This file
│   │
│   ├── DOCUMENTATION_PLAN.md               # Initial creation plan
│   ├── CHECKLIST.md                        # Initial creation checklist
│   ├── SUMMARY.md                          # Initial creation summary
│   │
│   ├── UPDATE_PLAN.md                      # Latest update plan
│   ├── PROJECT_UPDATE_PROMPT.md            # Custom prompt for this project
│   └── UPDATE_SUMMARY.md                   # Latest update summary
│
└── README.md                               # Links to project-documentation/

Note: .ai-workspace/ = AI workspace for planning
      project-documentation/ = actual documentation files
```

---

## 🎯 Best Practices

### During Initial Creation
1. **Be thorough** - It's easier to maintain good docs than fix bad ones
2. **Include everything** - Architecture, operations, reference materials
3. **Use actual code** - Real examples, not pseudo-code
4. **Add diagrams** - Visual understanding is crucial
5. **Think onboarding** - Can a new person understand everything?

### During Maintenance
1. **Update docs with code** - Same PR, same time
2. **Schedule regular audits** - Quarterly full sync
3. **Use the custom prompt** - PROJECT_UPDATE_PROMPT.md gets smarter
4. **Track doc changes** - Keep UPDATE_SUMMARY.md history
5. **Validate thoroughly** - Run through validation checklist

### For Teams
1. **Shared responsibility** - Everyone updates docs
2. **PR checklist** - Include "Documentation updated?"
3. **Automated reminders** - CI checks for doc drift
4. **Regular reviews** - Monthly doc health checks
5. **Celebrate good docs** - Recognize maintainers

---

## 🚀 Quick Reference

### I need to...

**Create documentation for a new project**
→ Use `QUICK_PROMPT.txt` or `UNIVERSAL_DOCUMENTATION_PROMPT.md`

**Update docs after a sprint**
→ Use `QUICK_UPDATE_PROMPT.txt` with routine update mode

**Major documentation refresh**
→ Use `UNIVERSAL_UPDATE_PROMPT.md` with full sync mode

**Update docs for a specific feature**
→ Use generated `PROJECT_UPDATE_PROMPT.md` (quick update section)

**Share documentation approach with team**
→ Share `.ai-workspace/` folder and this file

**Use documentation for onboarding**
→ Upload `project-documentation/` to NotebookLM, share the chat interface

---

## 📊 Metrics to Track

### Documentation Health
- **Coverage**: % of codebase documented
- **Freshness**: Days since last update
- **Accuracy**: Open "doc is wrong" issues
- **Usage**: NotebookLM queries, doc views

### Maintenance Burden
- **Time per update**: Hours spent on routine updates
- **Update frequency**: How often docs updated
- **Drift detection**: Time to identify outdated docs
- **Automation level**: % automated vs manual updates

### Impact
- **Onboarding time**: Days for new dev to be productive
- **Support tickets**: Questions answered by docs
- **Integration success**: Partners using doc-only integration
- **Code reading**: % decrease in "read the code" responses

---

## 🔧 Advanced: Automation

### Git Hooks
```bash
# .git/hooks/pre-commit
# Remind about documentation
if git diff --cached --name-only | grep -qE "^(src/|lib/|cmd/)"; then
    echo "⚠️  Code changes detected. Have you updated documentation?"
    echo "   Run: Use QUICK_UPDATE_PROMPT.txt"
fi
```

### CI/CD Integration
```yaml
# .github/workflows/doc-check.yml
name: Documentation Check
on: [pull_request]
jobs:
  check-docs:
    runs-on: ubuntu-latest
    steps:
      - name: Check doc freshness
        run: |
          # Check if project-documentation/ is older than src/
          # Flag if documentation hasn't been updated
```

### Scheduled Updates
```yaml
# .github/workflows/doc-update.yml
name: Monthly Doc Audit
on:
  schedule:
    - cron: '0 0 1 * *'  # First day of each month
jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - name: Run update prompt
        # Automatically detect doc drift
        # Create issue with update plan
```

---

## 💡 Pro Tips

### For Maintainability
- Keep docs in markdown (easy to diff)
- Use Mermaid for diagrams (version controlled)
- Include "Last Updated" dates
- Link liberally between docs
- Add "Last Verified Against Code" dates

### For Discoverability
- Good navigation in docs/README.md
- Searchable keywords
- Table of contents in long docs
- Cross-references with context
- Index/glossary for terms

### For NotebookLM
- Use clear headings (improves AI parsing)
- Include examples (makes answers concrete)
- Explain acronyms (helps with Q&A)
- Add context (why, not just what)
- Keep files focused (easier chunking)
- Upload all files from `project-documentation/` directory

---

## 🎓 Learning Path

### Week 1: Setup
- Run UNIVERSAL_DOCUMENTATION_PROMPT on a project
- Review and refine the plan
- Complete Phase 1 (Foundation docs)

### Week 2-3: Initial Creation
- Complete all documentation phases
- Upload to NotebookLM
- Test with new team member

### Week 4: First Update
- Run UNIVERSAL_UPDATE_PROMPT
- Review update plan accuracy
- Refine PROJECT_UPDATE_PROMPT

### Month 2+: Routine Maintenance
- Use PROJECT_UPDATE_PROMPT after each sprint
- Monthly minor updates (1 hour)
- Quarterly full sync (4-8 hours)

---

## ✅ Success Indicators

You know the system is working when:
- ✅ New devs onboard using only documentation
- ✅ Support questions answered by docs
- ✅ Partners integrate without hand-holding
- ✅ Docs updated in same PR as code
- ✅ NotebookLM answers 80%+ of questions correctly
- ✅ Team references docs before asking questions
- ✅ Documentation debt stays minimal
- ✅ Updates take < 2 hours per sprint

---

## 📞 Support

### Common Issues

**"Documentation is overwhelming"**
→ Start with Quick Start guide, use NotebookLM for targeted questions

**"Docs get out of date too fast"**
→ Use PROJECT_UPDATE_PROMPT.md after each PR, automate reminders

**"Don't know what to document"**
→ The universal prompt handles this - it analyzes and plans

**"Updates take too long"**
→ Update in same PR as code (prevents batch updates)

**"NotebookLM gives wrong answers"**
→ Docs may be outdated - run update prompt

---

## 🔮 Future Enhancements

Potential additions to this system:
- Auto-generate API docs from OpenAPI specs
- Integrate with code comments for sync
- Visual diff of documentation changes
- Automated doc quality scoring
- Integration with linear/jira for doc tasks
- AI-powered doc review comments

---

**Version**: 2.0
**Created**: 2026-03-17
**Last Updated**: 2026-03-21

**Prompt Templates**:
- Create: `UNIVERSAL_DOCUMENTATION_PROMPT.md` + `QUICK_PROMPT.txt`
- Update: `UNIVERSAL_UPDATE_PROMPT.md` + `QUICK_UPDATE_PROMPT.txt`
