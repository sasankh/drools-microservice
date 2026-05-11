# Checklist: Sonar Security Hotspots — S5852 ReDoS Fixes

> **STATUS: ✅ COMPLETED 2026-05-11** — Both hotspots resolved. Commit: `2b7b5bd`. Quality Gate OK, 0 security hotspots TO_REVIEW.

**Branch:** `upgrade-java-sonar`
**Hotspots:** 2 → 0
**Result:** Quality Gate OK

---

## Pre-flight

- [x] Confirm branch is `upgrade-java-sonar`
- [x] Confirm 597 tests pass
- [x] Confirm Quality Gate OK

---

## Step 1 — `CorsConfig.java` (Hotspot 1)

**File:** `src/main/java/com/company/drools/config/CorsConfig.java`

- [x] Locate `parseCommaSeparatedValues` method (line ~82)
- [x] Replace `return Arrays.asList(value.split("\\s*,\\s*"));` with:
  ```java
  return Arrays.stream(value.split(","))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .toList();
  ```
- [x] Confirm `import java.util.Arrays` still present (still needed for `Arrays.stream`)
- [x] Confirm behavior unchanged: `"GET, POST"` → `["GET", "POST"]`

---

## Step 2 — `DrlSanitizer.java` (Hotspot 2)

**File:** `src/main/java/com/company/drools/core/engine/DrlSanitizer.java`

- [x] Locate `IMPORT_PATTERN` constant
- [x] Replace `\s` with `\h` (horizontal whitespace — excludes `\n`, preserving MULTILINE `^`/`$` anchors) and convert all quantifiers to possessive:
  ```java
  Pattern.compile("^\\h*+import\\h++(static\\h++)?([\\w.]+\\*?)\\h*+;?+\\h*+$", Pattern.MULTILINE)
  ```
  Note: initial plan used `\\s*+` — changed to `\\h*+` after 7 tests failed because possessive `\s` consumed `\n`, breaking MULTILINE mode.
- [x] Verify `DrlSanitizerTest` passes (all parameterized import tests, including blocked-import variants)

---

## Step 3 — Verify: Tests

- [x] `docker run ... mvn clean test -Dtest='!S3StorageIntegrationTest'`
- [x] Confirm: `Tests run: 597, Failures: 0, Errors: 0`
- [x] Confirm: `BUILD SUCCESS`
- [x] `DrlSanitizerTest` passes (parameterized import tests)

---

## Step 4 — Verify: SpotBugs

- [x] `docker run ... mvn compile spotbugs:check`
- [x] Confirm: `BUILD SUCCESS`

---

## Step 5 — Commit

- [x] `git add src/main/java/com/company/drools/config/CorsConfig.java`
- [x] `git add src/main/java/com/company/drools/core/engine/DrlSanitizer.java`
- [x] Commit: `fix(security): resolve S5852 ReDoS hotspots — CorsConfig regex split + DrlSanitizer possessive quantifiers` (`2b7b5bd`)

---

## Step 6 — Sonar Scan + Verify

- [x] Run Sonar scan via Docker
- [x] Confirm `BUILD SUCCESS`
- [x] SonarQube UI → Security Hotspots → **0 TO_REVIEW**
- [x] Quality Gate still OK

---

## Notes

- **Possessive quantifiers** (`*+`, `++`, `?+`): Supported in Java 5+. Once consumed, never give
  back characters. Eliminates backtracking on the whitespace quantifiers in `IMPORT_PATTERN`.
- **`\h` vs `\s`**: Used `\h` (horizontal whitespace) not `\s` because `\s` matches `\n` —
  possessive `\s*+` would consume the newline and break MULTILINE `^`/`$` anchors.
- **CorsConfig input**: Config values from `@Value` — low DoS risk in practice, but fix is trivial.
- **DrlSanitizer input**: User-supplied DRL content — genuine attack surface; possessive fix is
  the correct security fix.
