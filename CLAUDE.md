# SimpleBatteryNotifier — agent guide

Android app that monitors the device battery and notifies on low/critical/full levels, high
temperature, and battery health. Single-module Gradle project (`app/`), Java, min SDK 26.

## Commit & PR title convention (required)

Every PR title **must** follow [Conventional Commits](https://www.conventionalcommits.org):
`type: description`, lowercase, imperative, no trailing period.

```
feat: add banana counter to the details table
fix: radiation is missing from bananas
refactor: split NotificationService into channels / dispatch
```

Allowed types: `feat`, `fix`, `perf`, `refactor`, `docs`, `build`, `ci`, `chore`, `test`,
`style`, `revert`. `feat` and `fix` drive version bumps; the rest land in history without one.
A breaking change is marked `feat!:` / `fix!:` or a `BREAKING CHANGE:` footer.

PRs are **squash-merged**, so the PR title becomes the commit on `master` — that is the text
release-please reads. A CI check (`PR Title`) blocks non-conforming titles. Individual commit
messages on a branch don't matter; the PR title is what counts.

## Versioning — do not hand-edit

`versionName` / `versionCode` are **derived at build time** from the latest `v<semver>` git tag
in `app/build.gradle` (via `git describe`). There is no version number stored in source.

- On a release commit: `versionName = 3.0.0`, `versionCode = 3000000`.
- Between releases: `versionName = 3.0.0-<n>-g<sha>` (distinct per build).
- `versionCode = MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + commitsSinceTag`.

Releases are cut by **release-please**: merge normal PRs → it maintains a release PR that bumps
the version + `CHANGELOG.md` → merging that PR creates the `v<semver>` tag and GitHub Release.
Never bump a version by editing a file; write a `feat:`/`fix:` PR and let the pipeline tag it.

## Reference

- `CONTEXT.md` — glossary of domain terms (drain rate, charge rate, design capacity, …). Use these
  exact terms in UI and code.
- `CODE_REVIEW_GUIDELINES.md` — coding standards (e.g. no `final` on parameters) and review checklist.
- Every user-facing string needs an Arabic translation — the build fails on `MissingTranslation`.
