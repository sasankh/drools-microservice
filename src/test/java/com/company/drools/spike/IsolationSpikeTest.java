package com.company.drools.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.drools.kiesession.rulebase.InternalKnowledgeBase;
import org.drools.kiesession.rulebase.KnowledgeBaseFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.model.KieBaseModel;
import org.kie.api.builder.model.KieModuleModel;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.internal.builder.KnowledgeBuilder;
import org.kie.internal.builder.KnowledgeBuilderFactory;
import org.kie.internal.io.ResourceFactory;
import org.kie.internal.utils.KieHelper;

/**
 * Phase 1 spike (engine-isolation plan, 2026-11-18): how to give each rule_id its own compiled
 * KieBase on Drools 10.2.0. Functional checks run in the normal suite; the measurement mode runs
 * only with {@code -Dspike.measure=true}. Removed once ADR-025 records the decision.
 */
class IsolationSpikeTest {

  private static final KieServices KS = KieServices.Factory.get();
  private static final AtomicLong VERSION = new AtomicLong();

  /** Per-ID isolation strategies under evaluation. */
  enum Strategy {
    /** A1: one KieModule per rule ID (own ReleaseId), KieBase taken from its container. */
    A1_MODULE_PER_ID {
      @Override
      KieBase build(String ruleId, String drl) {
        ReleaseId rid =
            KS.newReleaseId(
                "com.company.drools.spike",
                "a1-" + ruleId.replaceAll("[^A-Za-z0-9_-]", "-"),
                "1.0." + VERSION.incrementAndGet());
        KieFileSystem kfs = KS.newKieFileSystem();
        kfs.generateAndWritePomXML(rid);
        kfs.write("src/main/resources/rules/" + ruleId.replace('.', '/') + ".drl", drl);
        KieBuilder kb = KS.newKieBuilder(kfs).buildAll();
        assertNoErrors(kb.getResults().getMessages(Message.Level.ERROR).toString(), kb);
        KieContainer kc = KS.newKieContainer(rid);
        KieBase base = kc.getKieBase();
        // Retire the module immediately: the KieBase must stay usable on its own.
        KS.getRepository().removeKieModule(rid);
        return base;
      }
    },
    /** A2a: KieHelper (public kie-internal utility). */
    A2_KIE_HELPER {
      @Override
      KieBase build(String ruleId, String drl) {
        return new KieHelper().addContent(drl, ResourceType.DRL).build();
      }
    },
    /** A2b: KnowledgeBuilder + KnowledgeBaseFactory (no KieModule/KieRepository at all). */
    A2_KNOWLEDGE_BUILDER {
      @Override
      KieBase build(String ruleId, String drl) {
        KnowledgeBuilder kb = KnowledgeBuilderFactory.newKnowledgeBuilder();
        kb.add(
            ResourceFactory.newByteArrayResource(drl.getBytes(StandardCharsets.UTF_8))
                .setSourcePath("rules/" + ruleId.replace('.', '/') + ".drl"),
            ResourceType.DRL);
        if (kb.hasErrors()) {
          throw new IllegalStateException("compile errors: " + kb.getErrors());
        }
        InternalKnowledgeBase base = KnowledgeBaseFactory.newKnowledgeBase();
        base.addPackages(kb.getKnowledgePackages());
        return base;
      }
    };

    abstract KieBase build(String ruleId, String drl);

    private static void assertNoErrors(String errors, KieBuilder kb) {
      if (kb.getResults().hasMessages(Message.Level.ERROR)) {
        throw new IllegalStateException("compile errors: " + errors);
      }
    }
  }

  // ---------------------------------------------------------------- functional checks

  @ParameterizedTest
  @EnumSource(Strategy.class)
  void simpleDoesNotRunVip(Strategy s) throws IOException {
    KieBase simple = s.build("pricing.discount.simple", sample("pricing.discount.simple"));
    Map<String, Object> out =
        fire(simple, new HashMap<>(Map.of("customerType", "VIP", "amount", 100)));
    assertThat(((Number) out.get("amount")).doubleValue()).isEqualTo(90.0);
    assertThat(out).doesNotContainKey("customerTier");
  }

  @ParameterizedTest
  @EnumSource(Strategy.class)
  void vipAloneGivesEighty(Strategy s) throws IOException {
    KieBase vip = s.build("pricing.discount.vip", sample("pricing.discount.vip"));
    Map<String, Object> out =
        fire(vip, new HashMap<>(Map.of("customerType", "VIP", "amount", 100)));
    assertThat(((Number) out.get("amount")).doubleValue()).isEqualTo(80.0);
  }

  @ParameterizedTest
  @EnumSource(Strategy.class)
  void twoRulesInOneFileBothFireInSalienceOrder(Strategy s) {
    String drl =
        """
        package com.company.rules.spike.multi
        import java.util.Map
        import java.util.List
        rule "second" salience 10
        when $d : Map(this["trace"] != null)
        then ((List) $d.get("trace")).add("second");
        end
        rule "first" salience 100
        when $d : Map(this["trace"] != null)
        then ((List) $d.get("trace")).add("first");
        end
        """;
    KieBase base = s.build("spike.multi", drl);
    Map<String, Object> data = new HashMap<>();
    data.put("trace", new ArrayList<String>());
    fire(base, data);
    assertThat(data.get("trace")).isEqualTo(List.of("first", "second"));
  }

  @ParameterizedTest
  @EnumSource(Strategy.class)
  void forallWithoutPriceNoLongerCrashes(Strategy s) throws IOException {
    KieBase forall = s.build("validation.cart.forall", sample("validation.cart.forall"));
    Map<String, Object> data = new HashMap<>();
    data.put("items", new ArrayList<>(List.of(new HashMap<>(Map.of("stockLevel", 10)))));
    Map<String, Object> out = fire(forall, data);
    assertThat(out).containsEntry("allItemsInStock", true);
  }

  @ParameterizedTest
  @EnumSource(Strategy.class)
  void all17SampleFilesCompileIndependently(Strategy s) throws IOException {
    Map<String, String> all = allSamples();
    assertThat(all).hasSize(17);
    all.forEach((id, drl) -> assertThat(s.build(id, drl).getKiePackages()).isNotEmpty());
  }

  @ParameterizedTest
  @EnumSource(Strategy.class)
  void retiredBaseIsGarbageCollected(Strategy s) throws IOException {
    WeakReference<KieBase> ref = buildAndUseOnce(s);
    for (int i = 0; i < 20 && ref.get() != null; i++) {
      System.gc();
      sleep(50);
    }
    assertThat(ref.get()).as("retired KieBase should be collectable").isNull();
  }

  private WeakReference<KieBase> buildAndUseOnce(Strategy s) throws IOException {
    KieBase base = s.build("pricing.discount.simple", sample("pricing.discount.simple"));
    fire(base, new HashMap<>(Map.of("amount", 100)));
    return new WeakReference<>(base);
  }

  /**
   * Option B: one module, one named KieBase per rule ID selected by its resource folder. Observed
   * on 10.2.0: kbase {@code packages} matches the DRL's DECLARED package, not the folder, so a
   * folder-selected kbase for a file declaring {@code com.company.rules.pricing.discount} contains
   * no rules (amount stays 100). Files sharing a declared package can't be separated this way
   * without rewriting their {@code package} line. Rejected.
   */
  @Test
  void optionB_namedKieBasePerFolder_cannotIsolateFilesSharingADrlPackage() throws IOException {
    ReleaseId rid = KS.newReleaseId("com.company.drools.spike", "b-named", "1.0.0");
    KieModuleModel module = KS.newKieModuleModel();
    String[] ids = {"pricing.discount.simple", "pricing.discount.vip"};
    KieFileSystem kfs = KS.newKieFileSystem();
    kfs.generateAndWritePomXML(rid);
    for (String id : ids) {
      String folder = "r/" + id.replace('.', '/');
      KieBaseModel kb = module.newKieBaseModel("kb_" + id.replace('.', '_'));
      kb.addPackage(folder.replace('/', '.'));
      kb.newKieSessionModel("ks_" + id.replace('.', '_'));
      kfs.write("src/main/resources/" + folder + "/rule.drl", sample(id));
    }
    kfs.writeKModuleXML(module.toXML());
    KieBuilder builder = KS.newKieBuilder(kfs).buildAll();
    assertThat(builder.getResults().getMessages(Message.Level.ERROR)).isEmpty();
    KieContainer kc = KS.newKieContainer(rid);

    Map<String, Object> out =
        fire(
            kc.getKieBase("kb_pricing_discount_simple"),
            new HashMap<>(Map.of("customerType", "VIP", "amount", 100)));
    assertThat(((Number) out.get("amount")).doubleValue())
        .as("folder-selected kbase is empty: no rule fired")
        .isEqualTo(100.0);
    KS.getRepository().removeKieModule(rid);
  }

  /** Option C: global base + AgendaFilter. Records what happens to another rule's LHS. */
  @Test
  void optionC_agendaFilter_doesNotStopOtherRulesLhs() throws IOException {
    KieHelper helper = new KieHelper();
    allSamples().values().forEach(drl -> helper.addContent(drl, ResourceType.DRL));
    KieBase global = helper.build();
    Map<String, Object> data = new HashMap<>();
    data.put("items", new ArrayList<>(List.of(new HashMap<>(Map.of("stockLevel", 10)))));
    Set<String> forallRules = Set.of("All Cart Items In Stock");
    Throwable thrown = null;
    try (KieSession session = global.newKieSession()) {
      session.insert(data);
      session.fireAllRules(m -> forallRules.contains(m.getRule().getName()));
    } catch (Throwable t) {
      thrown = t;
    }
    System.out.println(
        "SPIKE optionC: thrown=" + (thrown == null ? "none" : thrown) + " result=" + data);
    assertThat(thrown)
        .as("bundle rule LHS still evaluates and NPEs on the missing price")
        .isNotNull();
  }

  // ---------------------------------------------------------------- measurements

  @Test
  void measure() throws IOException {
    assumeTrue(Boolean.getBoolean("spike.measure"), "set -Dspike.measure=true");
    String which = System.getProperty("spike.strategy", "A2_KNOWLEDGE_BUILDER");
    int n = Integer.getInteger("spike.n", 1000);
    Map<String, String> corpus = synthetic(n);
    List<String> ids = new ArrayList<>(corpus.keySet());

    long heap0 = usedHeapAfterGc();
    long meta0 = metaspaceUsed();
    long classes0 = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();

    Map<String, KieBase> bases = new HashMap<>();
    long t0 = System.nanoTime();
    KieBase global = null;
    if ("GLOBAL".equals(which)) {
      global = buildGlobal(corpus);
    } else {
      Strategy s = Strategy.valueOf(which);
      corpus.forEach((id, drl) -> bases.put(id, s.build(id, drl)));
    }
    long coldMs = (System.nanoTime() - t0) / 1_000_000;
    long heap1 = usedHeapAfterGc();

    // warm execution latency over 10k requests (each hits its own rule)
    long[] lat = new long[10_000];
    for (int i = 0; i < lat.length; i++) {
      String id = ids.get(i % ids.size());
      KieBase base = global != null ? global : bases.get(id);
      Map<String, Object> data = new HashMap<>(Map.of(keyOf(id), 100));
      long s0 = System.nanoTime();
      fire(base, data);
      lat[i] = System.nanoTime() - s0;
      if (!Boolean.TRUE.equals(data.get("fired_" + keyOf(id)))) {
        throw new AssertionError("rule did not fire for " + id);
      }
    }
    java.util.Arrays.sort(lat);

    // single-ID refresh: rebuild one rule 100 times (GLOBAL rebuilds everything, 5 times)
    int rebuilds = global != null ? 5 : Integer.getInteger("spike.rebuilds", 100);
    long[] ref = new long[rebuilds];
    String target = ids.get(0);
    for (int i = 0; i < rebuilds; i++) {
      long s0 = System.nanoTime();
      if (global != null) {
        global = buildGlobal(corpus);
      } else {
        bases.put(target, Strategy.valueOf(which).build(target, corpus.get(target)));
      }
      ref[i] = System.nanoTime() - s0;
      if (global == null && (i + 1) % 250 == 0) {
        System.out.printf(
            "SPIKE rebuild=%d heapMB=%.1f metaspaceMB=%.1f%n",
            i + 1, (usedHeapAfterGc() - heap0) / 1048576.0, metaspaceUsed() / 1048576.0);
      }
    }
    java.util.Arrays.sort(ref);
    long heap2 = usedHeapAfterGc();
    long meta2 = metaspaceUsed();
    long classes2 = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();

    System.out.printf(
        "SPIKE strategy=%s n=%d coldBuildMs=%d heapResidentMB=%.1f heapAfterRefreshesMB=%.1f"
            + " execP50us=%d execP99us=%d refreshP50ms=%.1f refreshMaxMs=%.1f"
            + " metaspaceStartMB=%.1f metaspaceEndMB=%.1f classesStart=%d classesEnd=%d%n",
        which,
        n,
        coldMs,
        (heap1 - heap0) / 1048576.0,
        (heap2 - heap0) / 1048576.0,
        lat[lat.length / 2] / 1000,
        lat[(int) (lat.length * 0.99)] / 1000,
        ref[ref.length / 2] / 1e6,
        ref[ref.length - 1] / 1e6,
        meta0 / 1048576.0,
        meta2 / 1048576.0,
        classes0,
        classes2);
  }

  private static KieBase buildGlobal(Map<String, String> corpus) {
    ReleaseId rid =
        KS.newReleaseId("com.company.drools.spike", "global", "1.0." + VERSION.incrementAndGet());
    KieFileSystem kfs = KS.newKieFileSystem();
    kfs.generateAndWritePomXML(rid);
    corpus.forEach(
        (id, drl) -> kfs.write("src/main/resources/rules/" + id.replace('.', '/') + ".drl", drl));
    KS.newKieBuilder(kfs).buildAll();
    KieBase base = KS.newKieContainer(rid).getKieBase();
    KS.getRepository().removeKieModule(rid);
    return base;
  }

  // ---------------------------------------------------------------- helpers

  private static Map<String, Object> fire(KieBase base, Map<String, Object> data) {
    try (KieSession session = base.newKieSession()) {
      session.insert(data);
      session.fireAllRules(10_000);
    }
    return data;
  }

  private static String sample(String ruleId) throws IOException {
    return Files.readString(Paths.get("sample-rules/" + ruleId.replace('.', '/') + ".drl"));
  }

  private static Map<String, String> allSamples() throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    Path root = Paths.get("sample-rules");
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path p : paths.filter(x -> x.toString().endsWith(".drl")).sorted().toList()) {
        String rel = root.relativize(p).toString();
        out.put(rel.substring(0, rel.length() - 4).replace('/', '.'), Files.readString(p));
      }
    }
    return out;
  }

  private static String keyOf(String id) {
    return "k_" + id.substring(id.lastIndexOf('.') + 1);
  }

  /** Synthetic corpus: one rule per ID, all in ONE shared DRL package (worst case for B). */
  private static Map<String, String> synthetic(int n) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 1; i <= n; i++) {
      String id = "synth.simple.r" + i;
      String key = "k_r" + i;
      out.put(
          id,
          """
          package com.company.rules.synthetic.simple
          import java.util.Map
          rule "synthetic-simple-%d"
          when
              $data : Map(this["%s"] != null, ((Number)this["%s"]).doubleValue() > 50.0)
          then
              double v = ((Number) $data.get("%s")).doubleValue();
              $data.put("fired_%s", true);
              $data.put("result_%s", v * 0.9);
          end
          """
              .formatted(i, key, key, key, key, key));
    }
    return out;
  }

  private static long usedHeapAfterGc() {
    for (int i = 0; i < 4; i++) {
      System.gc();
      sleep(100);
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static long metaspaceUsed() {
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if ("Metaspace".equals(pool.getName())) {
        return pool.getUsage().getUsed();
      }
    }
    return -1;
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
