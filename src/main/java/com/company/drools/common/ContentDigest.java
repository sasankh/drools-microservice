package com.company.drools.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 fingerprint of DRL source text, used to detect changed rules across refreshes. */
public final class ContentDigest {

  private ContentDigest() {}

  /** Lowercase hex SHA-256 of {@code content} (UTF-8). */
  public static String sha256(String content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** First 12 hex characters, for logs and audit lines. */
  public static String shortForm(String digest) {
    if (digest == null) {
      return null;
    }
    return digest.length() > 12 ? digest.substring(0, 12) : digest;
  }
}
