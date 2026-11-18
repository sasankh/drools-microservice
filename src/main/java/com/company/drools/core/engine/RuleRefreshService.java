package com.company.drools.core.engine;

import com.company.drools.core.model.Rule;
import com.company.drools.storage.StorageFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The single entry point for every way rules get (re)loaded: startup, admin endpoints, pub/sub
 * events from sibling instances, and the scheduled reconcile.
 *
 * <p><b>Ordering.</b> Each operation reads storage AND applies the result while holding one lock,
 * so the newest read is always the last one installed. (Before, the read happened outside the
 * engine's lock and two refreshes could install an older snapshot last.)
 *
 * <p><b>Safety.</b> A failed or incomplete storage read changes nothing. A complete read that is
 * empty while rules are loaded is refused unless {@code drools.refresh.allow-empty=true}, so a
 * misconfigured bucket or path can't wipe every rule (and fan the wipe out to siblings).
 */
@Service
public class RuleRefreshService {

  private static final Logger log = LoggerFactory.getLogger(RuleRefreshService.class);

  /**
   * One line per rule-changing action (who, what, outcome, rule IDs, digests). Never logs DRL
   * content, request payloads or keys. Route logger {@code AUDIT} separately if needed.
   */
  private static final Logger audit = LoggerFactory.getLogger("AUDIT");

  private static final int AUDIT_MAX_IDS = 50;

  /** Who triggered a refresh; used for logs, metrics and the load state. */
  public enum Source {
    STARTUP,
    ADMIN,
    PUBSUB,
    RECONCILE;

    String tag() {
      return name().toLowerCase();
    }
  }

  /** How a bulk refresh reads storage. */
  public enum ReadMode {
    /** Normal read (a cache layer may answer). */
    CACHED,
    /** Invalidate the cache first, then read — what an operator-triggered refresh wants. */
    INVALIDATE_THEN_READ,
    /** Read the source of truth directly, bypassing the cache (reconcile). */
    SOURCE
  }

  private final DroolsEngineService engine;
  private final StorageFactory storageFactory;
  private final RuleLoadState loadState;
  private final MeterRegistry meterRegistry;
  private final boolean allowEmpty;
  private final ReentrantLock lock = new ReentrantLock();

  public RuleRefreshService(
      DroolsEngineService engine,
      StorageFactory storageFactory,
      RuleLoadState loadState,
      MeterRegistry meterRegistry,
      @Value("${drools.refresh.allow-empty:false}") boolean allowEmpty) {
    this.engine = engine;
    this.storageFactory = storageFactory;
    this.loadState = loadState;
    this.meterRegistry = meterRegistry;
    this.allowEmpty = allowEmpty;
  }

  /** Read the full rule set and load it. Never throws; the outcome says what happened. */
  public BulkResult refreshAll(Source source, ReadMode mode) {
    return refreshAll(source, mode, null);
  }

  /**
   * As {@link #refreshAll(Source, ReadMode)}, recording {@code actor} (e.g. the admin caller's
   * address) in the audit line.
   */
  public BulkResult refreshAll(Source source, ReadMode mode, String actor) {
    BulkResult result = doRefreshAll(source, mode);
    auditBulk(source, actor, result);
    return result;
  }

  private BulkResult doRefreshAll(Source source, ReadMode mode) {
    lock.lock();
    try {
      List<Rule> rules;
      try {
        var storage = storageFactory.createRuleStorage();
        rules =
            switch (mode) {
              case CACHED -> storage.getAllRules();
              case INVALIDATE_THEN_READ -> {
                storage.refreshCache();
                yield storage.getAllRules();
              }
              case SOURCE -> storage.getAllRulesFromSource();
            };
      } catch (RuntimeException e) {
        String error = "Storage read failed; nothing changed: " + e.getMessage();
        log.error("{} refresh: {}", source.tag(), error, e);
        loadState.recordFullLoadFailure(source.tag(), error, source == Source.STARTUP);
        count(source, "storage_failed");
        return BulkResult.storageFailed(error);
      }

      if (rules.isEmpty() && engine.getLoadedRulesCount() > 0 && !allowEmpty) {
        String error =
            "Storage returned 0 rules while "
                + engine.getLoadedRulesCount()
                + " are loaded; refusing to remove them all (set drools.refresh.allow-empty=true"
                + " if this is intended)";
        log.error("{} refresh: {}", source.tag(), error);
        loadState.recordRefresh(source.tag(), "rejected_empty", error);
        count(source, "rejected_empty");
        return BulkResult.rejectedEmpty(error);
      }

      DroolsEngineService.LoadReport report = engine.loadRulesReport(rules);
      String result = report.allSucceeded() ? "ok" : "partial";
      loadState.recordFullLoad(source.tag(), result);
      count(source, result);
      if (source == Source.RECONCILE && report.changedAnything()) {
        meterRegistry
            .counter("drools.reconcile.changed")
            .increment((double) report.loaded().size() + report.removed().size());
        log.warn(
            "Reconcile found drift: {} rule(s) updated, {} removed (a refresh event was probably"
                + " missed)",
            report.loaded().size(),
            report.removed().size());
      }
      return BulkResult.loaded(report);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Re-read one rule from storage and apply it. A rule that is gone from storage but still loaded
   * is removed (it was deleted).
   */
  public SingleResult refreshOne(String ruleId, Source source, boolean invalidateCache) {
    return refreshOne(ruleId, source, invalidateCache, null);
  }

  /** As {@link #refreshOne(String, Source, boolean)}, recording {@code actor} in the audit line. */
  public SingleResult refreshOne(
      String ruleId, Source source, boolean invalidateCache, String actor) {
    // The lock is reentrant: hold it across the refresh AND the digest read, so the audit line
    // can't show a concurrent refresh's digest.
    lock.lock();
    try {
      return refreshOneAudited(ruleId, source, invalidateCache, actor);
    } finally {
      lock.unlock();
    }
  }

  private SingleResult refreshOneAudited(
      String ruleId, Source source, boolean invalidateCache, String actor) {
    SingleResult result = doRefreshOne(ruleId, source, invalidateCache);
    String newDigest = engine.getServingDigest(ruleId).orElse(null);
    audit.info(
        "AUDIT action=refresh_rule source={} actor={} rule_id={} result={} old_digest={}"
            + " new_digest={}{}",
        source.tag(),
        actor != null ? actor : "-",
        ruleId,
        result.status().name().toLowerCase(),
        com.company.drools.common.ContentDigest.shortForm(result.previousDigest()),
        com.company.drools.common.ContentDigest.shortForm(newDigest),
        // Never the compiler message here: Drools errors can quote DRL source. It is in the
        // regular application log instead.
        result.error() != null
            ? (result.status() == SingleStatus.STORAGE_FAILED
                ? " error=storage_read_failed"
                : " error=compile_failed")
            : "");
    return result;
  }

  private SingleResult doRefreshOne(String ruleId, Source source, boolean invalidateCache) {
    lock.lock();
    try {
      Optional<Rule> rule;
      try {
        var storage = storageFactory.createRuleStorage();
        if (invalidateCache) {
          storage.refreshRule(ruleId);
        }
        rule = storage.getRule(ruleId);
      } catch (RuntimeException e) {
        String error = "Storage read failed; nothing changed: " + e.getMessage();
        log.error("{} refresh of {}: {}", source.tag(), ruleId, error, e);
        loadState.recordRefresh(source.tag(), "storage_failed", error);
        count(source, "storage_failed");
        return new SingleResult(SingleStatus.STORAGE_FAILED, null, error);
      }

      if (rule.isEmpty()) {
        if (engine.hasRule(ruleId)) {
          engine.removeRule(ruleId);
          loadState.recordRefresh(source.tag(), "removed", null);
          count(source, "removed");
          log.info("{} refresh: {} no longer in storage, removed", source.tag(), ruleId);
          return new SingleResult(SingleStatus.REMOVED, null, null);
        }
        count(source, "not_found");
        return new SingleResult(SingleStatus.NOT_FOUND, null, null);
      }

      String previousDigest = engine.getServingDigest(ruleId).orElse(null);
      DroolsEngineService.InstallOutcome outcome = engine.replaceRule(rule.get());
      SingleStatus status =
          switch (outcome.status()) {
            case INSTALLED -> SingleStatus.INSTALLED;
            case UNCHANGED -> SingleStatus.UNCHANGED;
            case FAILED -> SingleStatus.FAILED;
          };
      loadState.recordRefresh(source.tag(), status.name().toLowerCase(), outcome.error());
      count(source, status.name().toLowerCase());
      return new SingleResult(status, previousDigest, outcome.error());
    } finally {
      lock.unlock();
    }
  }

  /** Remove one rule ID (a delete event from a sibling instance). */
  public void removeOne(String ruleId, Source source) {
    lock.lock();
    try {
      String digest = engine.getServingDigest(ruleId).orElse(null);
      engine.removeRule(ruleId);
      loadState.recordRefresh(source.tag(), "removed", null);
      count(source, "removed");
      audit.info(
          "AUDIT action=remove_rule source={} actor=- rule_id={} result=removed old_digest={}",
          source.tag(),
          ruleId,
          com.company.drools.common.ContentDigest.shortForm(digest));
    } finally {
      lock.unlock();
    }
  }

  private void auditBulk(Source source, String actor, BulkResult result) {
    String action = source == Source.RECONCILE ? "reconcile" : "refresh_all";
    if (result.status() != BulkStatus.LOADED) {
      audit.info(
          "AUDIT action={} source={} actor={} result={} error=\"{}\"",
          action,
          source.tag(),
          actor != null ? actor : "-",
          result.status().name().toLowerCase(),
          oneLine(result.error()));
      return;
    }
    DroolsEngineService.LoadReport r = result.report();
    if (source == Source.RECONCILE && !r.changedAnything() && r.allSucceeded()) {
      return; // quiet when a periodic reconcile finds nothing to do
    }
    audit.info(
        "AUDIT action={} source={} actor={} result={} loaded={} unchanged={} failed={} removed={}"
            + " loaded_ids={} failed_ids={} removed_ids={}",
        action,
        source.tag(),
        actor != null ? actor : "-",
        r.allSucceeded() ? "ok" : "partial",
        r.loaded().size(),
        r.unchanged().size(),
        r.failed().size(),
        r.removed().size(),
        capped(r.loaded()),
        capped(r.failed().keySet()),
        capped(r.removed()));
  }

  private static String capped(java.util.Collection<String> ids) {
    List<String> sorted = ids.stream().sorted().toList();
    return sorted.size() > AUDIT_MAX_IDS
        ? sorted.subList(0, AUDIT_MAX_IDS) + "+" + (sorted.size() - AUDIT_MAX_IDS)
        : sorted.toString();
  }

  private static String oneLine(String s) {
    if (s == null) {
      return "";
    }
    String flat = s.replaceAll("[\\r\\n\\t]+", " ").replace('"', '\'');
    return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
  }

  /**
   * Compare storage (read directly, bypassing any cache) with what is serving, and apply only the
   * differences. Unchanged rules are skipped by digest, so this is cheap when nothing changed.
   */
  public BulkResult reconcile() {
    return refreshAll(Source.RECONCILE, ReadMode.SOURCE);
  }

  private void count(Source source, String result) {
    meterRegistry
        .counter("drools.refresh.runs", "source", source.tag(), "result", result)
        .increment();
  }

  /** Outcome of a single-rule refresh. */
  public enum SingleStatus {
    INSTALLED,
    UNCHANGED,
    FAILED,
    REMOVED,
    NOT_FOUND,
    STORAGE_FAILED
  }

  /**
   * @param status what happened
   * @param previousDigest digest serving before this refresh (null if none)
   * @param error compile or storage error, if any
   */
  public record SingleResult(SingleStatus status, String previousDigest, String error) {}

  /** Outcome category of a bulk refresh. */
  public enum BulkStatus {
    /** Storage read fully; the report says per rule ID what happened. */
    LOADED,
    /** Storage could not be read completely; nothing changed. */
    STORAGE_FAILED,
    /** Storage was empty while rules are loaded; refused, nothing changed. */
    REJECTED_EMPTY
  }

  /**
   * @param status category
   * @param report per-ID outcome when {@link BulkStatus#LOADED}, else null
   * @param error why nothing changed, when not LOADED
   */
  public record BulkResult(BulkStatus status, DroolsEngineService.LoadReport report, String error) {

    static BulkResult loaded(DroolsEngineService.LoadReport report) {
      return new BulkResult(BulkStatus.LOADED, report, null);
    }

    static BulkResult storageFailed(String error) {
      return new BulkResult(BulkStatus.STORAGE_FAILED, null, error);
    }

    static BulkResult rejectedEmpty(String error) {
      return new BulkResult(BulkStatus.REJECTED_EMPTY, null, error);
    }

    /** Per-ID compile errors (empty unless LOADED with failures). */
    public Map<String, String> failures() {
      return report != null ? report.failed() : Map.of();
    }
  }
}
