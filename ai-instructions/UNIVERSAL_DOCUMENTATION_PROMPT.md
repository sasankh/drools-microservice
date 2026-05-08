# Universal Project Documentation Prompt

Copy and paste this prompt into any project to generate comprehensive documentation.

---

## 📋 THE PROMPT (Copy from here)

I need comprehensive, exhaustive documentation for this entire project. The goal is to create documentation so thorough that anyone can understand everything about the project without reading the source code, and use it with NotebookLM for interactive Q&A.

### Requirements

**Create a documentation plan and checklist** that covers:

0. **Visual Overview** (Always create this first)
   - System architecture diagram (Mermaid) - high-level entry point
   - Component architecture diagram
   - Infrastructure architecture diagram
   - Data flow architecture (sequence diagrams)
   - Technology stack summary table
   - Key integrations overview
   - Navigation links to all detailed documentation below

1. **Foundation & Architecture**
   - Project overview: What it is, why it exists, business purpose, tech stack
   - Project structure: Complete directory tree with explanations
   - System architecture: High-level design, data flows, component interactions
   - Environments: All environments, configurations, deployment targets

2. **Infrastructure & Infrastructure as Code** (CRITICAL - Don't skip!)
   - ALL cloud resources (compute, storage, networking, databases, queues, caching, etc.)
   - Terraform/CloudFormation/CDK/Pulumi configurations (complete IaC deep dive)
   - State management, backend configuration, workspaces/environments
   - Network architecture: VPCs, subnets, security groups, routing
   - IAM roles, policies, permissions (execution roles, task roles, service roles)
   - Container orchestration (ECS/EKS/Kubernetes task definitions, services, auto-scaling)
   - Load balancing, DNS, SSL/TLS configuration
   - Deployment guard mechanisms, safety controls, account validation
   - Environment-specific configurations and differences (dev/stage/prod)
   - Resource dependencies and deployment order
   - Terraform workflows, commands, troubleshooting

3. **APIs & Integration**
   - All API endpoints with complete request/response schemas
   - External service integrations (APIs, SDKs, webhooks)
   - Authentication and authorization mechanisms
   - Rate limiting, retry logic, error handling

4. **Data & Security**
   - Data models: Database schemas, domain models, relationships
   - Encryption: How data is encrypted, keys managed, what's protected
   - Security: IAM policies, network security, secrets management, compliance
   - Data lifecycle: How data flows through the system, retention, archival

5. **Development**
   - Coding patterns: Design patterns, conventions, best practices used
   - Testing: Framework, strategies, how to run tests, test environments
   - Build & deployment: How to build, deploy, CI/CD pipelines, release process

6. **Advanced Topics**
   - Complex workflows: Special business logic, transformations, algorithms
   - Async processing: Message queues, event-driven architecture, state machines
   - Performance: Optimization strategies, caching, scaling patterns
   - Partner configuration systems (multi-tenant configurations)

7. **Operational**
   - Getting started: Complete developer onboarding guide (setup to first deployment)
   - Runbooks: Operational procedures (deployment, rollback, incident response, maintenance)
   - Monitoring: Observability setup, logs, metrics, alerts, dashboards, debugging

8. **Reference Materials**
   - Visual diagrams: Architecture diagrams, sequence diagrams, data flows (use Mermaid)
   - Integration guide: How external partners/systems can integrate with this project
   - Error codes: Complete catalog of all errors, causes, resolutions
   - Glossary: All domain terms, acronyms, technical jargon explained
   - Query patterns: How to query data stores (with CLI and code examples)
   - Configuration reference: All configuration options consolidated in one place

9. **Onboarding & Quick Start**
   - Prerequisites: What developers need installed/configured
   - Quick start: Get running locally in minimal steps
   - Common tasks: Frequent developer workflows
   - Troubleshooting: Common issues and solutions
   - FAQ: Frequently asked questions

10. **Advanced Guides & Deep Dives** (For complex systems)
   - Architecture Decision Records (ADRs): Historical context for major technical decisions
   - Extension Points Guide: How to extend the system (add providers, processors, integrations)
   - Code Templates: Copy-paste ready templates for common development tasks
   - Scenarios Cookbook: End-to-end implementation examples with complete code
   - Architecture Evolution: Patterns for evolving architecture (microservices, CQRS, migrations)
   - Dependency Map: System dependency graphs and impact analysis
   - Advanced Testing: Complex testing scenarios, flaky test analysis, testing strategies
   - Troubleshooting Trees: Decision trees for systematic problem diagnosis
   - **Domain-Specific Deep Dives** (create as needed for complex subsystems):
     - Account/Resource Provisioning Architecture (two-layer patterns, sponsor registry, idempotency)
     - External API Implementation Patterns (controllers, serializers, validators, transformers, middleware)
     - Webhook Implementation Details (EventBridge integration, encryption, payload versioning)
     - Infrastructure Deep Dive (comprehensive Terraform/IaC guide beyond basic infrastructure doc)

### Process

1. **Analysis Phase**
   - Thoroughly explore the entire codebase
   - Identify project type (web app, API, serverless, microservices, etc.)
   - Map out all components, services, and dependencies
   - Understand the tech stack and architecture patterns
   - **Check for Infrastructure as Code** (terraform/, cloudformation/, cdk/, pulumi/ directories)
   - **Identify complex subsystems** that may need deep-dive documentation
   - Read existing documentation (README, comments, wikis)

2. **Planning Phase**
   - Create `.ai-workspace/` directory for AI planning artifacts (workspace for plans, checklists, analysis)
   - Create `DOCUMENTATION_PLAN.md`: Detailed spec for each document to be written
   - Create `CHECKLIST.md`: Task breakdown with time estimates and dependencies
   - Create `SUMMARY.md`: Overview of the plan
   - List should be tailored to THIS specific project (not all projects need all docs)

3. **Implementation Phase** (only after plan approval)
   - Create `project-documentation/` directory in project root (for actual documentation)
   - Write all documentation files according to the plan
   - Include code examples from actual source
   - Use diagrams (Mermaid) for visual explanations
   - Cross-reference between documents
   - Verify accuracy against source code
   - Create `project-documentation/README.md` as the main navigation index
   - Update root `README.md` to link to documentation

### Success Criteria

The documentation is complete when:
- ✅ A new developer can onboard and be productive without asking questions
- ✅ An operator can deploy, monitor, and troubleshoot independently
- ✅ An architect can understand the complete design without reading code
- ✅ A partner/client can integrate using only the documentation
- ✅ Any error or issue can be debugged using the docs
- ✅ All technical terms and acronyms are defined
- ✅ All configuration options are documented
- ✅ Visual diagrams explain the system intuitively
- ✅ The documentation can be uploaded to NotebookLM for interactive Q&A

### Important Notes

- **Do NOT start writing documentation yet** - create the plan first
- Tailor the documentation structure to fit THIS project specifically
- Some projects may need more docs, some may need fewer
- Focus on what would be most valuable for THIS codebase
- Include actual code snippets, not pseudo-code
- Use the project's actual file paths, function names, and configurations
- Make it searchable and compatible with AI tools like NotebookLM

---

## 🎯 Expected Deliverables

After running this prompt, you should receive:

1. **`.ai-workspace/DOCUMENTATION_PLAN.md`** (AI workspace)
   - Complete specification for every document
   - What topics each document covers
   - Source files to reference
   - Estimated complexity

2. **`.ai-workspace/CHECKLIST.md`** (AI workspace)
   - Task-by-task breakdown
   - Time estimates per document
   - Dependencies between documents
   - Progress tracking

3. **`.ai-workspace/SUMMARY.md`** (AI workspace)
   - High-level overview
   - Total document count
   - Total estimated time
   - Success criteria

4. **`project-documentation/` directory** (after implementation)
   - All documentation files
   - `project-documentation/README.md` (navigation index)
   - Diagrams and examples

**Note**: `.ai-workspace/` is your AI workspace for planning artifacts. `project-documentation/` contains the actual documentation.

## 📝 How to Use This Prompt

### Step 1: Navigate to Your Project
```bash
cd /path/to/your/project
```

### Step 2: Copy and Paste the Prompt
Copy everything from "📋 THE PROMPT (Copy from here)" section above and paste it into Claude Code.

### Step 3: Review the Plan
Claude will analyze your project and create a tailored documentation plan in `.ai-workspace/`.

### Step 4: Approve and Implement
Review the plan, make adjustments if needed, then approve for Claude to write all the documentation.

### Step 5: Export to NotebookLM
Once complete, upload all markdown files from `project-documentation/` to NotebookLM for interactive Q&A.

## 🔄 Customization Tips

You can customize the prompt for specific needs:

- **For microservices**: Add "Service-to-service communication patterns"
- **For frontend apps**: Add "Component library, state management, routing"
- **For data pipelines**: Add "ETL workflows, data quality, lineage"
- **For ML projects**: Add "Model training, inference, feature engineering"
- **For mobile apps**: Add "Platform-specific considerations, app store deployment"

## ✨ Why This Works

This approach produces documentation that:
- **Comprehensive**: Covers everything, not just happy paths
- **Accurate**: Verified against actual source code
- **Accessible**: Can be understood without domain expertise
- **Actionable**: Includes runbooks, examples, troubleshooting
- **Maintainable**: Structured for easy updates
- **AI-Ready**: Works perfectly with NotebookLM for interactive learning

## 📊 Example Results

### vendor-tr Project (Smaller Project)
- **23 comprehensive documents**
- **~30 hours of estimated work**
- **Complete coverage** of 10 Lambda functions, 8 verification types, full AWS infrastructure
- **Operational readiness** with runbooks, monitoring, and troubleshooting guides

### Kompliant-App Project (Large Enterprise Project)
- **41 comprehensive documents** (1 visual overview + 28 foundation + 12 advanced deep dives)
- **~125-145 hours of estimated work**
- **Complete coverage** of 67 models, 49+ jobs, complex workflows, multi-tenant architecture
- **Full infrastructure** including Terraform deep dive (18 .tf files documented)
- **Advanced deep dives** for provisioning, API implementation, webhooks, and infrastructure
- **1.9 MB, 310,000+ words, 400-460 pages**
- **Ready for NotebookLM** interactive Q&A

---

**Last Updated**: 2026-03-21
**Version**: 2.0
**Based on**: Kompliant-App documentation project (41 documents)
