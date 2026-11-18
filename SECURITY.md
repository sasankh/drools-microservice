# Security Policy

## Reporting a vulnerability

Please report security issues privately — do **not** open a public GitHub issue for a vulnerability.
Email the maintainers (see the repository owner's contact) with a description, reproduction steps, and
affected version/commit. We aim to acknowledge reports within a few business days.

## Supported versions

Security fixes target the `main` branch. There is no separate LTS branch at this time.

## Known security considerations

This service executes business rules authored as Drools `.drl` files loaded from a rule store
(S3 / local filesystem). Operators must understand the current trust boundaries:

- **Rule-store write access is equivalent to code execution.** A `.drl` consequence is compiled to
  ordinary Java. The current `DrlSanitizer` is a best-effort text filter and is **not** a sound
  sandbox — a fully-qualified class name needs no `import`, so a malicious rule can reach classes the
  sanitizer does not enumerate. Treat write access to the rule bucket, and access to the admin
  refresh endpoints, as equivalent to running code inside the service process. A load-time
  classloader allowlist plus out-of-process isolation is planned (Java 25 removed `SecurityManager`,
  JEP 486, so an in-JVM permission sandbox is no longer available).
- **Admin endpoints (`/admin/*`)** are protected by the `X-Admin-API-Key` header (env `ADMIN_API_KEY`).
  In the `prod` and `docker` profiles the service **fails to start** if the key is blank. Never deploy
  with a blank admin key.
- **Management port (`8081`, `/actuator/*`)** has no authentication. Bind it to an internal interface
  only (loopback / private security group) — never expose it publicly.
- **Redis** must use `rediss://` with authentication in production (the `prod` profile enforces this).
  Local/dev stacks use loopback-bound plaintext Redis for convenience only.

## Hardening checklist for production

- [ ] Set a strong random `ADMIN_API_KEY`.
- [ ] Restrict write access to the rule store to trusted publishers only.
- [ ] Keep `8081` off any public network.
- [ ] Use `rediss://` + auth for Redis.
- [ ] Put primary authentication/authorization at the API gateway in front of this service.
