# Checklist: Fix CI S3-integration-test checksum failure

**Plan:** [`ci-s3-checksum-fix-plan.md`](ci-s3-checksum-fix-plan.md) · **Created:** 2026-08-20

- [x] Write plan + checklist to `.ai-workspace/project-plans/`
- [x] `S3StorageIntegrationTest` client: `requestChecksumCalculation(WHEN_REQUIRED)` + `responseChecksumValidation(WHEN_REQUIRED)` + imports
- [x] `S3Config` LocalStack/endpoint branch: same two calls (prod path unchanged)
- [x] Verified `S3BaseClientBuilder` has both methods (javap against sdk 2.34.0)
- [x] Docker `mvn -B clean spotless:apply verify` → **BUILD SUCCESS** (new SDK API compiles in main + test; unit suite + gates green)
- [ ] (CI) push → integration-test job passes — **can't run Testcontainers locally on macOS; confirmed by the CI re-run**
