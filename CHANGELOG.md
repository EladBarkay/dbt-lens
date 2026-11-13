# Changelog

All notable changes to DBT Lens are listed here. Versions follow [semantic versioning](https://semver.org).

## 1.0.3 — Saved output fix

- Saved output no longer reports every resource as changed. dbt strips a file's final newline when it
  saves the source, so files ending with a newline were always flagged as outdated.

## 1.0.2 — Listing and naming polish

- Every name shown in the IDE now reads **DBT Lens**: the settings and color scheme pages, the tool
  window, YAML schema names and messages. The tool window returns to its default position once;
  saved settings are kept.
- New Marketplace description listing the plugin's features.
- Releases are signed with the project's own key and published to the Marketplace automatically.

## 1.0.1 — Listing and naming polish (GitHub only)

- The same plugin changes as 1.0.2. Its automatic Marketplace upload failed, so these changes reach
  the Marketplace in 1.0.2.

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
