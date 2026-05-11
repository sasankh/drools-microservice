# Checklist: Sonar Security Hotspots — S5852 ReDoS Fixes

> **STATUS: ✅ COMPLETED 2026-05-11** — Both hotspots resolved. Commit: `2b7b5bd`. Quality Gate OK, 0 security hotspots TO_REVIEW. Items below preserved as reference.

**Branch:** `upgrade-java-sonar`
**Hotspots:** 2 → 0
**Result:** Quality Gate OK

---

## Pre-flight

- [ ] Confirm branch is `upgrade-java-sonar`
- [ ] Confirm 597 tests pass
- [ ] Confirm Quality Gate OK

---

## Step 1 — `CorsConfig.java` (Hotspot 1)

**File:** `src/main/java/com/company/drools/config/CorsConfig.java`

- [ ] Locate `parseCommaSeparatedValues` method (line ~82)
- [ ] Replace `return Arrays.asList(value.split("\\s*,\\s*"));` with:
  ```java
  return Arrays.stream(value.split(","))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .toList();
  ```
- [ ] Confirm `import java.util.Arrays` still present (still needed for `Arrays.stream`)
- [ ] Confirm behavior unchanged: `"GET, POST"` → `["GET", "POST"]`

---

## Step 2 — `DrlSanitizer.java` (Hotspot 2)

**File:** `src/main/java/com/company/drools/core/engine/DrlSanitizer.java`

- [ ] Locate `IMPORT_PATTERN` constant
- [ ] Replace:
  ```java
  Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+\\*?)\\s*;?\\s*$", Pattern.MULTILINE)
  ```
  with:
  ```java
  Pattern.compile("^\\s*+import\\s++(static\\s++)?([\\w.]+\\*?)\\s*+;?+\\s*+$", Pattern.MULTILINE)
  ```
- [ ] Verify all `\s*` → `\s*+`, all `\s+` → `\s++`, `;?` → `;?+`
- [ ] No other changes to the file needed

---

## Step 3 — Verify: Tests

- [ ] `docker run ... mvn clean test -Dtest='!S3StorageIntegrationTest'`
- [ ] Confirm: `Tests run: 597, Failures: 0, Errors: 0`
- [ ] Confirm: `BUILD SUCCESS`
- [ ] Specifically check `DrlSanitizerTest` passes (parameterized import tests)

---

## Step 4 — Verify: SpotBugs

- [ ] `docker run ... mvn compile spotbugs:check`
- [ ] Confirm: `BUILD SUCCESS`

---

## Step 5 — Commit

- [ ] `git add src/main/java/com/company/drools/config/CorsConfig.java`
- [ ] `git add src/main/java/com/company/drools/core/engine/DrlSanitizer.java`
- [ ] Commit: `fix(security): resolve S5852 ReDoS hotspots — CorsConfig regex split + DrlSanitizer possessive quantifiers`

---

## Step 6 — Sonar Scan + Verify

- [ ] Run Sonar scan via Docker
- [ ] Confirm `BUILD SUCCESS`
- [ ] Check SonarQube UI → Security Hotspots → 0 TO_REVIEW
- [ ] Confirm Quality Gate still OK

---

## Notes

- **Possessive quantifiers** (`*+`, `++`, `?+`): Supported in Java 5+. Once consumed, never give
  back characters. Eliminates backtracking on the whitespace quantifiers in `IMPORT_PATTERN`.
- **CorsConfig input**: Config values from `@Value` — low DoS risk in practice, but fix is trivial.
- **DrlSanitizer input**: User-supplied DRL content — genuine attack surface; possessive fix is
  the correct security fix.
