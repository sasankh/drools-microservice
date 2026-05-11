package com.company.drools.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
          "Lookup",
          "Unsafe");

  private static final Set<String> BLOCKED_METHOD_CALLS =
      Set.of(
          "Runtime.getRuntime",
          "System.exit",
          "System.getenv",
          "System.setProperty",
          "System.getProperty",
          "System.setSecurityManager",
          "System.load",
          "System.loadLibrary",
          "System.gc",
          "System.runFinalization",
          "Class.forName",
          "Class.getMethod",
          "Class.getDeclaredMethod",
          "Class.getField",
          "Class.getDeclaredField",
          "Class.getConstructor",
          "Class.newInstance",
          ".getClass().getMethod",
          ".getClass().forName");

  private static final Set<String> BLOCKED_IMPORT_PREFIXES =
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

  public SanitizationResult sanitize(String ruleId, String drlContent) {
    List<String> violations = new ArrayList<>();

    checkImports(drlContent, violations);
    checkBlockedClassReferences(drlContent, violations);
    checkBlockedMethodCalls(drlContent, violations);
    checkEvalUsage(drlContent, violations);

    if (!violations.isEmpty()) {
      log.warn(
          "DRL sanitization failed for rule '{}': {} violation(s) — {}",
          ruleId,
          violations.size(),
          violations);
      return SanitizationResult.rejected(violations);
    }

    return SanitizationResult.accepted();
  }

  private void checkImports(String content, List<String> violations) {
    Matcher matcher = IMPORT_PATTERN.matcher(content);
    while (matcher.find()) {
      String importPath = matcher.group(2);
      boolean isStatic = matcher.group(1) != null;

      if (isStatic) {
        violations.add("Static imports are not allowed: 'import static " + importPath + "'");
      } else if (isBlockedImport(importPath)) {
        violations.add("Blocked import: '" + importPath + "'");
      } else if (!isAllowedImport(importPath)) {
        violations.add("Import not in allowlist: '" + importPath + "'");
      }
    }
  }

  private boolean isBlockedImport(String importPath) {
    for (String prefix : BLOCKED_IMPORT_PREFIXES) {
      if (importPath.startsWith(prefix)) {
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

  private void checkBlockedClassReferences(String content, List<String> violations) {
    for (String className : BLOCKED_CLASS_REFERENCES) {
      Pattern pattern =
          Pattern.compile("\\b" + Pattern.quote(className) + "\\b", Pattern.MULTILINE);
      if (pattern.matcher(content).find()) {
        violations.add("Blocked class reference: '" + className + "'");
      }
    }
  }

  private void checkBlockedMethodCalls(String content, List<String> violations) {
    for (String methodCall : BLOCKED_METHOD_CALLS) {
      if (content.contains(methodCall)) {
        violations.add("Blocked method call: '" + methodCall + "'");
      }
    }
  }

  private void checkEvalUsage(String content, List<String> violations) {
    if (EVAL_PATTERN.matcher(content).find()) {
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
