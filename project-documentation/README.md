# project-documentation

This is the documentation corpus for the **Drools Rule Engine Microservice**. 38 markdown files designed to be uploaded as a NotebookLM source corpus or read directly.

**Start here**: [00-system-overview.md](00-system-overview.md) — entry point with role-based reading paths.

---

## Upload to NotebookLM

NotebookLM accepts up to 50 sources, each up to 500K characters. This corpus has 38 markdown files totaling ~28,000 lines (~900 KB) — well within limits.

### Upload steps

1. Create a new notebook at https://notebooklm.google.com.
2. Drag-and-drop **all 38 `.md` files** from this directory.
3. Also upload [`api-reference/openapi.yml`](api-reference/openapi.yml) for full API spec coverage.
4. Wait for indexing (~1-2 minutes for this corpus size).
5. Test with sample queries:
   - "What are the 7 security headers and their values?"
   - "Why does VIP discount with $100 produce $72 instead of $80?"
   - "How does rate limiting identify clients?"
   - "What's blocked by the DRL sandbox?"
   - "How do I rotate the admin API key?"

If answers come back accurate and cite the right docs, NotebookLM is ready for end users.

---

## File index

### Foundation (00-04)

| File | Lines | Purpose |
|---|---:|---|
| [00-system-overview.md](00-system-overview.md) | ~290 | **Start here.** Visual overview, role-based reading paths, doc index |
| [01-project-overview.md](01-project-overview.md) | ~100 | What/why/business value, performance targets |
| [02-project-structure.md](02-project-structure.md) | ~370 | Annotated repo tree with clickable file index |
| [03-tech-stack.md](03-tech-stack.md) | ~310 | Every dependency with version + rationale |
| [04-architecture.md](04-architecture.md) | ~1700 | System architecture: layers, threading, security, performance |

### Infrastructure & deployment (05-09)

| File | Lines | Purpose |
|---|---:|---|
| [05-environments-and-profiles.md](05-environments-and-profiles.md) | ~290 | 4 Spring profiles (`local`/`dev`/`prod`/`docker`) with full override matrix |
| [06-deployment.md](06-deployment.md) | ~1100 | Local + Docker + AWS ECS reference deployment |
| [07-docker-and-compose.md](07-docker-and-compose.md) | ~480 | Dockerfile + docker-compose.yml line-by-line |
| [08-configuration.md](08-configuration.md) | ~750 | Configuration primer (kept from original) |
| [09-environment-variables-reference.md](09-environment-variables-reference.md) | ~380 | All 66 env vars catalogued by category |

### APIs & integration (10-13)

| File | Lines | Purpose |
|---|---:|---|
| [10-api-reference.md](10-api-reference.md) | ~620 | Every endpoint with verified request/response shapes |
| [11-integration-guide.md](11-integration-guide.md) | ~600 | Code examples in curl, Python, Java, Node.js |
| [12-error-code-catalog.md](12-error-code-catalog.md) | ~440 | 10 error codes with HTTP status + cause + fix |
| [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) | ~370 | Multi-tier client identification, admin exemption |

### Security (14-16)

| File | Lines | Purpose |
|---|---:|---|
| [14-security-architecture.md](14-security-architecture.md) | ~360 | 8-layer defense-in-depth model |
| [15-admin-authentication.md](15-admin-authentication.md) | ~340 | Admin API key flow + dev-mode bypass |
| [16-drl-sandboxing.md](16-drl-sandboxing.md) | ~440 | Sandbox allowlist (20 imports) + blocklist (12 classes, 19 methods) + eval() ban |

### Rule authoring (17-23)

| File | Lines | Purpose |
|---|---:|---|
| [17-rule-development.md](17-rule-development.md) | ~950 | Comprehensive rule-author guide |
| [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md) | ~420 | Rule ID format, S3 path mapping, path-traversal protection |
| [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) | ~675 | All 10 sample rules with live-tested curl examples |
| [20-rule-generation-prompt.md](20-rule-generation-prompt.md) | ~360 | AI prompt for rule generation (full version) |
| [21-rule-generation-prompt-enhanced.md](21-rule-generation-prompt-enhanced.md) | ~390 | AI prompt with safety patterns |
| [22-rule-generation-prompt-concise.md](22-rule-generation-prompt-concise.md) | ~180 | AI prompt (concise) |
| [23-rule-language-reference.md](23-rule-language-reference.md) | ~3200 | Drools 8 upstream reference (with "[Not used in this project]" tags) |

### Performance & memory (24-26)

| File | Lines | Purpose |
|---|---:|---|
| [24-jvm-optimization.md](24-jvm-optimization.md) | ~280 | JVM flags + GC tuning |
| [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) | ~730 | Memory diagnostics + leak troubleshooting |
| [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) | ~610 | 10-branch decision tree for performance issues |

### Development (27-29)

| File | Lines | Purpose |
|---|---:|---|
| [27-development-setup.md](27-development-setup.md) | ~385 | New-contributor onboarding + conventions |
| [28-testing-guide.md](28-testing-guide.md) | ~460 | Test suite map (44 files, 589 tests, 96.2% coverage) |
| [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) | ~420 | Resilience4j wiring + state machine |

### Operations (30-31)

| File | Lines | Purpose |
|---|---:|---|
| [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) | ~525 | Operational runbooks + monitoring + alerts |
| [31-troubleshooting.md](31-troubleshooting.md) | ~935 | Common issues + fixes (kept from original) |

### Onboarding & reference (32-35)

| File | Lines | Purpose |
|---|---:|---|
| [32-getting-started.md](32-getting-started.md) | ~240 | <30-minute Docker quickstart |
| [33-simple-start.md](33-simple-start.md) | ~240 | Rule author quickstart |
| [34-java-setup-guide.md](34-java-setup-guide.md) | ~660 | Java 17 install (kept) |
| [35-faq.md](35-faq.md) | ~510 | 65+ Q&A across 10 categories |

### Advanced (36-37)

| File | Lines | Purpose |
|---|---:|---|
| [36-architecture-decision-records.md](36-architecture-decision-records.md) | ~615 | 12 ADRs + extension points appendix |
| [37-glossary.md](37-glossary.md) | ~310 | 70+ terms + acronyms defined |

### Reference assets

| Path | Purpose |
|---|---|
| [api-reference/openapi.yml](api-reference/openapi.yml) | OpenAPI 3.0 specification — machine-readable API contract |

---

## Reading paths by role

| Role | Read in this order |
|---|---|
| **New developer** | [32](32-getting-started.md) → [27](27-development-setup.md) → [02](02-project-structure.md) → [04](04-architecture.md) → [28](28-testing-guide.md) |
| **Architect** | [01](01-project-overview.md) → [04](04-architecture.md) → [14](14-security-architecture.md) → [29](29-circuit-breakers-and-resilience.md) → [36](36-architecture-decision-records.md) |
| **Operator** | [06](06-deployment.md) → [30](30-runbooks-and-monitoring.md) → [25](25-memory-monitoring-guide.md) → [26](26-performance-tuning-runbook.md) → [31](31-troubleshooting.md) |
| **Integrator** | [10](10-api-reference.md) → [11](11-integration-guide.md) → [12](12-error-code-catalog.md) → [13](13-rate-limiting-and-throttling.md) → [api-reference/openapi.yml](api-reference/openapi.yml) |
| **Rule author** | [33](33-simple-start.md) → [16](16-drl-sandboxing.md) → [17](17-rule-development.md) → [18](18-rule-id-and-storage-layout.md) → [19](19-sample-rules-cookbook.md) |
| **AI agent** | [00](00-system-overview.md) → [01](01-project-overview.md) → [02](02-project-structure.md) → [37](37-glossary.md) → [09](09-environment-variables-reference.md) → [12](12-error-code-catalog.md) → [16](16-drl-sandboxing.md) |

---

## Documentation principles

These docs follow these rules:

1. **Code is ground truth.** Every claim cites a `path/file.ext:line` link. When docs and code disagree, code wins.
2. **Tests are the most accurate spec.** [28-testing-guide.md](28-testing-guide.md) maps every "implicit spec" claim to the test that proves it.
3. **No marketing prose.** Docs document what the code does, including non-obvious behaviors and quirks.
4. **Honest about gaps.** When a feature is deferred, missing, or behaves surprisingly, the docs say so.
5. **Cross-linked.** Every doc has a frontmatter table linking related docs.
6. **NotebookLM-friendly.** Clear headings, defined acronyms (see [37-glossary.md](37-glossary.md)), no >120-line sections without subheadings.

---

## Maintenance

When code changes, the relevant docs need updating:

| Code change | Doc to update |
|---|---|
| New env var | [09-environment-variables-reference.md](09-environment-variables-reference.md) |
| New endpoint | [10-api-reference.md](10-api-reference.md), [api-reference/openapi.yml](api-reference/openapi.yml) |
| New error code | [12-error-code-catalog.md](12-error-code-catalog.md) |
| New filter | [04-architecture.md](04-architecture.md) filter chain section |
| Sandbox change (allowlist/blocklist) | [16-drl-sandboxing.md](16-drl-sandboxing.md) |
| New sample rule | [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) |
| Major design choice | New ADR in [36-architecture-decision-records.md](36-architecture-decision-records.md) |
| Test added/removed | [28-testing-guide.md](28-testing-guide.md) per-file counts |

If the docs go more than 2 sprints without an audit, run a verification pass: spot-check 10 random claims, run the cookbook curls live, refresh the env var catalog from `@Value` annotations.

---

## Provenance

This corpus was rebuilt 2026-05-08 from the prior 13-doc set. The rebuild:
- Verified every claim against actual source code
- Live-tested every curl example
- Found and fixed 32+ doc-vs-code inconsistencies (cataloged in `.ai-workspace/CODE_FINDINGS.md`)
- Extended coverage from ~65% of the 11-category framework to 100%

Tracking artifacts (in `.ai-workspace/`):
- `SUMMARY.md` — high-level overview of the rebuild
- `DOCUMENTATION_PLAN.md` — per-doc spec
- `CHECKLIST.md` — phase-by-phase completion log
- `CODE_FINDINGS.md` — code-vs-doc mismatches found during the rebuild

---

**Welcome.** Start with [00-system-overview.md](00-system-overview.md).
