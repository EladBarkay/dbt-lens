# Changelog

## 1.0.0 — Reviewed release

- Fix literal configuration parsing for quoted keys, tabs, comments and quoted path lists;
  reject duplicate, dynamic and ambiguous configuration instead of guessing.
- Enforce file limits during reads, parse artifact JSON strictly and reject malformed paths
  and duplicate lineage entries. Cancel obsolete/disposed artifact work.
- Keep Compile current precise: incomplete or ambiguous saved identities require manifest
  regeneration or Compile project, rather than selecting every matching filename.
- Share declaration scanning and generated-directory rules; apply pinned Kotlin formatting.
- Add minimal-host and free-PyCharm CI, binary compatibility verification, pinned Actions,
  dependency updates, checksummed draft releases, Apache-2.0 licensing and maintenance guidance.
- Upgrade the checked Gradle wrapper to support Java 25 and explicitly provide both verification toolchains.
- Add Marketplace listing metadata and a signed publication workflow for reviewed releases.
- Rename the plugin to DBT Lens with the Marketplace ID `eladbarkay.dev.dbtlens`. Uninstall builds
  that used the previous `dev.dbt.jinja` ID before installing this version.
- Reference GitHub Actions by release tag instead of commit SHA.

## 0.2.4 — Local dbt compilation

Consolidates original PRs #14.

Explicit cancellable local manifest generation, current/project compilation and argument-aware macro invocation.

## 0.2.3 — Saved artifacts

Consolidates original PRs #12–#13.

Bounded manifest/catalog loading, compiled SQL previews, documentation and lineage, including native documentation styling.

## 0.2.2 — Smart editing and dispatch

Consolidates original PRs #10–#11.

Scoped local usages, member/filter/keyword completion, live block templates, Enter crash regression and adapter dispatch navigation.

## 0.2.1 — Project and YAML navigation

Consolidates original PRs #8–#9.

Installed package navigation, macro documentation, YAML schemas and icons, and static ref/source/version navigation.

## 0.2.0 — Editor foundations

Consolidates the original 0.1.0–0.1.5 development stack (PRs #2–#7).

- Source-preserving Jinja parser, highlighting, completion and local macro navigation.
- Automatic dbt SQL activation with Database Tools, TextMate and free PyCharm hosts.
- Project macro navigation and usages, structural/comment folding and enclosure navigation.
- dbt Lens branding with the existing plugin ID retained for upgrades.

The historical 0.1.x branches remain available for comparison. Versioned 0.2.x
checkpoints consolidate compatible changes; publication is a separate operation.
