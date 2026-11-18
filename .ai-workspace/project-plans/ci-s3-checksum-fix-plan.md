# Plan: Fix the CI integration-test failure (S3 + LocalStack checksum)

**Created:** 2026-08-20 · **Branch:** `re-review-fable-1`
**Checklist:** [`ci-s3-checksum-fix-checklist.md`](ci-s3-checksum-fix-checklist.md)

## Context
The GitHub Actions integration-test job fails: `S3StorageIntegrationTest` errors on every
`saveRule`/`putObject`:
```
InvalidRequestException: Value for x-amz-checksum-crc32 header is invalid. (Status Code: 400)
  at S3RuleStorage.saveRule → DefaultS3Client.putObject
```
Root cause: **AWS SDK v2 (2.34.0) sends a default CRC32 request checksum on uploads; LocalStack 2.3
rejects it (400).** Introduced by this session's **S8** change, which pinned the IT's LocalStack image
`:latest` → `:2.3` (to match compose). `createBucket` works (no body checksum); only `putObject`
fails. `testConcurrentOperations` "expected 0 but was 20" = all 20 saves failed for the same reason.

The IT builds its **own** `S3Client` (`S3StorageIntegrationTest.java:53-62`), not the `S3Config` bean.

## Fix
Disable the default request checksum on the LocalStack-facing S3 clients (SDK 2.34.0 has
`software.amazon.awssdk.core.checksums.RequestChecksumCalculation` — confirmed present in the jar):

1. `S3StorageIntegrationTest` `setupS3` client builder — add
   `.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)` and
   `.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)` + imports.
2. `S3Config` — same two calls inside the existing `if (StringUtils.hasText(endpoint))` (LocalStack/
   custom-endpoint) branch only. Real AWS S3 (no endpoint override) keeps default checksums.

Keeps the reproducible `localstack:2.3` pin; no compose change. Deterministic and version-independent
(no LocalStack-version guessing).

## Files
- `src/test/java/com/company/drools/integration/S3StorageIntegrationTest.java`
- `src/main/java/com/company/drools/config/S3Config.java`

## Verification
- Docker `mvn -B clean spotless:apply verify` → BUILD SUCCESS (new SDK API compiles in main + test;
  unit suite + gates green).
- Full IT runtime can't run locally (Testcontainers needs a real Docker daemon; macOS dev loop blocks
  it — container-Maven can't reach Docker, host-Maven lacks Java 25). Validated by the CI re-run;
  `WHEN_REQUIRED` stops the SDK sending `x-amz-checksum-crc32`, the exact header LocalStack 2.3 rejected.
