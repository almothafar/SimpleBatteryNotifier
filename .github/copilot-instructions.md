# Copilot instructions — SimpleBatteryNotifier

Android app (single Gradle module `app/`, Java, min SDK 26) that monitors the battery and notifies on low/critical/full levels, temperature, and health.

## PR titles must be Conventional Commits

Write every pull request title as `type: description` — lowercase, imperative, no trailing period:

```
feat: add banana counter to the details table
fix: radiation is missing from bananas
```

Allowed types: `feat`, `fix`, `perf`, `refactor`, `docs`, `build`, `ci`, `chore`, `test`, `style`, `revert`. Use `feat!:` / `fix!:` for breaking changes. PRs are squash-merged, so the PR title becomes the commit message that release-please reads to compute the version bump. A CI check enforces this.

## Versioning

Do not hand-edit version numbers. The version lives in `.release-please-manifest.json`; `app/build.gradle` derives both `versionName` and `versionCode` from it (`versionCode = MAJOR*100000 + MINOR*1000 + PATCH`, so `3.1.55` → `301055`). release-please bumps the manifest when you merge its Release PR. Ship a `feat:`/`fix:` PR and let the pipeline pick the number.

## Conventions

- Use the domain vocabulary defined in `CONTEXT.md` (drain rate, charge rate, design capacity).
- Follow `CODE_REVIEW_GUIDELINES.md` (e.g. no `final` on parameters).
- Every user-facing string needs an Arabic translation or the build fails (`MissingTranslation`).
