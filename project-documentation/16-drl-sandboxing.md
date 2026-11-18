# 16 · DRL Sandboxing

| | |
|---|---|
| **Audience** | Rule authors (humans), AI rule-generation tools, security reviewers |
| **Purpose** | Definitive reference for what `DrlSanitizer` blocks and what's allowed. The doc AI rule-generation tools must consume to produce sandbox-passing rules. |
| **Last verified against** | [`DrlSanitizer.java`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java), [`DrlSanitizerTest.java`](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) on 2026-08-20 |
| **Related docs** | [17-rule-development.md](17-rule-development.md), [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md), [14-security-architecture.md](14-security-architecture.md) |

---

## TL;DR

Every `.drl` file is scanned by [`DrlSanitizer`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) **before** it reaches the Drools compiler. Rules that fail the scan are rejected with a violation message and never compiled.

The sandbox enforces **four checks**:
1. **Imports must be in the allowlist** (20 specific prefixes) — and not in the blocklist.
2. **Blocked class names cannot appear anywhere** in the rule text (Runtime, Thread, ClassLoader, etc.).
3. **Blocked method calls cannot appear** (System.exit, Class.forName, etc.).
4. **`eval()` is forbidden.** Use Drools pattern matching instead.

Static imports are also rejected unconditionally.

If you're an AI generating rules, follow the [sandbox-passing template](#sandbox-passing-template) at the bottom of this doc.

---

## Why the sandbox exists

DRL files are user-supplied content. Drools is a Java compiler at runtime — it can compile arbitrary Java in `then` blocks. Without a sandbox, a malicious or buggy rule could:
- Read environment variables, system properties (`System.getenv`, `System.getProperty`)
- Execute arbitrary processes (`Runtime.getRuntime().exec(...)`)
- Read or write files (`java.io.File`, `java.nio.*`)
- Make network calls (`java.net.*`)
- Load arbitrary classes by reflection (`Class.forName`, `ClassLoader`)
- Call native code (`System.load`, `System.loadLibrary`)
- Reset the security manager (`System.setSecurityManager`)
- Crash the JVM (`System.exit`)

The sandbox stops all of that **before compilation** — text-level scan, not runtime. This means:
- The DRL never reaches Drools' `KieBuilder` if it violates the sandbox.
- The error is reported at refresh time (`POST /admin/refresh-rules`), not at first-execution time.
- The blocked rule has zero opportunity to run.

---

## How sanitization fits into the rule lifecycle

```
.drl content (from S3 / local / memory)
       │
       ▼
   DrlSanitizer.sanitize(ruleId, drlContent)
       │
       ├── ANY violation? → reject; log; rule excluded from KieContainer
       │
       └── all clear → proceed to RuleCompiler → KieBuilder.buildAll()
```

Implementation: [`RuleCompiler`](../src/main/java/com/company/drools/core/engine/RuleCompiler.java) calls `DrlSanitizer.sanitize()` on every rule before submitting to Drools. A failed rule does not abort the whole refresh — it's reported in `POST /admin/refresh-rules` response under `errors[]` and the other rules continue.

See [10-api-reference.md](10-api-reference.md) `POST /admin/refresh-rules` for the response shape.

---

## Check 1 — Imports

[`DrlSanitizer.java:125-139`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L125-L139)

The import scanner uses regex `^\h*+import\h++(static\h++)?([\w.]+\*?)\h*+;?+\h*+$` (`\h` = horizontal whitespace, possessive quantifiers `*+`/`++`/`?+` prevent ReDoS backtracking on malformed DRL — S5852 fix) and inspects every match.

For each import the sanitizer applies three rules:

1. **No static imports.** Any `import static foo.Bar.baz;` is rejected outright.
2. **Not in the block list** (next subsection).
3. **In the allowlist** (subsection after that).

A blocked-prefix import is rejected before allowlist is checked. An import that's neither blocked nor in the allowlist is still rejected (allowlist policy — default-deny).

### Allowed imports (the allowlist)

Verbatim from [`DrlSanitizer.java:17-38`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L17-L38):

| Allowed prefix | What it lets you import |
|---|---|
| `java.util.*` | `Map`, `List`, `ArrayList`, `HashMap`, `Set`, `Date`, `Optional`, etc. |
| `java.math.*` | `BigDecimal`, `BigInteger`, `RoundingMode` |
| `java.time.*` | `LocalDate`, `LocalDateTime`, `Instant`, `Duration`, `ZoneId` |
| `java.lang.Math` | `Math.max`, `Math.min`, `Math.floor`, etc. |
| `java.lang.String` | (already implicit in Drools) |
| `java.lang.Number` | base numeric class |
| `java.lang.Integer` | |
| `java.lang.Long` | |
| `java.lang.Double` | |
| `java.lang.Float` | |
| `java.lang.Boolean` | |
| `java.lang.Byte` | |
| `java.lang.Short` | |
| `java.lang.Character` | |
| `java.lang.Comparable` | |
| `java.lang.Object` | |
| `java.lang.Enum` | |
| `java.text.DecimalFormat` | number formatting |
| `java.text.NumberFormat` | locale-aware formatting |
| `java.text.SimpleDateFormat` | date formatting |

> **Project package not in this list.** `com.company.*` is **not** in the allowlist. Sample rules don't import any company classes — they manipulate the input `Map` directly.

### Blocked imports

Verbatim from [`DrlSanitizer.java:77-97`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L77-L97):

| Blocked prefix | Why blocked |
|---|---|
| `java.io.` | File I/O |
| `java.nio.` | NIO file I/O |
| `java.net.` | Network sockets, URLs |
| `java.lang.reflect.` | Reflection-based code execution |
| `java.lang.invoke.` | MethodHandles, dynamic invocation |
| `java.lang.Process` | Subprocess management |
| `java.lang.Runtime` | `Runtime.exec()` etc. |
| `java.lang.ClassLoader` | Loading arbitrary classes |
| `java.lang.Thread` | Thread management |
| `java.lang.SecurityManager` | Tampering with security policy |
| `javax.script.` | Script engine (JS, Groovy, etc.) |
| `javax.naming.` | JNDI lookups (Log4Shell-style) |
| `javax.management.` | JMX |
| `javax.net.` | Network sockets |
| `sun.` | Internal Sun/OpenJDK APIs |
| `com.sun.` | Internal Sun APIs |
| `jdk.` | Internal JDK APIs |
| `org.kie.api.internal` | Drools internal escape hatches |
| `org.drools.core` | Drools internals (could bypass sandbox) |

### What happens when an import is in neither list

Default-deny. Example: `import com.example.MyHelper;` is rejected:
```
Import not in allowlist: 'com.example.MyHelper'
```

This is intentional — even seemingly innocent custom imports are rejected because they could expose dangerous internal APIs. To use a custom helper class, **you'd need to add its prefix to the allowlist in `DrlSanitizer.java`**, recompile, and redeploy. That's a code change — not a config change. By design.

### Common allowlist gotchas

| You wrote | What happens | Why |
|---|---|---|
| `import java.util.Map;` | ✅ allowed | `java.util.` prefix |
| `import java.util.HashMap;` | ✅ allowed | same prefix |
| `import java.util.*;` | ✅ allowed | wildcard ok |
| `import java.io.File;` | ❌ rejected | `java.io.` is blocked |
| `import java.lang.Runtime;` | ❌ rejected | `java.lang.Runtime` is blocked |
| `import java.lang.System;` | ❌ rejected | not in allowlist (default-deny) — but System is auto-imported in DRL anyway |
| `import static java.lang.Math.PI;` | ❌ rejected | static imports always rejected |
| `import com.company.MyClass;` | ❌ rejected | not in allowlist |
| `import java.time.LocalDate;` | ✅ allowed | `java.time.` prefix |

---

## Check 2 — Blocked class references

[`DrlSanitizer.java:159-167`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L159-L167)

The sanitizer scans the text for word-boundary matches against this list, regardless of import status. So even if you don't import `Runtime`, writing `Runtime.getRuntime()` anywhere in the rule body is rejected.

Verbatim from [`DrlSanitizer.java:40-53`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L40-L53):

| Blocked class name | What it does (and why blocked) |
|---|---|
| `Runtime` | `Runtime.getRuntime().exec(...)` runs subprocesses |
| `ProcessBuilder` | Same as Runtime, fancier API |
| `ClassLoader` | Loads arbitrary classes |
| `URLClassLoader` | Loads classes from URLs |
| `Thread` | Spawns threads |
| `ThreadGroup` | Manages threads |
| `SecurityManager` | Tampers with security policy |
| `ScriptEngine` | Executes JS, Groovy, etc. |
| `ScriptEngineManager` | Same family |
| `MethodHandle` | Dynamic invocation |
| `Lookup` | `MethodHandles.Lookup` — reflection-equivalent |
| `Unsafe` | Direct memory access, JVM internals |

> **Detection is text-based, not semantic.** A comment containing `Runtime` would be rejected. So would a string literal like `"Runtime is not blocked"`. Choose variable and string content carefully.

### What if you have a legitimate variable named "Thread"?

You can't. Even local variables clash with the blocklist. Pick a different name (`Threadpool`, `MyThread`, `WorkerThread` are all... wait, `WorkerThread` contains `Thread`. Use something like `WorkerExecutor` or `Pool`.)

Word-boundary regex is `\bClassName\b`, so `MyThreadPool` matches `Thread`. Substring-style matches.

### What about Java identifiers that share a name?

If you import `org.example.Thread` (different package, same simple name) — first the *import* is rejected because `org.example.*` isn't in the allowlist. Even if it were, the simple name `Thread` would be flagged later.

---

## Check 3 — Blocked method calls

[`DrlSanitizer.java:169-175`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L169-L175)

The sanitizer does a literal substring scan for each entry. This is less precise than the class check (no word boundary), so e.g., `Class.forName` matches `MyClass.forName` if you somehow had that.

Verbatim from [`DrlSanitizer.java:55-75`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L55-L75):

| Blocked method call | What it does |
|---|---|
| `Runtime.getRuntime` | Entry to `Runtime.exec(...)` |
| `System.exit` | Crashes the JVM |
| `System.getenv` | Reads env vars (could leak secrets) |
| `System.setProperty` | Modifies system properties |
| `System.getProperty` | Reads system properties |
| `System.setSecurityManager` | Tampers with policy |
| `System.load` | Loads native library (path-based) |
| `System.loadLibrary` | Loads native library (name-based) |
| `System.gc` | Forces GC (DoS-y) |
| `System.runFinalization` | Forces finalizers |
| `Class.forName` | Loads arbitrary class by name |
| `Class.getMethod` | Reflection on public methods |
| `Class.getDeclaredMethod` | Reflection on all methods |
| `Class.getField` | Reflection on public fields |
| `Class.getDeclaredField` | Reflection on all fields |
| `Class.getConstructor` | Reflection on constructors |
| `Class.newInstance` | Instantiates by reflection |
| `.getClass().getMethod` | Same as `Class.getMethod` via instance |
| `.getClass().forName` | Same as `Class.forName` via instance |

### Implications

- **`System.out.println` is allowed.** It's not in the blocklist. You can `println` from `then` blocks for debug logging. **Don't** in production rules — it goes to stdout, which is captured but messy.
- **`System.currentTimeMillis()` is allowed.** Use it freely.
- **Math operations are allowed.** `Math.max`, `Math.min`, `Math.round`, etc.

---

## Check 4 — `eval()` is forbidden

[`DrlSanitizer.java:177-181`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L177-L181)

The pattern `\beval\s*\(` matches any function call to `eval`. This is rejected unconditionally with the message:
```
eval() is not allowed in DRL rules
```

### Why ban eval()?

Drools supports an `eval(...)` clause in `when` blocks that lets you embed arbitrary Java boolean expressions. While useful, `eval()` is a known DRL "escape hatch":
- Bypasses the Drools pattern-matching engine (RETE network), forcing per-rule re-evaluation
- Can hide arbitrary Java code execution from rule reviewers
- Performance footgun (RETE optimizations don't apply to `eval` predicates)

We ban it. Period. **Use Drools pattern matching instead.**

### How to replace eval() in `when`

| Instead of | Use |
|---|---|
| `eval($data.get("amount") != null)` | `$data : Map(this["amount"] != null)` |
| `eval(((Number)$data.get("amount")).doubleValue() > 50)` | `$data : Map(((Number)this["amount"]).doubleValue() > 50)` |
| `eval("VIP".equals($data.get("customerType")))` | `$data : Map(this["customerType"] == "VIP")` |

Verified by the 17 sample rules in [`sample-rules/`](../sample-rules/) — none use `eval()`.

### What about `eval()` in other contexts?

The pattern `\beval\s*\(` matches:
- `eval(...)` — rejected
- `eval (...)` — rejected (whitespace ok)
- `myeval(...)` — NOT rejected (`\b` word boundary)
- `eval` not followed by `(` — NOT rejected (so a variable named `evaluator` is fine)

The detection is purposely tight — only the `eval(` function call form.

---

## Live example: rejection

If you upload a rule that imports `java.io.File`:

```drools
package com.company.rules.bad

import java.io.File   // ← BLOCKED

rule "Tries to read files"
when
  $data : Map()
then
  // body
end
```

then `POST /admin/refresh-rules`:

```json
{
  "status": "completed_with_errors",
  "rules_loaded": 9,
  "rules_failed": 1,
  "errors": [
    {
      "rule_id": "bad",
      "error": "Blocked import: 'java.io.File'"
    }
  ]
}
```

The other 9 rules load normally.

---

## Sandbox-passing template

The canonical pattern for project rules. AI rule-generation tools should produce something matching this shape.

```drools
package com.company.rules.{domain}.{category}

import java.util.Map
// (Add other allowlist imports as needed: java.math.BigDecimal, java.time.LocalDate, etc.)

rule "Descriptive Rule Name"
    salience 100        // Higher = higher priority. Optional but recommended.
    no-loop true        // Prevent re-firing on own data modifications.

when
    $data : Map(
        this["amount"] != null,
        this["customerType"] != null
    )

then
    // Read fields with null-safe Number casting
    Number amountNum = (Number) $data.get("amount");
    double amount = amountNum != null ? amountNum.doubleValue() : 0.0;
    String customerType = (String) $data.get("customerType");

    // Business logic — plain Java, no eval(), no reflection
    if ("VIP".equals(customerType) && amount >= 100.0) {
        double discount = amount * 0.20;
        $data.put("discount", discount);
        $data.put("amount", amount - discount);
        $data.put("discountPercent", 20);
        $data.put("discountReason", "VIP customer discount");
    }
end
```

This rule:
- Imports only allowlist packages
- Uses no `eval()`
- Uses Map pattern matching for conditions (not eval())
- Uses plain Java in `then` block
- Has null-safe casts via `Number`
- Modifies `$data` (the input Map) — that's how rules return results

---

## How to test a rule against the sandbox locally

Three ways:

### Option 1: Spin up the dev stack and refresh

```bash
docker compose up -d
# Edit/place your .drl in sample-rules/
./init-localstack.sh   # re-syncs sample-rules/ to LocalStack S3
curl -X POST http://localhost:8080/admin/refresh-rules \
  -H "X-Admin-API-Key: ${ADMIN_API_KEY:-}" | jq
```

If your rule is in `errors[]`, read the violation message.

### Option 2: Run the sanitizer test harness

```bash
mvn test -Dtest=DrlSanitizerTest
```

The test class has 8 test methods (4 `@Test` + 4 `@ParameterizedTest`) organized across 6 `@Nested` classes — `AllowedRules`, `BlockedImports`, `BlockedClassReferences`, `BlockedMethodCalls`, `EvalBlocking`, and `MultipleViolations` — with the parameterized methods driven by `@MethodSource` streams that cover every blocked import, class, and method. Looking at the test methods shows you exactly what's rejected. See [`DrlSanitizerTest.java`](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java).

### Option 3: Write a tiny test in your rule's own test file

```java
@Test
void myRuleShouldPassSandbox() {
  String drl = readResource("rules/pricing/discount/my-rule.drl");
  DrlSanitizer.SanitizationResult result = drlSanitizer.sanitize("my-rule", drl);

  assertThat(result.isAccepted())
      .as("violations: %s", result.getViolations())
      .isTrue();
}
```

This is the highest-confidence local check.

---

## What the sandbox does NOT do

> **⚠️ It is NOT a sound security boundary — the primary open bypass (finding B1, tracked-open, deferred).** The blocked-class check (Check 2) matches **simple class names** against a finite 12-name blocklist. But **a fully-qualified class name needs no `import`**, and referencing a class by its fully-qualified name (e.g. `java.io.File`, or any dangerous class whose *simple* name is not one of the 12 blocked names) sidesteps both the import allowlist (Check 1 only inspects `import` lines) and the class blocklist. Since any class outside the finite blocklist is reachable this way, the text scan cannot enclose the code-execution surface. `SecurityManager` — which could have backstopped this at runtime — was removed by JEP 486, so no in-JVM permission sandbox is available. This is documented and accepted in [`SECURITY.md`](../SECURITY.md); treat the `DrlSanitizer` scan as defense-in-depth that raises the bar against casual/accidental misuse, **not** as a real isolation boundary. Sound isolation would require compiling/executing rules in a separate constrained process or JVM. The operational control that actually matters is trusting who can publish DRL to the rule bucket.

- **It does NOT validate Drools syntax.** A rule can pass sanitization and still fail Drools compilation. (Example: `rule "x" then end` is sandbox-clean but malformed Drools.)
- **It does NOT prevent infinite loops at runtime.** Use `no-loop true` and the `maxRuleFirings = 10000` cap (in [`RuleExecutor`](../src/main/java/com/company/drools/core/engine/RuleExecutor.java)) for that.
- **It does NOT analyze data flow.** A rule that does `$data.get("password")` and writes it elsewhere is sandbox-clean (the sandbox doesn't know `password` is sensitive).
- **It does NOT parse the DRL semantically.** It's a regex-based text scan. Tricks like Unicode escapes (`Runtime`) might evade detection — the surface should be widened if exploited.
- **It does NOT scan dependencies.** If you somehow imported a class that's allowed but uses `Runtime` internally, the sandbox doesn't follow the call chain. Allowed classes are vetted by humans (the allowlist is small).

---

## Extending the sandbox

If you have a legitimate need for a new package or method, the change is in code, not config:

1. Edit [`DrlSanitizer.java`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java).
2. Add the prefix to `ALLOWED_IMPORT_PREFIXES`.
3. Add a test case to [`DrlSanitizerTest.java`](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) demonstrating the new addition is acceptable.
4. PR for security review.
5. Recompile, redeploy.

This is intentional friction. The allowlist is small because every entry is a potential attack surface.

---

## Verification

To prove the sandbox is what this doc says it is, run:

```bash
mvn test -Dtest=DrlSanitizerTest 2>&1 | grep -E 'Tests run|FAIL'
```

Expected: 8 test methods (across 6 `@Nested` classes) expanding to their parameterized invocations, all pass.

Or read [`DrlSanitizerTest.java`](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) directly — every claim in this doc has a corresponding test case.
