# Changelog

All notable changes to DBT Lens are listed here. Versions follow [semantic versioning](https://semver.org).

## 1.0.0 — First Marketplace release

- New plugin name and ID: **DBT Lens** (`eladbarkay.dev.dbtlens`). Uninstall earlier builds that used
  `dev.dbt.jinja` before installing this version.
- Supports IntelliJ IDEA and PyCharm 2025.3 and newer, including PyCharm 2026.3.
- More reliable `dbt_project.yml` parsing: quoted keys, tabs, comments and quoted path lists are
  handled, and ambiguous or dynamic configuration is left unresolved instead of guessed.
- **Compile current** now refuses to guess when a resource can't be identified exactly, and suggests
  regenerating the manifest or compiling the project instead.
- Stricter, size-limited artifact reading that rejects malformed paths and duplicate lineage entries,
  and cancels work for closed panels.
- New plugin icon and Marketplace listing.

## 0.2.4 — Local dbt compilation

- **Generate manifest**, **Compile current** and **Compile project** actions, with cancellation.
- Compile a macro invocation with arguments using `dbt compile --inline`.

## 0.2.3 — Saved artifacts

- **dbt Lens** tool window with compiled SQL previews, resource documentation and lineage.
- Size-limited manifest and catalog loading, with documentation styled like the IDE.

## 0.2.2 — Smart editing and dispatch

- Scope-aware local usages and member, filter and keyword-argument completion.
- Live block templates and auto-closing of Jinja blocks.
- Navigation between `adapter.dispatch` calls and their implementations.

## 0.2.1 — Project and YAML navigation

- Navigation into installed packages and macro documentation on hover.
- Bundled YAML schemas and dbt file icons.
- Go to Declaration for `ref`, `source` and versioned models.

## 0.2.0 — Editor foundations

- Source-preserving Jinja parser with highlighting, completion and local macro navigation.
- Automatic activation for `.sql` files in dbt projects, with or without Database Tools.
- Project-wide macro navigation and usages, folding, and navigation between block tags.
