package com.company.drools.common;

import java.util.regex.Pattern;

/**
 * Shared rule-ID safety checks. A rule ID is transformed into a storage path ({@code a.b.c} →
 * {@code a/b/c.drl}), so it must be validated as path-safe BEFORE any transformation — at every
 * entry point, including ones that bypass HTTP request validation (e.g. the pub/sub refresh path).
 */
public final class RuleIds {

  // Alphanumeric plus dot, dash, underscore — matches RuleIdValidator's HTTP-layer pattern.
  private static final Pattern SAFE = Pattern.compile("^[a-zA-Z0-9._-]+$");

  private RuleIds() {}

  /** True if {@code ruleId} is non-blank, well-formed, and free of path-traversal sequences. */
  public static boolean isPathSafe(String ruleId) {
    if (ruleId == null || ruleId.isBlank()) {
      return false;
    }
    String trimmed = ruleId.trim();
    if (!SAFE.matcher(trimmed).matches()) {
      return false;
    }
    return !(trimmed.contains("..") || trimmed.startsWith("/") || trimmed.contains("\\"));
  }

  /**
   * Throws {@link IllegalArgumentException} if {@code ruleId} is not {@link #isPathSafe path-safe}.
   */
  public static void requirePathSafe(String ruleId) {
    if (!isPathSafe(ruleId)) {
      throw new IllegalArgumentException(
          "Invalid rule ID: contains unsafe characters or path traversal");
    }
  }
}
