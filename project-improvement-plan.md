# 🚀 Drools Rule Engine - Improvement Plan
**Date**: 2026-02-19
**Goal**: Transform from "Functionally Complete" to "Production-Ready with Confidence"

---

## 🎯 Goals

1. **Fix Critical Issues**: Java version, memory stability
2. **Add Test Coverage**: 80% code coverage, performance validated
3. **Production Hardening**: Monitoring, security, operations
4. **Deployment Ready**: Confident production deployment

---

## 📅 3-Week Sprint Plan

### Week 1: Critical Fixes & Foundation
**Goal**: Fix blocking issues, establish testing foundation

### Week 2: Test Coverage & Validation
**Goal**: Comprehensive testing, performance validation

### Week 3: Production Hardening
**Goal**: Monitoring, security, operational readiness

---

## Week 1: Critical Fixes (5 days)

### Day 1: Environment Setup & Java Fix ✅

**Morning (2 hours)**
- [ ] **Fix Java Version Mismatch**
  ```bash
  # Find Java 17 installation
  /usr/libexec/java_home -V

  # Set JAVA_HOME (add to ~/.zshrc)
  export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  export PATH=$JAVA_HOME/bin:$PATH

  # Verify
  java -version  # Should show 17
  mvn -version   # Should show Java 17
  ```

- [ ] **Verify Build**
  ```bash
  mvn clean compile
  mvn clean package -DskipTests
  ls -lh target/drools-rule-engine-1.0.0.jar
  ```

**Afternoon (2 hours)**
- [ ] **Start Docker Environment**
  ```bash
  # Start Docker Desktop manually
  # Verify Docker is running
  docker ps

  # Run setup script
  ./setup-dev-environment.sh

  # Verify services
  docker-compose ps
  curl http://localhost:8081/admin/health
  ```

- [ ] **Test Sample Rules**
  ```bash
  # Test simple discount rule
  curl -X POST http://localhost:8080/execute-rule \
    -H "Content-Type: application/json" \
    -d '{
      "ruleId": "pricing.discount.simple",
      "data": {"amount": 100}
    }'

  # Test VIP discount rule
  curl -X POST http://localhost:8080/execute-rule \
    -H "Content-Type: application/json" \
    -d '{
      "ruleId": "pricing.discount.vip",
      "data": {"customerType": "VIP", "amount": 100}
    }'
  ```

**Deliverables:**
- ✅ Java 17 configured and verified
- ✅ Build successful
- ✅ Docker environment running
- ✅ Sample rules executing successfully

---

### Day 2: Memory Investigation & Fixes

**Morning (3 hours)**
- [ ] **Add Memory Diagnostics**

  Update `docker-compose.yml`:
  ```yaml
  app:
    environment:
      JAVA_OPTS: >-
        -Xms512m
        -Xmx2048m
        -XX:+UseG1GC
        -XX:MaxGCPauseMillis=200
        -XX:+HeapDumpOnOutOfMemoryError
        -XX:HeapDumpPath=/tmp/heapdump.hprof
        -Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags
    volumes:
      - ./heap-dumps:/tmp
  ```

- [ ] **Add Memory Monitoring Endpoint**

  Create `src/main/java/com/company/drools/api/controller/MemoryController.java`:
  ```java
  @RestController
  @RequestMapping("/admin/memory")
  public class MemoryController {

      @GetMapping("/info")
      public Map<String, Object> getMemoryInfo() {
          Runtime runtime = Runtime.getRuntime();
          long maxMemory = runtime.maxMemory();
          long totalMemory = runtime.totalMemory();
          long freeMemory = runtime.freeMemory();
          long usedMemory = totalMemory - freeMemory;

          return Map.of(
              "maxMemoryMB", maxMemory / 1024 / 1024,
              "totalMemoryMB", totalMemory / 1024 / 1024,
              "usedMemoryMB", usedMemory / 1024 / 1024,
              "freeMemoryMB", freeMemory / 1024 / 1024,
              "usagePercent", (usedMemory * 100.0) / maxMemory
          );
      }

      @PostMapping("/gc")
      public String triggerGC() {
          System.gc();
          return "GC triggered";
      }
  }
  ```

**Afternoon (2 hours)**
- [ ] **Review KieBase Caching**

  Check `DroolsEngineService.java` and `LocalLRUCache.java`:
  - Verify KieBase instances are cached correctly
  - Check eviction policy removes old instances
  - Ensure no memory leaks in cache

- [ ] **Add Cache Cleanup**

  Update `LocalLRUCache.java`:
  ```java
  @Scheduled(fixedRate = 300000) // Every 5 minutes
  public void cleanupStaleEntries() {
      long now = System.currentTimeMillis();
      cache.entrySet().removeIf(entry -> {
          long age = now - entry.getValue().getLastAccessTime();
          return age > maxAgeMillis; // e.g., 1 hour
      });
  }
  ```

**Deliverables:**
- ✅ Memory monitoring endpoint added
- ✅ Heap dumps configured
- ✅ GC logging enabled
- ✅ Cache cleanup implemented

---

### Day 3: Code Quality & Deprecation Fixes

**Morning (2 hours)**
- [ ] **Fix Redis Deprecated API**

  Review `RedisConfig.java` and update to use latest APIs.

- [ ] **Fix Drools Package Warnings**

  Align package declarations with folder structure in sample rules:
  ```drools
  // Change from:
  package com.company.rules.pricing.discount

  // To:
  package rules.pricing.discount
  ```

- [ ] **Run Code Formatting**
  ```bash
  mvn spotless:apply
  mvn spotless:check
  ```

**Afternoon (2 hours)**
- [ ] **Add Maven Enforcer Plugin**

  Update `pom.xml`:
  ```xml
  <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-enforcer-plugin</artifactId>
      <version>3.3.0</version>
      <executions>
          <execution>
              <id>enforce-java</id>
              <goals>
                  <goal>enforce</goal>
              </goals>
              <configuration>
                  <rules>
                      <requireJavaVersion>
                          <version>[17,18)</version>
                          <message>Java 17 is required!</message>
                      </requireJavaVersion>
                      <requireMavenVersion>
                          <version>[3.8,)</version>
                      </requireMavenVersion>
                  </rules>
              </configuration>
          </execution>
      </executions>
  </plugin>
  ```

- [ ] **Verify Clean Build**
  ```bash
  mvn clean verify
  # Should have no warnings
  ```

**Deliverables:**
- ✅ All deprecation warnings fixed
- ✅ Code formatted with Spotless
- ✅ Maven enforcer enforces Java 17
- ✅ Clean build with no warnings

---

### Day 4-5: Test Infrastructure Setup

**Day 4 Morning (3 hours)**
- [ ] **Create Test Structure**
  ```bash
  mkdir -p src/test/java/com/company/drools/{core,cache,storage,api}
  mkdir -p src/test/resources
  ```

- [ ] **Add Test Dependencies** (verify in pom.xml)
  ```xml
  <dependencies>
      <!-- JUnit 5 -->
      <dependency>
          <groupId>org.junit.jupiter</groupId>
          <artifactId>junit-jupiter</artifactId>
          <scope>test</scope>
      </dependency>

      <!-- Mockito -->
      <dependency>
          <groupId>org.mockito</groupId>
          <artifactId>mockito-core</artifactId>
          <scope>test</scope>
      </dependency>

      <!-- Spring Boot Test -->
      <dependency>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-starter-test</artifactId>
          <scope>test</scope>
      </dependency>

      <!-- TestContainers for integration tests -->
      <dependency>
          <groupId>org.testcontainers</groupId>
          <artifactId>testcontainers</artifactId>
          <scope>test</scope>
      </dependency>
      <dependency>
          <groupId>org.testcontainers</groupId>
          <artifactId>localstack</artifactId>
          <scope>test</scope>
      </dependency>
  </dependencies>
  ```

**Day 4 Afternoon (3 hours)**
- [ ] **Create Base Test Classes**

  `src/test/java/com/company/drools/BaseUnitTest.java`:
  ```java
  @ExtendWith(MockitoExtension.class)
  public abstract class BaseUnitTest {
      // Common test utilities
  }
  ```

  `src/test/java/com/company/drools/BaseIntegrationTest.java`:
  ```java
  @SpringBootTest
  @Testcontainers
  public abstract class BaseIntegrationTest {
      // Common integration test setup
  }
  ```

- [ ] **Write First Unit Test**

  `src/test/java/com/company/drools/core/model/RuleTest.java`:
  ```java
  class RuleTest extends BaseUnitTest {

      @Test
      void testRuleCreation() {
          String ruleId = "test.rule";
          String content = "rule content";

          Rule rule = new Rule(ruleId, content);

          assertNotNull(rule);
          assertEquals(ruleId, rule.getRuleId());
          assertEquals(content, rule.getContent());
      }

      @Test
      void testRuleMetadata() {
          Rule rule = new Rule("test.rule", "content");
          RuleMetadata metadata = new RuleMetadata(
              "test.rule",
              "Test Rule",
              "1.0",
              Instant.now()
          );

          rule.setMetadata(metadata);

          assertNotNull(rule.getMetadata());
          assertEquals("Test Rule", rule.getMetadata().getName());
      }
  }
  ```

- [ ] **Run Tests**
  ```bash
  mvn test
  mvn test -Dtest=RuleTest
  ```

**Day 5 (6 hours)**
- [ ] **Write Core Engine Tests**

  Priority test files:
  1. `RuleCompilerTest.java` - Test DRL compilation
  2. `RuleExecutorTest.java` - Test rule execution
  3. `DroolsEngineServiceTest.java` - Test service layer
  4. `LocalLRUCacheTest.java` - Test caching logic

- [ ] **Write Storage Tests**
  1. `InMemoryRuleStorageTest.java` - Test in-memory storage
  2. `RuleIdTransformerTest.java` - Test ID transformation

- [ ] **Verify Test Coverage**
  ```bash
  mvn test jacoco:report
  open target/site/jacoco/index.html
  ```

**Week 1 Deliverables:**
- ✅ Java version fixed
- ✅ Memory diagnostics added
- ✅ Code quality issues resolved
- ✅ Test infrastructure established
- ✅ First batch of unit tests (target: 20-30% coverage)

---

## Week 2: Test Coverage & Performance (5 days)

### Day 6-7: Complete Unit Tests

**Target Coverage: 60%**

**Day 6 (6 hours) - Cache & Storage Tests**
- [ ] `LocalLRUCacheTest.java`
  - Test eviction policy
  - Test thread safety
  - Test statistics tracking
  - Test cache warming

- [ ] `RedisRuleCacheTest.java`
  - Test Redis operations (mock)
  - Test connection failure handling
  - Test TTL configuration

- [ ] `S3RuleStorageTest.java`
  - Test path transformation
  - Test retry logic (mock)
  - Test error handling

**Day 7 (6 hours) - API & Controller Tests**
- [ ] `RuleExecutionControllerTest.java`
  - Test POST /execute-rule
  - Test validation errors
  - Test error responses

- [ ] `AdminControllerTest.java`
  - Test GET /admin/rules
  - Test POST /admin/refresh-rules
  - Test GET /admin/health

- [ ] `GlobalExceptionHandlerTest.java`
  - Test exception mapping
  - Test error response format

**Coverage Check:**
```bash
mvn test jacoco:report
# Target: 60% line coverage
```

---

### Day 8-9: Integration Tests

**Day 8 (6 hours) - S3 & Redis Integration**
- [ ] **S3 Integration Test**

  `S3RuleStorageIntegrationTest.java`:
  ```java
  @SpringBootTest
  @Testcontainers
  class S3RuleStorageIntegrationTest {

      @Container
      static LocalStackContainer localstack = new LocalStackContainer(
          DockerImageName.parse("localstack/localstack:latest")
      ).withServices(S3);

      @Test
      void testUploadAndDownloadRule() {
          // Test actual S3 operations with LocalStack
      }
  }
  ```

- [ ] **Redis Integration Test**

  `RedisRuleCacheIntegrationTest.java`:
  ```java
  @SpringBootTest
  @Testcontainers
  class RedisRuleCacheIntegrationTest {

      @Container
      static GenericContainer redis = new GenericContainer("redis:7-alpine")
          .withExposedPorts(6379);

      @Test
      void testCacheOperations() {
          // Test actual Redis operations
      }
  }
  ```

**Day 9 (6 hours) - API Integration Tests**
- [ ] **End-to-End Rule Execution**

  `RuleExecutionIntegrationTest.java`:
  ```java
  @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
  class RuleExecutionIntegrationTest {

      @Autowired
      TestRestTemplate restTemplate;

      @Test
      void testExecuteSimpleRule() {
          RuleExecutionRequest request = new RuleExecutionRequest(
              "pricing.discount.simple",
              Map.of("amount", 100)
          );

          ResponseEntity<RuleExecutionResponse> response =
              restTemplate.postForEntity(
                  "/execute-rule",
                  request,
                  RuleExecutionResponse.class
              );

          assertEquals(HttpStatus.OK, response.getStatusCode());
          // Verify discount applied
      }
  }
  ```

- [ ] **Rule Refresh Test**
- [ ] **Error Scenario Tests**

**Coverage Target: 80%**

---

### Day 10: Performance Testing

**Morning (3 hours) - Setup JMeter**
- [ ] **Install JMeter**
  ```bash
  brew install jmeter
  # Or download from apache.org
  ```

- [ ] **Create Test Plan**

  `performance-tests/rule-execution-100rps.jmx`:
  - Thread Group: 10 threads, 10 loops/thread, 100ms delay
  - HTTP Request: POST /execute-rule
  - Assertions: Response time < 100ms (P99)
  - Listeners: Summary Report, Response Time Graph

**Afternoon (3 hours) - Run Performance Tests**
- [ ] **Baseline Test (100 RPS)**
  ```bash
  # Start application
  ./setup-dev-environment.sh

  # Run JMeter test
  jmeter -n -t performance-tests/rule-execution-100rps.jmx \
    -l results/100rps-results.jtl \
    -e -o results/100rps-report

  # Open report
  open results/100rps-report/index.html
  ```

- [ ] **Target Test (1000 RPS)**
  - Modify thread group: 100 threads, 10 loops, 10ms delay
  - Run for 5 minutes
  - Monitor memory usage during test

  ```bash
  # Monitor memory
  watch -n 5 'curl -s http://localhost:8081/admin/memory/info | jq'
  ```

- [ ] **Memory Stability Test**
  - Run 100 RPS test for 1 hour
  - Monitor memory growth
  - Verify no OOM errors

**Deliverables:**
- JMeter test plans created
- Performance baseline established
- 100 RPS test: PASS/FAIL
- 1000 RPS test: PASS/FAIL
- Memory stability: PASS/FAIL

**Week 2 Deliverables:**
- ✅ 80% code coverage achieved
- ✅ Integration tests complete
- ✅ Performance validated (100-1000 RPS)
- ✅ Memory stability confirmed

---

## Week 3: Production Hardening (5 days)

### Day 11-12: Monitoring Stack

**Day 11 (6 hours) - Prometheus + Grafana**
- [ ] **Add to docker-compose.yml**
  ```yaml
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - ./monitoring/grafana/dashboards:/etc/grafana/provisioning/dashboards
  ```

- [ ] **Create Prometheus Config**

  `monitoring/prometheus.yml`:
  ```yaml
  global:
    scrape_interval: 15s

  scrape_configs:
    - job_name: 'drools-engine'
      static_configs:
        - targets: ['app:8080']
      metrics_path: '/actuator/prometheus'
  ```

- [ ] **Enable Prometheus Endpoint**

  `application.yml`:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,metrics,prometheus
    metrics:
      export:
        prometheus:
          enabled: true
  ```

**Day 12 (6 hours) - Grafana Dashboards**
- [ ] **Create Dashboard**
  - JVM memory usage
  - Rule execution rate
  - Cache hit/miss ratio
  - Error rate
  - Response time percentiles (P50, P90, P99)

- [ ] **Configure Alerts**
  - Memory usage > 80%
  - Error rate > 5%
  - Response time P99 > 200ms

---

### Day 13: Security Hardening

**Morning (3 hours) - API Authentication**
- [ ] **Add Spring Security**
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
  </dependency>
  ```

- [ ] **Implement API Key Authentication**

  `SecurityConfig.java`:
  ```java
  @Configuration
  @EnableWebSecurity
  public class SecurityConfig {

      @Bean
      public SecurityFilterChain filterChain(HttpSecurity http) {
          return http
              .csrf().disable()
              .authorizeHttpRequests(auth -> auth
                  .requestMatchers("/admin/**").hasRole("ADMIN")
                  .requestMatchers("/execute-rule").hasRole("USER")
                  .anyRequest().authenticated()
              )
              .addFilterBefore(apiKeyFilter(), UsernamePasswordAuthenticationFilter.class)
              .build();
      }
  }
  ```

**Afternoon (3 hours) - Secrets Management**
- [ ] **Enable S3 Encryption**

  Update `S3Config.java`:
  ```java
  s3Client = S3Client.builder()
      .region(Region.of(region))
      .overrideConfiguration(b -> b
          .putAdvancedOption(SdkAdvancedClientOption.SIGNER,
              new AWSS3V4Signer())
      )
      .build();
  ```

- [ ] **Add AWS Secrets Manager Integration**
  ```xml
  <dependency>
      <groupId>com.amazonaws.secretsmanager</groupId>
      <artifactId>aws-secretsmanager-caching-java</artifactId>
  </dependency>
  ```

---

### Day 14: CI/CD Pipeline

**Morning (3 hours) - GitHub Actions**
- [ ] **Create Workflow**

  `.github/workflows/ci.yml`:
  ```yaml
  name: CI/CD Pipeline

  on:
    push:
      branches: [ main, develop ]
    pull_request:
      branches: [ main ]

  jobs:
    build:
      runs-on: ubuntu-latest

      steps:
        - uses: actions/checkout@v3

        - name: Set up JDK 17
          uses: actions/setup-java@v3
          with:
            java-version: '17'
            distribution: 'temurin'

        - name: Build with Maven
          run: mvn clean package

        - name: Run tests
          run: mvn test

        - name: Code coverage
          run: mvn jacoco:report

        - name: Build Docker image
          run: docker build -t drools-engine:${{ github.sha }} .

        - name: Push to ECR (if main branch)
          if: github.ref == 'refs/heads/main'
          run: |
            # Push to AWS ECR
  ```

**Afternoon (3 hours) - Deployment Automation**
- [ ] **Create Deployment Script**

  `scripts/deploy.sh`:
  ```bash
  #!/bin/bash
  set -e

  # Build and tag
  docker build -t drools-engine:latest .
  docker tag drools-engine:latest $ECR_REGISTRY/drools-engine:$VERSION

  # Push to ECR
  aws ecr get-login-password --region $AWS_REGION | \
    docker login --username AWS --password-stdin $ECR_REGISTRY
  docker push $ECR_REGISTRY/drools-engine:$VERSION

  # Deploy to ECS
  aws ecs update-service \
    --cluster drools-cluster \
    --service drools-engine \
    --force-new-deployment
  ```

---

### Day 15: Final Validation & Documentation

**Morning (3 hours) - End-to-End Testing**
- [ ] **Production-like Environment Test**
  ```bash
  # Deploy to staging
  ./scripts/deploy.sh staging

  # Run smoke tests
  ./scripts/smoke-test.sh

  # Run load test
  jmeter -n -t performance-tests/production-test.jmx
  ```

- [ ] **Security Audit**
  ```bash
  # Run OWASP dependency check
  mvn dependency-check:check

  # Run Trivy container scan
  trivy image drools-engine:latest
  ```

**Afternoon (3 hours) - Documentation Update**
- [ ] **Update README.md**
  - Add test coverage badge
  - Add performance benchmarks
  - Update deployment instructions

- [ ] **Update CLAUDE.md**
  - Add test commands
  - Add monitoring access
  - Add troubleshooting for common issues

- [ ] **Create Runbook**

  `docs/operational-runbook.md`:
  - Deployment procedures
  - Rollback procedures
  - Incident response
  - Common issues and solutions

**Week 3 Deliverables:**
- ✅ Monitoring dashboard operational
- ✅ Authentication implemented
- ✅ CI/CD pipeline created
- ✅ Security hardening complete
- ✅ Production deployment validated

---

## 🎯 Success Criteria

### Code Quality
- [ ] 80%+ test coverage
- [ ] All tests passing
- [ ] No critical security vulnerabilities
- [ ] Code formatted with Spotless

### Performance
- [ ] 100 RPS: P99 < 100ms ✅
- [ ] 1000 RPS: P99 < 500ms ✅
- [ ] 1 hour stability test: No OOM ✅
- [ ] Memory usage stable < 1.5GB ✅

### Production Readiness
- [ ] Health checks working
- [ ] Monitoring dashboard operational
- [ ] Alerts configured
- [ ] Authentication enabled
- [ ] CI/CD pipeline automated
- [ ] Deployment runbook complete

### Documentation
- [ ] All docs updated
- [ ] API documentation complete
- [ ] Runbook created
- [ ] Test results documented

---

## 📊 Progress Tracking

Use this checklist to track daily progress:

```markdown
### Week 1
- [ ] Day 1: Environment Setup ✅
- [ ] Day 2: Memory Fixes ✅
- [ ] Day 3: Code Quality ✅
- [ ] Day 4: Test Infrastructure ✅
- [ ] Day 5: Unit Tests (20-30%) ✅

### Week 2
- [ ] Day 6: Unit Tests (40-50%) ✅
- [ ] Day 7: Unit Tests (60-70%) ✅
- [ ] Day 8: Integration Tests ✅
- [ ] Day 9: Integration Tests ✅
- [ ] Day 10: Performance Tests ✅

### Week 3
- [ ] Day 11: Monitoring Setup ✅
- [ ] Day 12: Grafana Dashboards ✅
- [ ] Day 13: Security ✅
- [ ] Day 14: CI/CD ✅
- [ ] Day 15: Final Validation ✅
```

---

## 🚨 Risk Mitigation

### Risk 1: Tests Reveal Critical Bugs
**Mitigation**: Allocate buffer time, prioritize fixes by severity

### Risk 2: Performance Targets Not Met
**Mitigation**: Profile with JProfiler, optimize hot paths, increase resources

### Risk 3: Memory Issues Persist
**Mitigation**: Heap dump analysis, memory profiler, consider KieBase pooling

### Risk 4: Timeline Slips
**Mitigation**: Focus on critical path, defer nice-to-have features

---

## 📞 Support Resources

- **Drools Documentation**: https://docs.drools.org/
- **Spring Boot Docs**: https://spring.io/projects/spring-boot
- **TestContainers**: https://www.testcontainers.org/
- **JMeter Guide**: https://jmeter.apache.org/usermanual/

---

**Ready to begin? Start with Week 1, Day 1! 🚀**

Last Updated: 2026-02-19
