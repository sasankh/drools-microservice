# 🤖 AI Assistant Onboarding - Start Here

**Welcome, AI Assistant!** This file contains instructions to quickly load the complete context for the **Drools Rule Engine Microservice** project.

---

## Quick Start Command

**IMPORTANT**: Instead of reading multiple files, simply read the **single consolidated context file**:

```
Read: ai-workspace/ai-initial-context/ai-initial-context-latest.md
```

This file contains complete project context and is the **fastest way to get up to speed**.

---

## Alternative: Manual Context Loading

If the ai-initial-context file doesn't exist or you need deeper detail, follow this sequence:

### Phase 0: AI Workflow Instructions (REQUIRED FIRST)

Read these files **first** to understand how to work with this project's AI workflow:

1. **[ai-instructions/snap-memory-instructions.md](snap-memory-instructions.md)** - How to create session logs
2. **[ai-instructions/ai-initial-context-instructions.md](ai-initial-context-instructions.md)** - How to update consolidated context

**After Phase 0, you should know:**
- How to create comprehensive snap-memory session logs
- When and how to update the ai-initial-context file
- The workflow for preserving session knowledge

---

### Phase 1: Core Project Understanding (REQUIRED)

Read these files **in this exact order** to understand what the Drools project is:

1. **[README.md](../README.md)** - Project overview, quick start, deployment
2. **[CLAUDE.md](../CLAUDE.md)** - Development workflow and current status
3. **[project-plan/project.prompt.md](../project-plan/project.prompt.md)** - Original implementation requirements

**After Phase 1, you should know:**
- What the Drools Rule Engine Microservice is (high-performance business rule execution)
- Tech stack (Java 17, Spring Boot 3.x, Drools 8.44.0, AWS S3, Redis, Docker)
- Performance targets (100-1000 RPS, P99 < 100ms)
- Current status (All 5 phases complete, production-ready)

---

### Phase 2: Project Planning & Implementation Status (REQUIRED)

**Read ALL files in the `project-plan/` directory** to understand what's been built:

4. **[project-plan/project.prompt.md](../project-plan/project.prompt.md)** - Original implementation requirements (if not already read)
5. **[project-plan/project.progress.md](../project-plan/project.progress.md)** - Detailed progress tracking
6. **[project-plan/project.checklist.md](../project-plan/project.checklist.md)** - Task completion status (85+ tasks)
7. **[project-plan/project.documentation.md](../project-plan/project.documentation.md)** - Complete project specifications
8. **[project-plan/project-validation-plan.md](../project-plan/project-validation-plan.md)** - Validation checklist

**After Phase 2, you should know:**
- All 5 phases are complete (Core Infrastructure, Storage & Caching, Production Readiness, Documentation, Deployment)
- What was implemented in each phase
- Known issues and deferred features
- Performance metrics achieved (2-100x better than targets)
- Validation approach and results

---

### Phase 3: Complete Documentation Suite (REQUIRED)

**Read ALL files in the `documentations/` directory** to understand the system design and operations:

9. **[documentations/deployment.md](../documentations/deployment.md)** - Deployment guide and architecture (800+ lines)
10. **[documentations/configuration.md](../documentations/configuration.md)** - Configuration reference (60+ env vars, 600+ lines)
11. **[documentations/rule-development.md](../documentations/rule-development.md)** - Rule development guide (5 examples, 800+ lines)
12. **[documentations/troubleshooting.md](../documentations/troubleshooting.md)** - Troubleshooting guide (50+ solutions, 700+ lines)
13. **[documentations/simple-start.md](../documentations/simple-start.md)** - Quick testing guide (250 lines)
14. **[documentations/rule-generation-prompt.md](../documentations/rule-generation-prompt.md)** - AI rule generation guide (360 lines)
15. **[documentations/rule-generation-prompt-enhanced.md](../documentations/rule-generation-prompt-enhanced.md)** - Enhanced with safety patterns (450 lines)
16. **[documentations/rule-generation-prompt-concise.md](../documentations/rule-generation-prompt-concise.md)** - Quick copy-paste version (150 lines)

17. **[api-documentation.yml](../api-documentation.yml)** - OpenAPI 3.0 specification (983 lines, 8 endpoints)

**After Phase 3, you should know:**
- Multi-tier caching architecture (S3 → Redis → LRU)
- Storage abstraction with factory pattern
- Security hardening (validation, rate limiting, CORS, log sanitization)
- Docker containerization (347MB optimized image)
- LocalStack integration for offline development
- How to create and test Drools rules
- How to troubleshoot common issues
- How to use AI assistance for rule generation
- Complete API endpoints and configuration options

---

### Phase 4: Sample Rules (OPTIONAL - Read if working with rules)

Read these to understand the available sample rules:

18. **[sample-rules/README.md](../sample-rules/README.md)** - 10 sample business rules documentation

**After Phase 4, you should know:**
- All 10 available sample rules (pricing, shipping, validation, seasonal)
- Rule ID to file path transformation (`pricing.discount.vip` → `pricing/discount/vip.drl`)
- How to test rules with LocalStack S3
- Example rule patterns and structures

---

### Phase 5: Session History (OPTIONAL - Read latest snap-memory if available)

Check for recent snap-memory files to understand latest work:

19. **[ai-workspace/snap-memory/](../ai-workspace/snap-memory/)** - Session logs (18 files)

**Command to find latest:**
```bash
ls -t ai-workspace/snap-memory/ | head -1
```

**After Phase 5, you should know:**
- What was accomplished in recent sessions
- Recent decisions and architectural changes
- Any issues encountered and resolved
- Current development focus

---

## Context Loading Checklist

Use this checklist to confirm you've loaded the context:

### Essential Context (Complete First)
- [ ] Project overview understood (high-performance rule engine)
- [ ] Current status identified (all phases complete, production-ready)
- [ ] Technical architecture comprehended (multi-tier caching, storage abstraction)
- [ ] Performance metrics known (1.5s startup, 1-40ms execution, 347MB image)
- [ ] Key capabilities clear (S3 storage, rule hot-reload, 10 sample rules)

### Working Context (Task-Dependent)
- [ ] Development setup reviewed (Docker Compose + LocalStack + Redis)
- [ ] Rule development guide consulted (if working with rules)
- [ ] Documentation suite reviewed (if updating docs)
- [ ] Latest snap-memory read (if available)

### Operational Context (Deployment/Troubleshooting)
- [ ] Deployment guide reviewed
- [ ] Configuration reference consulted
- [ ] Troubleshooting guide available
- [ ] Validation checklist reviewed

---

## Quick Reference: Where to Find What

| Information Needed | File to Read |
|-------------------|--------------|
| "What is this project?" | `README.md`, `ai-workspace/ai-initial-context/ai-initial-context-latest.md` |
| "What's the current status?" | `CLAUDE.md`, `project-plan/project.progress.md` |
| "How does it work?" | `documentations/deployment.md`, `documentations/api-documentation.yml` |
| "What was built?" | `project-plan/project.progress.md`, `project-plan/project.checklist.md` |
| "How do I configure it?" | `documentations/configuration.md` |
| "How do I create rules?" | `documentations/rule-development.md`, `sample-rules/README.md` |
| "How do I deploy it?" | `documentations/deployment.md` |
| "How do I troubleshoot?" | `documentations/troubleshooting.md` |
| "What happened last session?" | `ai-workspace/snap-memory/snap-memory-{latest}.md` |
| "What are the APIs?" | `documentations/api-documentation.yml` |

---

## After Loading Context: What to Do

### 1. Confirm Understanding
Briefly summarize back to the user:
```
I've loaded the Drools Rule Engine Microservice context. Here's what I understand:

- **Project**: High-performance business rule execution microservice
- **Status**: All 5 phases complete - Production ready
- **Tech Stack**: Java 17, Spring Boot 3.x, Drools 8.44.0, Docker
- **Performance**: 1.5s startup, 1-40ms rule execution, 347MB Docker image
- **Features**: S3 storage, multi-tier caching, 10 sample rules, LocalStack dev setup
- **Next Steps**: [Based on latest snap-memory or user request]

Ready to help! What would you like to work on?
```

### 2. Ask Clarifying Questions (if needed)
- If latest snap-memory shows ongoing work, ask about status
- If user wants to add features, understand requirements first
- If troubleshooting, gather error details and context

### 3. Be Proactive
- Suggest improvements based on project patterns
- Flag any documentation inconsistencies
- Offer to create snap-memory after significant sessions
- Offer to update ai-initial-context if major changes made

---

## Special Commands

### "snap-memory" or "create snap memory"
When user says this:
1. Read `ai-instructions/snap-memory-instructions.md`
2. Create comprehensive session log following that template
3. Save to `ai-workspace/snap-memory/snap-memory-{epoch}.md`

### "ai-context-update"
When user says this:
1. Read `ai-instructions/ai-initial-context-instructions.md`
2. Follow the steps exactly:
   - Archive current `ai-initial-context-latest.md` with epoch timestamp
   - Write fresh complete context to new `ai-initial-context-latest.md`
   - Confirm to user with file paths

### "What's next?"
When user says this:
1. Read latest `ai-workspace/snap-memory/` file
2. Check `CLAUDE.md` for current development focus
3. Review any open issues or deferred features in `project.progress.md`
4. Suggest prioritized next steps

### Context Compaction
When auto-compact or manual compact (`/compact` command) happens:
1. The system will provide a compact summary of the conversation
2. **IMPORTANT**: Save to `ai-workspace/compact-logs/compact-{epoch}.md`
3. Use current epoch timestamp: `date +%s`
4. Include the full compact output in the file
5. This helps preserve conversation history across sessions

---

## Project-Specific Guidelines

### When Working with Rules
- Follow patterns from `documentations/rule-development.md`
- Use sample rules in `sample-rules/` as templates
- Test with LocalStack S3 using `./init-localstack.sh`
- Rule ID format: `{domain}.{category}.{specific}`
- File path format: `{domain}/{category}/{specific}.drl`
- Package format: `com.company.rules.{domain}.{category}`

### When Making Changes
- Understand existing architecture before modifying
- Follow established patterns (factory, abstraction layers)
- Update documentation if changing APIs or configuration
- Test with Docker: `./docker-build-test.sh`
- Run code formatting: `mvn spotless:apply`

### When Deploying
- Use Docker Compose for local dev: `./setup-dev-environment.sh`
- Docker image is 347MB optimized multi-stage build
- LocalStack provides S3 emulation for offline development
- 10 sample rules available in `sample-rules/` directory
- Health checks on port 8081: `/admin/health`

### When Documenting
- API changes: Update `documentations/api-documentation.yml`
- Configuration: Update `documentations/configuration.md`
- Rules: Update `documentations/rule-development.md`
- Deployment: Update `documentations/deployment.md`
- Session work: Create snap-memory with `snap-memory` command
- Major changes: Update ai-initial-context with `ai-context-update`

### When Stuck
1. Check `documentations/troubleshooting.md` - comprehensive guide
2. Check latest snap-memory - was this encountered before?
3. Check `project-plan/project.progress.md` - known issues documented?
4. Read relevant documentation in `documentations/` folder
5. Ask the user for clarification

---

## Directory Structure Reference

```
drools-microservice/
├── project-plan/              # Project planning and tracking
│   ├── project.prompt.md      # Original requirements
│   ├── project.progress.md    # Implementation progress
│   ├── project.checklist.md   # Task tracking
│   ├── project.documentation.md # Complete specifications
│   └── project-validation-plan.md # Validation checklist
│
├── ai-workspace/              # AI working documents
│   ├── ai-initial-context/    # Consolidated context files
│   │   └── ai-initial-context-latest.md # Main context file
│   ├── snap-memory/           # Session logs (18 files)
│   │   └── snap-memory-{epoch}.md
│   └── compact-logs/          # Context compaction logs
│       └── compact-{epoch}.md
│
├── documentations/            # Complete documentation suite (~5,100 lines)
│   ├── api-documentation.yml  # OpenAPI 3.0 spec (8 endpoints)
│   ├── deployment.md          # Deployment guide (800+ lines)
│   ├── configuration.md       # Config reference (600+ lines, 60+ vars)
│   ├── rule-development.md    # Rule guide (800+ lines, 5 examples)
│   ├── troubleshooting.md     # Troubleshooting (700+ lines, 50+ solutions)
│   ├── rule-generation-prompt.md # AI rule generation guide
│   ├── rule-generation-prompt-enhanced.md # Enhanced with safety patterns
│   ├── rule-generation-prompt-concise.md # Quick version
│   └── simple-start.md        # Quick testing guide
│
├── ai-instructions/           # AI assistant instructions
│   ├── ai-start-prompt.md     # THIS FILE - Start here
│   ├── snap-memory-instructions.md # Session log template (universal)
│   └── ai-initial-context-instructions.md # Context update instructions (project-specific)
│
├── sample-rules/              # 10 business rules for testing
│   ├── pricing/discount/      # Discount rules (simple, vip, bulk, first-time)
│   ├── pricing/shipping/      # Shipping rules (standard, express)
│   ├── validation/customer/   # Validation rules (age, credit)
│   ├── seasonal/holiday/      # Seasonal rules (discount, blackfriday)
│   └── README.md              # Sample rules documentation
│
├── src/main/java/            # Java source code (53 files)
│   └── com/company/drools/
│       ├── api/              # REST endpoints, DTOs, validation, filters
│       ├── core/             # Business logic and rule engine
│       ├── storage/          # Storage abstraction (S3, local, memory)
│       ├── cache/            # Caching layer (LRU, Redis)
│       ├── common/           # Shared utilities
│       └── config/           # Spring configuration
│
├── scripts/                  # Automation scripts
│   ├── setup-dev-environment.sh # One-command setup
│   ├── init-localstack.sh   # LocalStack initialization
│   ├── test-localstack.sh   # LocalStack validation
│   └── docker-build-test.sh # Docker validation
│
├── Dockerfile                # Multi-stage Docker build (347MB)
├── docker-compose.yml        # Dev stack (LocalStack + Redis + App)
├── pom.xml                   # Maven configuration
├── README.md                 # User documentation
└── CLAUDE.md                 # Development guide
```

---

## Project Status Summary

### Completed Phases (All 5)
- ✅ **Phase 1**: Core Infrastructure (23 tasks) - Drools integration, REST API
- ✅ **Phase 2**: Storage & Caching (29 tasks) - S3, Redis, multi-tier caching
- ✅ **Phase 3**: Production Readiness (22 tasks) - Security, performance, monitoring
- ✅ **Phase 4**: Documentation (6 tasks Phase 4.4) - Complete doc suite (~5,100 lines)
- ✅ **Phase 5**: Deployment (10 tasks) - Docker, LocalStack, automation

### Key Metrics
- **Performance**: 2-100x better than targets (1.5s startup, 1-40ms execution)
- **Docker Image**: 347MB optimized (13% under target)
- **Documentation**: ~5,100 lines across 10 files
- **Sample Rules**: 10 comprehensive business rules
- **Configuration**: 60+ environment variables documented
- **Validation**: 95% working, production-ready

### Current State
- **Status**: Production-ready, all core features complete
- **Next Options**: Production deployment, additional testing, enhancements
- **Known Issues**: 4 minor non-blocking enhancements identified
- **Deferred**: Unit tests (Phase 4.1-4.3) per user direction

---

## Quick Action Commands

| User Says | You Do |
|-----------|--------|
| "Load context" | Read `ai-workspace/ai-initial-context/ai-initial-context-latest.md` (fastest) |
| "Full context" | Follow Phase 0 (AI workflow) + all 5 project phases above |
| "Quick context" | Read README + CLAUDE.md + ai-initial-context |
| "What's the status?" | Read CLAUDE.md + project.progress.md + latest snap-memory |
| "snap-memory" | Follow snap-memory-instructions.md |
| "ai-context-update" | Follow ai-initial-context-instructions.md |
| "What's next?" | Check latest snap-memory + CLAUDE.md + suggest next steps |
| "/compact" (or auto-compact) | Save to `ai-workspace/compact-logs/compact-{epoch}.md` |
| "How do I test rules?" | Explain LocalStack setup and sample rules usage |
| "How do I deploy?" | Reference deployment.md and Docker setup |

---

**Remember**: This project is **production-ready**. All 5 phases are complete with comprehensive documentation. The goal is to help maintain, enhance, or deploy the system. By loading this context, you can jump in and contribute effectively.

**Good luck! 🚀**
