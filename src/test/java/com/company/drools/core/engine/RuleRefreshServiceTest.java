package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.drools.api.exception.RuleStorageException;
import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.engine.RuleRefreshService.BulkResult;
import com.company.drools.core.engine.RuleRefreshService.BulkStatus;
import com.company.drools.core.engine.RuleRefreshService.ReadMode;
import com.company.drools.core.engine.RuleRefreshService.SingleResult;
import com.company.drools.core.engine.RuleRefreshService.SingleStatus;
import com.company.drools.core.engine.RuleRefreshService.Source;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.StorageFactory;
import com.company.drools.testutil.RuleTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link RuleRefreshService}: a real {@link DroolsEngineService} compiling real DRL
 * (via {@link RuleCompiler}), with storage mocked so each read outcome can be forced.
 */
@DisplayName("RuleRefreshService")
class RuleRefreshServiceTest {

  private SimpleMeterRegistry meterRegistry;
  private CompiledRulesetRegistry registry;
  private DroolsEngineService engine;
  private RuleStorage storage;
  private StorageFactory storageFactory;
  private RuleLoadState loadState;
  private RuleRefreshService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    registry = new CompiledRulesetRegistry();
    TimeoutConfig timeoutConfig = mock(TimeoutConfig.class);
    when(timeoutConfig.getRuleExecutionTimeoutSeconds()).thenReturn(30);
    engine =
        new DroolsEngineService(
            new RuleCompiler(new DrlSanitizer()),
            mock(RuleExecutor.class),
            registry,
            mock(RuleStorage.class),
            timeoutConfig,
            meterRegistry);
    storage = mock(RuleStorage.class);
    storageFactory = mock(StorageFactory.class);
    when(storageFactory.createRuleStorage()).thenReturn(storage);
    loadState = new RuleLoadState();
    service = newService(false);
  }

  private RuleRefreshService newService(boolean allowEmpty) {
    return new RuleRefreshService(engine, storageFactory, loadState, meterRegistry, allowEmpty);
  }

  private static Rule rule(String id) {
    return RuleTestUtils.createSimpleRule(id);
  }

  /** Same rule ID, different (still valid) DRL → a different digest. */
  private static Rule changed(Rule base, String tag) {
    return new Rule(
        base.getRuleId(), base.getContent() + "// " + tag + "\n", RuleMetadata.createNew());
  }

  private static Rule broken(String id) {
    return RuleTestUtils.createInvalidRule(id);
  }

  private double runs(String source, String result) {
    var c = meterRegistry.find("drools.refresh.runs").tag("source", source).tag("result", result);
    return c.counter() == null ? 0.0 : c.counter().count();
  }

  /** Load {@code rules} through the service (CACHED read) and assert it succeeded. */
  private void preload(Rule... rules) {
    when(storage.getAllRules()).thenReturn(List.of(rules));
    BulkResult result = service.refreshAll(Source.STARTUP, ReadMode.CACHED);
    assertThat(result.status()).isEqualTo(BulkStatus.LOADED);
    assertThat(result.report().allSucceeded()).isTrue();
  }

  private Map<String, String> digests() {
    return engine.getServingDigests();
  }

  // ─── refreshAll ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("refreshAll")
  class RefreshAll {

    @Test
    @DisplayName("storage failure → STORAGE_FAILED, engine untouched, never throws")
    void storageFailureLeavesEngineUntouched() {
      preload(rule("a.one"), rule("a.two"));
      Map<String, String> before = digests();
      CompiledRuleset handle = registry.get("a.one").orElseThrow();
      when(storage.getAllRules()).thenThrow(new RuleStorageException("S3 timeout", null));

      BulkResult result = service.refreshAll(Source.ADMIN, ReadMode.CACHED);

      assertThat(result.status()).isEqualTo(BulkStatus.STORAGE_FAILED);
      assertThat(result.report()).isNull();
      assertThat(result.error()).contains("nothing changed").contains("S3 timeout");
      assertThat(result.failures()).isEmpty();
      assertThat(digests()).isEqualTo(before);
      assertThat(registry.get("a.one")).containsSame(handle);
      assertThat(runs("admin", "storage_failed")).isEqualTo(1.0);
      // A later failure does not undo a successful startup load
      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.LOADED);
      assertThat(loadState.getLastRefreshResult()).isEqualTo("failed");
      assertThat(loadState.getLastError()).contains("S3 timeout");
    }

    @Test
    @DisplayName("storage factory failure (e.g. unknown source) → STORAGE_FAILED")
    void storageFactoryFailure() {
      when(storageFactory.createRuleStorage())
          .thenThrow(new IllegalStateException("Unknown drools.rule-source 'bogus'"));

      BulkResult result = service.refreshAll(Source.STARTUP, ReadMode.CACHED);

      assertThat(result.status()).isEqualTo(BulkStatus.STORAGE_FAILED);
      assertThat(result.error()).contains("bogus");
      assertThat(engine.getLoadedRulesCount()).isZero();
    }

    @Test
    @DisplayName("empty read while rules are loaded → REJECTED_EMPTY, engine untouched")
    void emptySetRejected() {
      preload(rule("e.one"), rule("e.two"));
      Map<String, String> before = digests();
      when(storage.getAllRules()).thenReturn(List.of());

      BulkResult result = service.refreshAll(Source.PUBSUB, ReadMode.CACHED);

      assertThat(result.status()).isEqualTo(BulkStatus.REJECTED_EMPTY);
      assertThat(result.report()).isNull();
      assertThat(result.error()).contains("0 rules").contains("2 are loaded");
      assertThat(digests()).isEqualTo(before);
      assertThat(runs("pubsub", "rejected_empty")).isEqualTo(1.0);
      assertThat(loadState.getLastRefreshResult()).isEqualTo("rejected_empty");
      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.LOADED);
    }

    @Test
    @DisplayName("empty read with nothing loaded is a valid (empty) load")
    void emptySetWithNothingLoadedIsLoaded() {
      when(storage.getAllRules()).thenReturn(List.of());

      BulkResult result = service.refreshAll(Source.STARTUP, ReadMode.CACHED);

      assertThat(result.status()).isEqualTo(BulkStatus.LOADED);
      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.LOADED);
      assertThat(engine.getLoadedRulesCount()).isZero();
    }

    @Test
    @DisplayName("allow-empty=true: an empty read removes every rule")
    void allowEmptyRemovesAll() {
      preload(rule("x.one"), rule("x.two"));
      service = newService(true);
      when(storage.getAllRules()).thenReturn(List.of());

      BulkResult result = service.refreshAll(Source.ADMIN, ReadMode.CACHED);

      assertThat(result.status()).isEqualTo(BulkStatus.LOADED);
      assertThat(result.report().removed()).containsExactlyInAnyOrder("x.one", "x.two");
      assertThat(engine.getLoadedRulesCount()).isZero();
    }

    @Test
    @DisplayName("INVALIDATE_THEN_READ invalidates the cache before reading")
    void invalidateThenRead() {
      when(storage.getAllRules()).thenReturn(List.of(rule("i.one")));

      BulkResult result = service.refreshAll(Source.ADMIN, ReadMode.INVALIDATE_THEN_READ);

      assertThat(result.status()).isEqualTo(BulkStatus.LOADED);
      InOrder order = inOrder(storage);
      order.verify(storage).refreshCache();
      order.verify(storage).getAllRules();
      verify(storage, never()).getAllRulesFromSource();
    }

    @Test
    @DisplayName("CACHED does not invalidate the cache")
    void cachedDoesNotInvalidate() {
      when(storage.getAllRules()).thenReturn(List.of(rule("c.one")));

      service.refreshAll(Source.PUBSUB, ReadMode.CACHED);

      verify(storage, never()).refreshCache();
      verify(storage, never()).getAllRulesFromSource();
    }

    @Test
    @DisplayName("SOURCE reads getAllRulesFromSource (bypassing any cache)")
    void sourceReadsFromSource() {
      when(storage.getAllRulesFromSource()).thenReturn(List.of(rule("s.one")));

      BulkResult result = service.refreshAll(Source.ADMIN, ReadMode.SOURCE);

      assertThat(result.status()).isEqualTo(BulkStatus.LOADED);
      assertThat(engine.hasRule("s.one")).isTrue();
      verify(storage).getAllRulesFromSource();
      verify(storage, never()).getAllRules();
      verify(storage, never()).refreshCache();
    }

    @Test
    @DisplayName("digest skip: an identical second refresh reports all UNCHANGED, same handles")
    void identicalRefreshIsUnchanged() {
      preload(rule("d.one"), rule("d.two"));
      CompiledRuleset one = registry.get("d.one").orElseThrow();
      CompiledRuleset two = registry.get("d.two").orElseThrow();

      BulkResult result = service.refreshAll(Source.ADMIN, ReadMode.CACHED);

      assertThat(result.status()).isEqualTo(BulkStatus.LOADED);
      assertThat(result.report().unchanged()).containsExactlyInAnyOrder("d.one", "d.two");
      assertThat(result.report().loaded()).isEmpty();
      assertThat(result.report().removed()).isEmpty();
      assertThat(result.report().changedAnything()).isFalse();
      assertThat(registry.get("d.one")).containsSame(one);
      assertThat(registry.get("d.two")).containsSame(two);
      assertThat(runs("admin", "ok")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("one changed rule: only that ID is recompiled")
    void onlyChangedRuleRecompiled() {
      Rule one = rule("m.one");
      preload(one, rule("m.two"));
      CompiledRuleset two = registry.get("m.two").orElseThrow();
      Rule oneV2 = changed(one, "v2");
      when(storage.getAllRules()).thenReturn(List.of(oneV2, rule("m.two")));

      BulkResult result = service.refreshAll(Source.ADMIN, ReadMode.CACHED);

      assertThat(result.report().loaded()).containsExactly("m.one");
      assertThat(result.report().unchanged()).containsExactly("m.two");
      assertThat(engine.getServingDigest("m.one")).contains(oneV2.contentDigest());
      assertThat(registry.get("m.two")).containsSame(two);
    }

    @Test
    @DisplayName("per-ID compile failure → LOADED with failures, result=partial")
    void partialFailure() {
      when(storage.getAllRules()).thenReturn(List.of(rule("p.ok"), broken("p.bad")));

      BulkResult result = service.refreshAll(Source.ADMIN, ReadMode.CACHED);

      assertThat(result.status()).isEqualTo(BulkStatus.LOADED);
      assertThat(result.failures()).containsOnlyKeys("p.bad");
      assertThat(result.report().loaded()).containsExactly("p.ok");
      assertThat(runs("admin", "partial")).isEqualTo(1.0);
      assertThat(loadState.getLastRefreshResult()).isEqualTo("partial");
      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.LOADED);
    }

    @Test
    @DisplayName(
        "ordering: a slow OLD read and a later NEW read never install OLD last (lock covers"
            + " read + apply)")
    void concurrentRefreshesInstallNewestReadLast() throws Exception {
      Rule base = rule("o.rule");
      preload(base);
      Rule oldVersion = changed(base, "old");
      Rule newVersion = changed(base, "new");

      CountDownLatch firstReadEntered = new CountDownLatch(1);
      CountDownLatch releaseFirstRead = new CountDownLatch(1);
      AtomicInteger reads = new AtomicInteger();
      when(storage.getAllRules())
          .thenAnswer(
              inv -> {
                if (reads.incrementAndGet() == 1) {
                  firstReadEntered.countDown();
                  assertThat(releaseFirstRead.await(10, TimeUnit.SECONDS)).isTrue();
                  return List.of(oldVersion); // slow read of the OLD snapshot
                }
                return List.of(newVersion); // a later read sees the NEW snapshot
              });

      AtomicReference<BulkResult> first = new AtomicReference<>();
      AtomicReference<BulkResult> second = new AtomicReference<>();
      Thread t1 =
          new Thread(() -> first.set(service.refreshAll(Source.ADMIN, ReadMode.CACHED)), "r1");
      t1.start();
      assertThat(firstReadEntered.await(10, TimeUnit.SECONDS)).isTrue();

      Thread t2 =
          new Thread(() -> second.set(service.refreshAll(Source.PUBSUB, ReadMode.CACHED)), "r2");
      t2.start();
      // Wait until r2 is actually queued on the service lock (not merely started).
      ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(service, "lock");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!lock.hasQueuedThread(t2)) {
        assertThat(System.nanoTime()).isLessThan(deadline);
        Thread.onSpinWait();
      }
      // r2 has NOT read storage yet: the read is inside the lock.
      assertThat(reads.get()).isEqualTo(1);

      releaseFirstRead.countDown();
      t1.join(10_000);
      t2.join(10_000);

      assertThat(first.get().status()).isEqualTo(BulkStatus.LOADED);
      assertThat(second.get().status()).isEqualTo(BulkStatus.LOADED);
      assertThat(engine.getServingDigest("o.rule")).contains(newVersion.contentDigest());
    }
  }

  // ─── RuleLoadState transitions ──────────────────────────────────────────────

  @Nested
  @DisplayName("RuleLoadState")
  class LoadStateTransitions {

    @Test
    @DisplayName("starts NOT_LOADED")
    void startsNotLoaded() {
      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.NOT_LOADED);
      assertThat(loadState.getLastRefreshAt()).isNull();
    }

    @Test
    @DisplayName("startup failure → FAILED; a later successful full load → LOADED")
    void startupFailureThenRecovery() {
      when(storage.getAllRules()).thenThrow(new RuleStorageException("bucket missing", null));

      service.refreshAll(Source.STARTUP, ReadMode.CACHED);

      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.FAILED);
      assertThat(loadState.getLastRefreshSource()).isEqualTo("startup");
      assertThat(loadState.getLastError()).contains("bucket missing");

      RuleStorage healthy = mock(RuleStorage.class);
      when(healthy.getAllRulesFromSource()).thenReturn(List.of(rule("r.one")));
      when(storageFactory.createRuleStorage()).thenReturn(healthy);

      BulkResult recovered = service.reconcile();

      assertThat(recovered.status()).isEqualTo(BulkStatus.LOADED);
      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.LOADED);
      assertThat(loadState.getLastRefreshSource()).isEqualTo("reconcile");
      assertThat(loadState.getLastRefreshResult()).isEqualTo("ok");
      assertThat(loadState.getLastError()).isNull();
      assertThat(loadState.getLastRefreshAt()).isNotNull();
    }

    @Test
    @DisplayName("a non-startup full-load failure before any success also marks FAILED")
    void nonStartupFailureBeforeAnySuccess() {
      when(storage.getAllRules()).thenThrow(new RuleStorageException("down", null));

      service.refreshAll(Source.ADMIN, ReadMode.CACHED);

      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.FAILED);
    }

    @Test
    @DisplayName("a startup failure after a successful load still marks FAILED")
    void startupFailureAfterLoad() {
      preload(rule("f.one"));
      when(storage.getAllRules()).thenThrow(new RuleStorageException("down", null));

      service.refreshAll(Source.STARTUP, ReadMode.CACHED);

      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.FAILED);
    }

    @Test
    @DisplayName("single-rule refreshes never change the phase")
    void singleRefreshDoesNotChangePhase() {
      when(storage.getRule("n.one")).thenReturn(Optional.of(rule("n.one")));

      service.refreshOne("n.one", Source.ADMIN, false);

      assertThat(loadState.getPhase()).isEqualTo(RuleLoadState.Phase.NOT_LOADED);
      assertThat(loadState.getLastRefreshResult()).isEqualTo("installed");
    }
  }

  // ─── refreshOne / removeOne ─────────────────────────────────────────────────

  @Nested
  @DisplayName("refreshOne")
  class RefreshOne {

    @Test
    @DisplayName("new content → INSTALLED, previousDigest is the version that was serving")
    void installed() {
      Rule v1 = rule("one.rule");
      preload(v1);
      Rule v2 = changed(v1, "v2");
      when(storage.getRule("one.rule")).thenReturn(Optional.of(v2));

      SingleResult result = service.refreshOne("one.rule", Source.ADMIN, false);

      assertThat(result.status()).isEqualTo(SingleStatus.INSTALLED);
      assertThat(result.previousDigest()).isEqualTo(v1.contentDigest());
      assertThat(result.error()).isNull();
      assertThat(engine.getServingDigest("one.rule")).contains(v2.contentDigest());
      assertThat(runs("admin", "installed")).isEqualTo(1.0);
      verify(storage, never()).refreshRule(anyString());
    }

    @Test
    @DisplayName("brand-new rule ID → INSTALLED with no previous digest")
    void installedNew() {
      when(storage.getRule("fresh.rule")).thenReturn(Optional.of(rule("fresh.rule")));

      SingleResult result = service.refreshOne("fresh.rule", Source.PUBSUB, false);

      assertThat(result.status()).isEqualTo(SingleStatus.INSTALLED);
      assertThat(result.previousDigest()).isNull();
      assertThat(engine.hasRule("fresh.rule")).isTrue();
    }

    @Test
    @DisplayName("invalidateCache=true evicts the cached entry before reading")
    void invalidatesBeforeRead() {
      when(storage.getRule("inv.rule")).thenReturn(Optional.of(rule("inv.rule")));

      service.refreshOne("inv.rule", Source.ADMIN, true);

      InOrder order = inOrder(storage);
      order.verify(storage).refreshRule("inv.rule");
      order.verify(storage).getRule("inv.rule");
    }

    @Test
    @DisplayName("identical content → UNCHANGED, same handle")
    void unchanged() {
      Rule v1 = rule("same.rule");
      preload(v1);
      CompiledRuleset handle = registry.get("same.rule").orElseThrow();
      when(storage.getRule("same.rule")).thenReturn(Optional.of(rule("same.rule")));

      SingleResult result = service.refreshOne("same.rule", Source.ADMIN, false);

      assertThat(result.status()).isEqualTo(SingleStatus.UNCHANGED);
      assertThat(result.previousDigest()).isEqualTo(v1.contentDigest());
      assertThat(registry.get("same.rule")).containsSame(handle);
    }

    @Test
    @DisplayName("broken update → FAILED; the previous version keeps serving")
    void failedKeepsPrevious() {
      Rule v1 = rule("brk.rule");
      preload(v1);
      CompiledRuleset handle = registry.get("brk.rule").orElseThrow();
      when(storage.getRule("brk.rule")).thenReturn(Optional.of(broken("brk.rule")));

      SingleResult result = service.refreshOne("brk.rule", Source.ADMIN, false);

      assertThat(result.status()).isEqualTo(SingleStatus.FAILED);
      assertThat(result.error()).isNotBlank();
      assertThat(registry.get("brk.rule")).containsSame(handle);
      assertThat(engine.getServingDigest("brk.rule")).contains(v1.contentDigest());
      assertThat(loadState.getLastRefreshResult()).isEqualTo("failed");
      assertThat(loadState.getLastError()).isEqualTo(result.error());
    }

    @Test
    @DisplayName("gone from storage but loaded → REMOVED (it was deleted)")
    void removed() {
      preload(rule("gone.rule"), rule("stay.rule"));
      when(storage.getRule("gone.rule")).thenReturn(Optional.empty());

      SingleResult result = service.refreshOne("gone.rule", Source.ADMIN, true);

      assertThat(result.status()).isEqualTo(SingleStatus.REMOVED);
      assertThat(engine.hasRule("gone.rule")).isFalse();
      assertThat(engine.hasRule("stay.rule")).isTrue();
      assertThat(runs("admin", "removed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("gone from storage and not loaded → NOT_FOUND, nothing changed")
    void notFound() {
      preload(rule("other.rule"));
      when(storage.getRule("ghost.rule")).thenReturn(Optional.empty());

      SingleResult result = service.refreshOne("ghost.rule", Source.PUBSUB, false);

      assertThat(result.status()).isEqualTo(SingleStatus.NOT_FOUND);
      assertThat(engine.getLoadedRulesCount()).isEqualTo(1);
      assertThat(runs("pubsub", "not_found")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("storage read throws → STORAGE_FAILED, the serving version is untouched")
    void storageFailed() {
      Rule v1 = rule("sf.rule");
      preload(v1);
      when(storage.getRule("sf.rule")).thenThrow(new RuleStorageException("S3 down", null));

      SingleResult result = service.refreshOne("sf.rule", Source.ADMIN, false);

      assertThat(result.status()).isEqualTo(SingleStatus.STORAGE_FAILED);
      assertThat(result.error()).contains("S3 down");
      assertThat(result.previousDigest()).isNull();
      assertThat(engine.getServingDigest("sf.rule")).contains(v1.contentDigest());
      assertThat(runs("admin", "storage_failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("cache invalidation throws → STORAGE_FAILED (never propagates)")
    void invalidationFailure() {
      org.mockito.Mockito.doThrow(new IllegalStateException("redis gone"))
          .when(storage)
          .refreshRule("x.rule");

      SingleResult result = service.refreshOne("x.rule", Source.ADMIN, true);

      assertThat(result.status()).isEqualTo(SingleStatus.STORAGE_FAILED);
      assertThat(result.error()).contains("redis gone");
    }

    @Test
    @DisplayName("removeOne removes just that rule ID and counts it")
    void removeOne() {
      preload(rule("rm.one"), rule("rm.two"));

      service.removeOne("rm.one", Source.PUBSUB);

      assertThat(engine.hasRule("rm.one")).isFalse();
      assertThat(engine.hasRule("rm.two")).isTrue();
      assertThat(runs("pubsub", "removed")).isEqualTo(1.0);
      assertThat(loadState.getLastRefreshSource()).isEqualTo("pubsub");
    }
  }

  // ─── reconcile ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("reconcile")
  class Reconcile {

    private double drift() {
      var c = meterRegistry.find("drools.reconcile.changed").counter();
      return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("reads the source directly and counts drift (updated + removed)")
    void countsDrift() {
      Rule a = rule("rc.a");
      preload(a, rule("rc.b"));
      // Storage now: a changed, b deleted, c added
      when(storage.getAllRulesFromSource()).thenReturn(List.of(changed(a, "v2"), rule("rc.c")));

      BulkResult result = service.reconcile();

      assertThat(result.status()).isEqualTo(BulkStatus.LOADED);
      assertThat(result.report().loaded()).containsExactlyInAnyOrder("rc.a", "rc.c");
      assertThat(result.report().removed()).containsExactly("rc.b");
      assertThat(drift()).isEqualTo(3.0);
      assertThat(runs("reconcile", "ok")).isEqualTo(1.0);
      verify(storage, never()).refreshCache();
    }

    @Test
    @DisplayName("no drift → drift counter not incremented")
    void noDrift() {
      preload(rule("nd.a"), rule("nd.b"));
      when(storage.getAllRulesFromSource()).thenReturn(List.of(rule("nd.a"), rule("nd.b")));

      BulkResult result = service.reconcile();

      assertThat(result.report().unchanged()).hasSize(2);
      assertThat(drift()).isZero();
    }

    @Test
    @DisplayName("source unreadable → STORAGE_FAILED, nothing changed")
    void sourceUnreadable() {
      preload(rule("su.a"));
      when(storage.getAllRulesFromSource()).thenThrow(new RuleStorageException("nope", null));

      BulkResult result = service.reconcile();

      assertThat(result.status()).isEqualTo(BulkStatus.STORAGE_FAILED);
      assertThat(engine.hasRule("su.a")).isTrue();
      assertThat(runs("reconcile", "storage_failed")).isEqualTo(1.0);
      assertThat(drift()).isZero();
    }
  }

  // ─── audit log ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("audit log")
  class AuditLog {

    private static final String SECRET_TOKEN = "zebraSecretToken42";
    private static final Pattern HEX12 = Pattern.compile("[0-9a-f]{12}");

    private ch.qos.logback.classic.Logger auditLogger;
    private ch.qos.logback.classic.Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
      auditLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT");
      previousLevel = auditLogger.getLevel();
      auditLogger.setLevel(ch.qos.logback.classic.Level.INFO);
      appender = new ListAppender<>();
      appender.start();
      auditLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
      auditLogger.detachAppender(appender);
      auditLogger.setLevel(previousLevel);
    }

    private List<String> lines() {
      return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String onlyLine() {
      assertThat(lines()).hasSize(1);
      return lines().get(0);
    }

    /** A valid rule whose DRL body carries a distinctive token that must never be audited. */
    private Rule secretRule(String id) {
      String drl =
          """
          package com.company.rules.audit

          import java.util.Map

          rule "%s"
          when
              $data : Map()
          then
              $data.put("%s", true);
          end
          """
              .formatted(id, SECRET_TOKEN);
      return new Rule(id, drl, RuleMetadata.createNew());
    }

    private String digestField(String line, String field) {
      Matcher m = Pattern.compile(field + "=(\\S+)").matcher(line);
      assertThat(m.find()).as(field + " present in: " + line).isTrue();
      return m.group(1);
    }

    @Test
    @DisplayName("admin bulk refresh: action, source, actor, result, counts and IDs; no DRL")
    void bulkAdminRefresh() {
      preload(secretRule("au.keep"), secretRule("au.gone"));
      appender.list.clear();
      when(storage.getAllRules()).thenReturn(List.of(secretRule("au.keep"), secretRule("au.new")));

      service.refreshAll(Source.ADMIN, ReadMode.INVALIDATE_THEN_READ, "10.9.8.7");

      String line = onlyLine();
      assertThat(line)
          .startsWith("AUDIT ")
          .contains("action=refresh_all")
          .contains("source=admin")
          .contains("actor=10.9.8.7")
          .contains("result=ok")
          .contains("loaded=1")
          .contains("unchanged=1")
          .contains("failed=0")
          .contains("removed=1")
          .contains("loaded_ids=[au.new]")
          .contains("failed_ids=[]")
          .contains("removed_ids=[au.gone]")
          .doesNotContain(SECRET_TOKEN)
          .doesNotContain("$data")
          .doesNotContain("package ");
    }

    @Test
    @DisplayName("partial bulk refresh lists failed IDs only, never the compiler message")
    void bulkPartial() {
      when(storage.getAllRules()).thenReturn(List.of(secretRule("pa.ok"), broken("pa.bad")));

      BulkResult result = service.refreshAll(Source.ADMIN, ReadMode.CACHED, "1.1.1.1");

      String line = onlyLine();
      assertThat(line)
          .contains("result=partial")
          .contains("failed=1")
          .contains("failed_ids=[pa.bad]")
          .doesNotContain(SECRET_TOKEN)
          .doesNotContain(result.failures().get("pa.bad"));
    }

    @Test
    @DisplayName("ID lists are sorted and capped at 50 with a +N suffix")
    void idListsCapped() {
      Rule[] rules = new Rule[53];
      for (int i = 0; i < rules.length; i++) {
        rules[i] = rule("cap.r%02d".formatted(52 - i)); // reverse order in, sorted out
      }
      when(storage.getAllRules()).thenReturn(List.of(rules));

      service.refreshAll(Source.ADMIN, ReadMode.CACHED, null);

      String line = onlyLine();
      assertThat(line)
          .contains("actor=-")
          .contains("loaded=53")
          .contains("loaded_ids=[cap.r00, cap.r01,");
      assertThat(line).contains("cap.r49]+3").doesNotContain("cap.r50");
    }

    @Test
    @DisplayName("bulk storage failure → result=storage_failed")
    void bulkStorageFailure() {
      when(storage.getAllRules()).thenThrow(new RuleStorageException("S3 timeout", null));

      service.refreshAll(Source.ADMIN, ReadMode.CACHED, "10.0.0.1");

      assertThat(onlyLine())
          .contains("action=refresh_all")
          .contains("actor=10.0.0.1")
          .contains("result=storage_failed")
          .contains("error=");
    }

    @Test
    @DisplayName("refresh_rule: rule_id, result and 12-hex old/new digests")
    void refreshRuleInstalled() {
      Rule v1 = secretRule("au.one");
      preload(v1);
      appender.list.clear();
      Rule v2 = changed(v1, "v2");
      when(storage.getRule("au.one")).thenReturn(Optional.of(v2));

      service.refreshOne("au.one", Source.ADMIN, true, "10.1.2.3");

      String line = onlyLine();
      assertThat(line)
          .contains("action=refresh_rule")
          .contains("source=admin")
          .contains("actor=10.1.2.3")
          .contains("rule_id=au.one")
          .contains("result=installed")
          .doesNotContain("error=")
          .doesNotContain(SECRET_TOKEN);
      String oldDigest = digestField(line, "old_digest");
      String newDigest = digestField(line, "new_digest");
      assertThat(oldDigest).matches(HEX12).isEqualTo(v1.contentDigest().substring(0, 12));
      assertThat(newDigest).matches(HEX12).isEqualTo(v2.contentDigest().substring(0, 12));
    }

    @Test
    @DisplayName("refresh_rule compile failure → error=compile_failed, no compiler message")
    void refreshRuleCompileFailed() {
      Rule v1 = secretRule("au.brk");
      preload(v1);
      appender.list.clear();
      when(storage.getRule("au.brk")).thenReturn(Optional.of(broken("au.brk")));

      SingleResult result = service.refreshOne("au.brk", Source.ADMIN, false, "10.1.2.3");

      assertThat(result.error()).isNotBlank();
      String line = onlyLine();
      assertThat(line)
          .contains("rule_id=au.brk")
          .contains("result=failed")
          .endsWith(" error=compile_failed")
          .doesNotContain(result.error())
          .doesNotContain("Unable to resolve")
          .doesNotContain(SECRET_TOKEN);
      // The previous version keeps serving, so old and new digest are the same.
      assertThat(digestField(line, "old_digest")).isEqualTo(digestField(line, "new_digest"));
    }

    @Test
    @DisplayName("refresh_rule storage failure → result=storage_failed error=storage_read_failed")
    void refreshRuleStorageFailed() {
      when(storage.getRule("au.sf")).thenThrow(new RuleStorageException("S3 down at key", null));

      service.refreshOne("au.sf", Source.PUBSUB, false);

      assertThat(onlyLine())
          .contains("source=pubsub")
          .contains("actor=-")
          .contains("rule_id=au.sf")
          .contains("result=storage_failed")
          .endsWith(" error=storage_read_failed")
          .doesNotContain("S3 down");
    }

    @Test
    @DisplayName("a reconcile that changes nothing emits no audit line")
    void quietReconcile() {
      preload(rule("q.a"), rule("q.b"));
      appender.list.clear();
      when(storage.getAllRulesFromSource()).thenReturn(List.of(rule("q.a"), rule("q.b")));

      service.reconcile();

      assertThat(lines()).isEmpty();
    }

    @Test
    @DisplayName("a reconcile that finds drift is audited as action=reconcile")
    void driftReconcile() {
      preload(rule("d.a"), rule("d.b"));
      appender.list.clear();
      when(storage.getAllRulesFromSource()).thenReturn(List.of(rule("d.a")));

      service.reconcile();

      assertThat(onlyLine())
          .contains("action=reconcile")
          .contains("source=reconcile")
          .contains("actor=-")
          .contains("removed_ids=[d.b]");
    }

    @Test
    @DisplayName("remove_rule line carries rule_id and the removed digest")
    void removeRule() {
      Rule r = secretRule("au.rm");
      preload(r);
      appender.list.clear();

      service.removeOne("au.rm", Source.PUBSUB);

      String line = onlyLine();
      assertThat(line)
          .contains("action=remove_rule")
          .contains("source=pubsub")
          .contains("rule_id=au.rm")
          .contains("result=removed")
          .doesNotContain(SECRET_TOKEN);
      assertThat(digestField(line, "old_digest"))
          .matches(HEX12)
          .isEqualTo(r.contentDigest().substring(0, 12));
    }
  }
}
