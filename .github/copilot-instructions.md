# Copilot instructions — SimpleBatteryNotifier

Android app (single Gradle module `app/`, Java, min SDK 26) that monitors the battery and notifies
on low/critical/full levels, temperature, and health.

## PR titles must be Conventional Commits

Write every pull request title as `type: description` — lowercase, imperative, no trailing period:

```
feat: add banana counter to the details table
fix: radiation is missing from bananas
```

Allowed types: `feat`, `fix`, `perf`, `refactor`, `docs`, `build`, `ci`, `chore`, `test`,
`style`, `revert`. Use `feat!:` / `fix!:` for breaking changes. PRs are squash-merged, so the PR
title becomes the commit message that release-please reads to compute the version bump. A CI check
enforces this.

## Versioning

Do not hand-edit version numbers. `versionName` / `versionCode` are derived from the latest
`v<semver>` git tag in `app/build.gradle`; release-please creates the tags. Ship a `feat:`/`fix:`
PR and let the pipeline version it.

## Conventions

- Use the domain vocabulary defined in `CONTEXT.md` (drain rate, charge rate, design capacity).
- Follow `CODE_REVIEW_GUIDELINES.md` (e.g. no `final` on parameters).
- Every user-facing string needs an Arabic translation or the build fails (`MissingTranslation`).
