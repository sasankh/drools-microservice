package com.company.drools.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Policy check for DRL from TRUSTED, reviewed authors. It rejects obviously dangerous constructs
 * (I/O, reflection, process and thread control, dynamic class loading, {@code eval}) before a rule
 * is compiled.
 *
 * <p><b>This is NOT a security boundary.</b> DRL consequences are Java code running inside this
 * process; a text-based check cannot prove arbitrary code safe, and Java 25 has no SecurityManager.
 * Treat write access to the rule store (and to the admin refresh endpoints / the Redis refresh
 * channel) as equivalent to code execution. Untrusted (customer- or AI-supplied) DRL needs a
 * separate low-privilege worker process first — see {@code SECURITY.md} (threat model, B1).
 *
 * <p>Checks run on a normalized copy of the source: comments removed, string-literal contents
 * blanked, and whitespace around {@code .} collapsed, so {@code import java.io.File // x}, {@code
 * System .exit} or a call split across lines can't slip past the patterns.
 */
@Component
public class DrlSanitizer {

  private static final Logger log = LoggerFactory.getLogger(DrlSanitizer.class);

  private static final Set<String> ALLOWED_IMPORT_PREFIXES =
      Set.of(
          "java.util.",
          "java.math.",
          "java.time.",
          "java.lang.Math",
          "java.lang.String",
          "java.lang.Number",
          "java.lang.Integer",
          "java.lang.Long",
          "java.lang.Double",
          "java.lang.Float",
          "java.lang.Boolean",
          "java.lang.Byte",
          "java.lang.Short",
          "java.lang.Character",
          "java.lang.Comparable",
          "java.lang.Object",
          "java.lang.Enum",
          "java.text.DecimalFormat",
          "java.text.NumberFormat",
          "java.text.SimpleDateFormat");

  private static final Set<String> BLOCKED_CLASS_REFERENCES =
      Set.of(
          "Runtime",
          "ProcessBuilder",
          "ClassLoader",
          "URLClassLoader",
          "Thread",
          "ThreadGroup",
          "SecurityManager",
          "ScriptEngine",
          "ScriptEngineManager",
          "MethodHandle",
          "MethodHandles",
          "Lookup",
          "Unsafe",
          "ServiceLoader");

  private static final Set<String> BLOCKED_METHOD_CALLS =
      Set.of(
          "Runtime.getRuntime",
          "System.exit",
          "System.getenv",
          "System.setProperty",
          "System.getProperty",
          "System.getProperties",
          "System.setSecurityManager",
          "System.load",
          "System.loadLibrary",
          "System.gc",
          "System.runFinalization",
          "System.setIn",
          "System.setOut",
          "System.setErr",
          "Class.forName",
          "Class.getMethod",
          "Class.getDeclaredMethod",
          "Class.getField",
          "Class.getDeclaredField",
          "Class.getConstructor",
          "Class.newInstance",
          ".getClass().getMethod",
          ".getClass().forName",
          ".getClass().getDeclared",
          ".getClassLoader");

  /**
   * Package/class prefixes blocked both as imports and as fully qualified names anywhere in the
   * rule (a fully qualified name needs no import, so checking imports alone is not enough).
   */
  private static final Set<String> BLOCKED_PACKAGE_PREFIXES =
      Set.of(
          "java.io.",
          "java.nio.",
          "java.net.",
          "java.lang.reflect.",
          "java.lang.invoke.",
          "java.lang.Process",
          "java.lang.Runtime",
          "java.lang.ClassLoader",
          "java.lang.Thread",
          "java.lang.SecurityManager",
          "java.lang.System",
          "java.lang.ref.",
          "java.util.concurrent.",
          "java.util.ServiceLoader",
          "java.util.logging.",
          "java.util.prefs.",
          "java.util.jar.",
          "java.util.zip.",
          "javax.script.",
          "javax.naming.",
          "javax.management.",
          "javax.net.",
          "sun.",
          "com.sun.",
          "jdk.",
          "org.kie.api.internal",
          "org.drools.core");

  private static final Pattern IMPORT_PATTERN =
      Pattern.compile(
          "^\\h*+import\\h++(static\\h++)?([\\w.]+\\*?)\\h*+;?+\\h*+$", Pattern.MULTILINE);

  private static final Pattern EVAL_PATTERN = Pattern.compile("\\beval\\s*\\(", Pattern.MULTILINE);

  private static final Pattern WHITESPACE_AROUND_DOT = Pattern.compile("\\s*+\\.\\s*+");

  /**
   * Java translates {@code \\uXXXX} escapes BEFORE tokenizing, anywhere in the source — so an
   * escape can hide a quote or a newline and smuggle code out of a string or comment past every
   * text check. Rejected outright (checked on the raw text, before normalizing).
   */
  private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u+[0-9a-fA-F]{4}");

  public SanitizationResult sanitize(String ruleId, String drlContent) {
    List<String> violations = new ArrayList<>();
    if (UNICODE_ESCAPE.matcher(drlContent).find()) {
      violations.add("Unicode escapes (\\uXXXX) are not allowed in DRL");
    }
    String code = normalize(drlContent);

    checkImports(code, violations);
    checkBlockedPackages(code, violations);
    checkBlockedClassReferences(code, violations);
    checkBlockedMethodCalls(code, violations);
    checkEvalUsage(code, violations);

    if (!violations.isEmpty()) {
      List<String> unique = List.copyOf(new LinkedHashSet<>(violations));
      log.warn(
          "DRL policy check failed for rule '{}': {} violation(s) — {}",
          ruleId,
          unique.size(),
          unique);
      return SanitizationResult.rejected(unique);
    }

    return SanitizationResult.accepted();
  }

  /**
   * Remove {@code //} and {@code /* *}{@code /} comments, blank the contents of string literals
   * (keeping the quotes), and collapse whitespace around {@code .}. Newlines are kept so
   * line-anchored patterns (imports) still work.
   */
  static String normalize(String content) {
    StringBuilder out = new StringBuilder(content.length());
    int i = 0;
    int n = content.length();
    while (i < n) {
      char c = content.charAt(i);
      char next = i + 1 < n ? content.charAt(i + 1) : '\0';
      if (c == '/' && next == '/') {
        while (i < n && content.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && next == '*') {
        i += 2;
        while (i < n && !(content.charAt(i) == '*' && i + 1 < n && content.charAt(i + 1) == '/')) {
          if (content.charAt(i) == '\n') {
            out.append('\n');
          }
          i++;
        }
        i += 2;
        out.append(' ');
      } else if (c == '"' || c == '\'') {
        char quote = c;
        out.append(quote);
        i++;
        while (i < n && content.charAt(i) != quote && content.charAt(i) != '\n') {
          i += content.charAt(i) == '\\' ? 2 : 1;
        }
        out.append(quote);
        // Consume the closing quote only; an unterminated literal must not swallow the newline
        // (that would merge the next line, e.g. an import, into this one).
        if (i < n && content.charAt(i) == quote) {
          i++;
        }
      } else {
        out.append(c);
        i++;
      }
    }
    return WHITESPACE_AROUND_DOT.matcher(out).replaceAll(".");
  }

  private void checkImports(String code, List<String> violations) {
    Matcher matcher = IMPORT_PATTERN.matcher(code);
    while (matcher.find()) {
      String importPath = matcher.group(2);
      boolean isStatic = matcher.group(1) != null;

      if (isStatic) {
        violations.add("Static imports are not allowed: 'import static " + importPath + "'");
      } else if (isBlocked(importPath)) {
        violations.add("Blocked import: '" + importPath + "'");
      } else if (!isAllowedImport(importPath)) {
        violations.add("Import not in allowlist: '" + importPath + "'");
      }
    }
  }

  private static boolean isBlocked(String name) {
    for (String prefix : BLOCKED_PACKAGE_PREFIXES) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private boolean isAllowedImport(String importPath) {
    for (String prefix : ALLOWED_IMPORT_PREFIXES) {
      if (importPath.startsWith(prefix) || importPath.equals(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** Fully qualified dangerous names anywhere outside import lines (no import is needed). */
  private void checkBlockedPackages(String code, List<String> violations) {
    String body = IMPORT_PATTERN.matcher(code).replaceAll("");
    for (String prefix : BLOCKED_PACKAGE_PREFIXES) {
      Pattern p = Pattern.compile("(?<![\\w.])" + Pattern.quote(prefix));
      if (p.matcher(body).find()) {
        violations.add("Blocked fully qualified reference: '" + prefix + "'");
      }
    }
  }

  private void checkBlockedClassReferences(String code, List<String> violations) {
    for (String className : BLOCKED_CLASS_REFERENCES) {
      Pattern pattern =
          Pattern.compile("\\b" + Pattern.quote(className) + "\\b", Pattern.MULTILINE);
      if (pattern.matcher(code).find()) {
        violations.add("Blocked class reference: '" + className + "'");
      }
    }
  }

  private void checkBlockedMethodCalls(String code, List<String> violations) {
    for (String methodCall : BLOCKED_METHOD_CALLS) {
      if (code.contains(methodCall)) {
        violations.add("Blocked method call: '" + methodCall + "'");
      }
    }
  }

  private void checkEvalUsage(String code, List<String> violations) {
    if (EVAL_PATTERN.matcher(code).find()) {
      violations.add("eval() is not allowed in DRL rules");
    }
  }

  public static class SanitizationResult {
    private final boolean accepted;
    private final List<String> violations;

    private SanitizationResult(boolean accepted, List<String> violations) {
      this.accepted = accepted;
      this.violations = violations;
    }

    public static SanitizationResult accepted() {
      return new SanitizationResult(true, List.of());
    }

    public static SanitizationResult rejected(List<String> violations) {
      return new SanitizationResult(false, List.copyOf(violations));
    }

    public boolean isAccepted() {
      return accepted;
    }

    public List<String> getViolations() {
      return violations;
    }
  }
}
