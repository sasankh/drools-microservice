# Contributing

Thanks for your interest in improving the Drools Rule Engine Microservice.

## Getting started

- Read [`project-documentation/00-system-overview.md`](project-documentation/00-system-overview.md)
  for architecture and role-based reading paths.
- Local dev uses Docker. Java 25 is required for a local build; if you don't have it, run the build
  and tests inside a container (see below).

## Building and testing (Docker)

All tests run in Docker end-to-end. If you don't have Java 25 locally:

```bash
# Unit tests in a Java 25 Maven container
docker run --rm -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-25 mvn -B clean test

# Full stack end-to-end (see full-docker-test-plan.md)
docker compose up -d --build
```

The Testcontainers integration tests need a native Docker daemon (Linux/CI); they are excluded from
the default `mvn test` on macOS. CI runs them on Linux.

## Pull requests

1. Fork and create a feature branch: `git checkout -b feature/my-change`.
2. Make your change with tests.
3. Run `mvn spotless:apply` to format, then `mvn -B verify` (or the Docker command above).
4. Update documentation when you change APIs, configuration, or behavior.
5. Open a PR describing what changed and why. CI must pass.

## Coding standards

- Follow the existing style; `spotless` (Google Java Format) enforces formatting.
- Add or update unit tests for new behavior.
- Cite `file:line` in PR descriptions when explaining non-obvious changes.

## Security

Do not file security vulnerabilities as public issues — see [`SECURITY.md`](SECURITY.md).
