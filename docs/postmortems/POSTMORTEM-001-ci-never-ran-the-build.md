# POSTMORTEM-001: CI never ran the build, so a service that could not start was reported as done

- **Date found:** 2026-10-13 (service start-up), 2026-10-14 (CI root cause)
- **Severity:** High — every quality gate after Week 1 was ineffective
- **Status:** Fixed; CI result to be confirmed on the first push after the fix

## Summary
`PROGRESS.md` marked Weeks 3–5 complete, yet the inventory service could not start: its Spring context failed, so 43
of its 44 integration tests errored. Nobody noticed because the check that should have caught it — GitHub Actions
running `./mvnw verify` on every push — had never executed a single Maven command. The `mvnw` script was committed
without its executable bit, and every CI run since the first one failed in under a minute with exit code 126.

## Impact
- Three weeks of work were merged to `main` without integration tests ever running in CI or (for Weeks 3–5) locally.
- Several defects that tests would have exposed reached `main` (POSTMORTEM-002, -004, -005).
- Project documentation claimed results that had not been produced (benchmark numbers and two incident reports were
  invented; they have been removed).

## Detection
An end-to-end audit ran the full `./mvnw clean verify` with Docker on 2026-10-13 and saw the context failure. The CI
history then showed every `CI` workflow run as failed; the job annotations gave the cause: `Process completed with exit
code 126` on the *Build and test* step. The only green runs in the Actions tab were Dependabot jobs, which made the
history look less red than it was.

## Root cause (5 whys)
1. *Why could the service not start?* `RedisProductCache` had two constructors and neither was marked for injection,
   so Spring could not instantiate it.
2. *Why was that merged?* The sessions that added it ran only `./mvnw test` (unit tests with in-memory fakes), which
   never builds a Spring context.
3. *Why did CI not catch it?* CI never ran Maven: `git ls-files -s mvnw` showed mode `100644`.
4. *Why did nobody notice CI was red?* The failures were fast and identical, and the Dependabot successes in the same
   list looked like CI passing. No branch protection required the check.
5. *Why was the file not executable?* It was created on Windows, where Git does not record the executable bit by
   default (`core.filemode=false`).

## Fix
- `git update-index --chmod=+x mvnw` (mode `100755`).
- One constructor in `RedisProductCache`; all 48 inventory integration tests pass.
- CI jobs pinned to `ubuntu-24.04`, and a Docker build + compose smoke job added, so the deployed artefact is tested too.

## Prevention
- The session protocol in `CLAUDE.md` requires `./mvnw verify` (not `test`) before marking any task done, and a check of
  the CI job's step results after each push.
- Recommended to the owner: enable branch protection on `main` requiring the *Build and test* check.
- Integration tests use the same `@InventoryIntegrationTest` annotation, so a context that cannot start fails loudly.

## Lessons
A green unit-test run says nothing about whether the application starts. A CI pipeline needs its own verification:
look at what a run actually executed, not at the colour of the list.
