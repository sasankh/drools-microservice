# Plan: Sonar Security Hotspots — 2 ReDoS Risks (S5852)

## Context

Two security hotspots flagged as **"TO_REVIEW"** under rule `java:S5852` (Denial of Service via slow
regular expressions). Both are in production code. Neither processes direct user input at the regex
level, but fixing is correct practice and resolves the hotspots.

Branch: `upgrade-java-sonar`. All 597 tests pass. Quality Gate OK.

---

## Hotspot 1 — `CorsConfig.java:86`

**Rule:** S5852 — Slow regular expression (polynomial backtracking)
**File:** `src/main/java/com/company/drools/config/CorsConfig.java`

### Current Code

```java
private List<String> parseCommaSeparatedValues(String value) {
    if (value == null || value.trim().isEmpty()) {
        return List.of();
    }
    return Arrays.asList(value.split("\\s*,\\s*"));
}
```

### Why It's Flagged

`\\s*` on both sides of `,` in `String.split()` uses Java's regex engine. On a string with many
spaces but no comma, the engine tries many ways to match `\s*` before giving up — polynomial
runtime O(n²) or worse.

The input comes from Spring `@Value` (config files/environment variables), so real-world DoS risk
is negligible. But the fix is trivial and makes the code cleaner.

### Fix

Replace the regex-based split with a plain `,` split plus manual trim. No regex quantifiers:

```java
private List<String> parseCommaSeparatedValues(String value) {
    if (value == null || value.trim().isEmpty()) {
        return List.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
}
```

**Behavioral differences from original:**
- Original: `"a , b"` → `["a", "b"]` (trim baked into regex)
- New: `"a , b"` → `["a", "b"]` (trim via `.map(String::trim)`) — **same result**
- New also filters empty strings from `"a,,b"` → `["a", "b"]` — slight improvement

**Import changes:** `import java.util.Arrays` is still needed for `Arrays.stream()`. No change needed.

---

## Hotspot 2 — `DrlSanitizer.java` — `IMPORT_PATTERN`

**Rule:** S5852 — Slow regular expression (polynomial backtracking)
**File:** `src/main/java/com/company/drools/core/engine/DrlSanitizer.java`

### Current Code

```java
private static final Pattern IMPORT_PATTERN =
    Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+\\*?)\\s*;?\\s*$", Pattern.MULTILINE);
```

### Why It's Flagged

The pattern has multiple adjacent whitespace quantifiers:
- `^\\s*import` — `\s*` then literal
- `\\s+(static\\s+)?` — `\s+` then optional group with `\s+`
- `\\s*;?\\s*$` — two `\s*` around an optional `;`

On a malformed DRL line like `import    ` (many spaces, no semicolon, no class name), the regex
engine tries many combinations for `\s*;?\s*$`. This creates O(n²) behaviour — classic ReDoS.

The input IS potentially adversarial: DRL content is user-supplied rule definitions. This is a
genuine (if low-probability) attack surface.

### Fix

Convert all `\\s*` and `\\s+` to **possessive quantifiers** (`\\s*+` and `\\s++`). Java's
`java.util.regex` has supported possessive quantifiers since Java 5. A possessive quantifier never
gives back matched characters, eliminating backtracking entirely.

```java
private static final Pattern IMPORT_PATTERN =
    Pattern.compile("^\\s*+import\\s++(static\\s++)?([\\w.]+\\*?)\\s*+;?+\\s*+$", Pattern.MULTILINE);
```

**Changes made:**
| Original | Possessive | Meaning |
|---|---|---|
| `\\s*` | `\\s*+` | Zero or more whitespace, no backtrack |
| `\\s+` | `\\s++` | One or more whitespace, no backtrack |
| `;?` | `;?+` | Optional semicolon, no backtrack |

**Behavioral differences:**
- For all **valid** import lines: identical results (possessive quantifiers only differ when
  backtracking would occur)
- For **malformed** lines that would trigger backtracking: the possessive version fails faster
  (correct — malformed imports should be rejected)
- Security improvement: pathological inputs no longer cause exponential processing time

### Why Not Other Approaches?

1. **Atomic groups** `(?>...)` — equivalent to possessive but more verbose; possessive is cleaner
2. **Bounded quantifiers** `\\s{0,100}` — arbitrary limit; possessive is exact
3. **Full rewrite** — unnecessary; possessive conversion is minimal and precise

---

## Test Impact

The `DrlSanitizerTest.java` parameterized tests cover:
- Valid imports (java.util, java.math, java.time, etc.)
- Blocked imports (java.io.File, java.net.URL, etc.)
- No-import rules

These will continue to pass since possessive quantifiers match the same valid inputs.

---

## Execution Order

```
Step 1: Fix CorsConfig.java
  - Replace value.split("\\s*,\\s*") with stream-based approach

Step 2: Fix DrlSanitizer.java
  - Convert IMPORT_PATTERN quantifiers to possessive

Step 3: Verify tests
  - mvn clean test -Dtest='!S3StorageIntegrationTest' → 597 pass

Step 4: SpotBugs
  - mvn compile spotbugs:check → BUILD SUCCESS

Step 5: Commit
  - git commit -m "fix(security): resolve S5852 ReDoS hotspots — CorsConfig + DrlSanitizer"

Step 6: Sonar scan
  - mvn clean verify sonar:sonar → scan completes
  - Verify hotspots auto-resolved or manually mark FIXED/SAFE
```

---

## Critical Files

| File | Change |
|---|---|
| `src/main/java/com/company/drools/config/CorsConfig.java` | Replace regex split at line 86 |
| `src/main/java/com/company/drools/core/engine/DrlSanitizer.java` | Possessive quantifiers in `IMPORT_PATTERN` |
