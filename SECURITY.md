# Security Policy

## Reporting a vulnerability

Please report security issues privately — do **not** open a public GitHub issue for a vulnerability.
Email the maintainers (see the repository owner's contact) with a description, reproduction steps, and
affected version/commit. We aim to acknowledge reports within a few business days.

## Supported versions

Security fixes target the `develop` branch (the default/release branch). There is no separate LTS branch at this time.

## Threat model: DRL is code

A `.drl` rule is not data. Its consequence (`then …`) is compiled to ordinary Java and runs inside
this service's JVM, with the service's credentials, filesystem, network access and threads. There
is no in-JVM sandbox: Java 25 removed the `SecurityManager` (JEP 486), and a text check cannot prove
arbitrary Java safe.

**Supported today: trusted DRL only** — rules written or reviewed by the operating team and
published through a controlled path. Under that model:

| Asset / action | Treat as | Required control (operational) |
|---|---|---|
| Write access to the rule bucket / rules directory | Code execution in the service | Least privilege; only the publishing role can write; reviewed changes |
| `POST /admin/refresh-rules[/{id}]` | Deploying that code | `ADMIN_API_KEY` (fail-closed on prod/docker); call only from trusted operators/automation |
| Publish on the Redis refresh channel | Triggering a reload | Redis auth + TLS (`rediss://`, enforced on prod); network-restricted |
| Executor's own S3 credentials | — | Read-only on the rule bucket |

What the service does to support that model:

- `DrlSanitizer` is a **policy check, not a security boundary**: it rejects obvious mistakes and
  dangerous constructs (I/O, reflection, process/thread control, class loading, `eval`,
  fully-qualified dangerous names, comment/whitespace tricks, `\uXXXX` escapes) before compiling,
  as defense in depth.
- Each `rule_id` runs only its own rules (ADR-025), so one rule can't change another's result.
- Every refresh, reconcile and delete writes one `AUDIT` log line (who, source, rule IDs, content
  digests, outcome; never DRL content or keys).
- A request that fires more than `drools.execution.max-rule-firings` times fails with 422
  `RULE_LIMIT_EXCEEDED`; a request that exceeds the timeout gets 408 and its worker is interrupted.
  **A consequence that never yields (`while (true) {}`) still keeps its worker thread until the
  process restarts** — it is counted by the `drools.rule.execution.stuck` gauge. Alert on it.

**Not supported: untrusted DRL** (customer-supplied, or AI-generated without human review). Before
accepting it, all of the following are prerequisites (not implemented):

1. Execute rules in a **separate low-privilege worker process/container**: no cloud credentials,
   read-only filesystem, no network egress, CPU/memory limits.
2. Enforce a **hard kill** (terminate the worker) on timeout instead of relying on thread interrupts.
3. A **review/approval step** with an immutable record (author, digest, approver, time) before a
   rule becomes active.
4. Per-tenant isolation of the worker if rules from different customers can run.

A constrained declarative format (e.g. DMN / approved expressions) is the other route; it changes
the authoring model and is a separate decision.

## Known security considerations

This service executes business rules authored as Drools `.drl` files loaded from a rule store
(S3 / local filesystem). Operators must understand the current trust boundaries:

- **Rule-store write access is equivalent to code execution** (B1, tracked-open). See the threat
  model above. The `DrlSanitizer` policy check now also catches fully-qualified dangerous names and
  comment/whitespace tricks (2026-11-18), but it remains a finite blocklist over Java code and can't
  be complete — it is defense in depth for trusted authors, not a boundary.
- **Admin endpoints (`/admin/*`)** are protected by the `X-Admin-API-Key` header (env `ADMIN_API_KEY`).
  In the `prod` and `docker` profiles the service **fails to start** if the key is blank. Never deploy
  with a blank admin key. Request paths containing `;`, `%`, `\`, `//` or `.`/`..` segments are
  rejected with 400 before authentication, and all filters match on the normalized (routed) path,
  so encoded or parameterised paths such as `/admin;x/health` or `/%61dmin/health` cannot bypass the
  key check (ADR-024; fixed 2026-11-17).
- **Management port (`8081`, `/actuator/*`)** has no authentication. Bind it to an internal interface
  only (loopback / private security group) — never expose it publicly.
- **Redis** must use `rediss://` with authentication in production (the `prod` profile enforces this).
  Local/dev stacks use loopback-bound plaintext Redis for convenience only.

## Hardening checklist for production

- [ ] Set a strong random `ADMIN_API_KEY`.
- [ ] Restrict write access to the rule store to trusted publishers only; the executor's own
      credentials are read-only.
- [ ] Ship the `AUDIT` log lines to your log store and alert on `drools.rule.execution.stuck > 0`.
- [ ] Do not accept untrusted DRL until the prerequisites in the threat model are in place.
- [ ] Keep `8081` off any public network.
- [ ] Use `rediss://` + auth for Redis.
- [ ] Put primary authentication/authorization at the API gateway in front of this service.
