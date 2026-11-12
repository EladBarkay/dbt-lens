<div align="center">

<img src="src/main/resources/META-INF/pluginIcon.svg" width="72" alt="DBT Lens icon">

# DBT Lens

**Jinja-aware editing for dbt projects in IntelliJ IDEA and PyCharm.**

[![Plugin checks](https://github.com/EladBarkay/dbt-lens/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/EladBarkay/dbt-lens/actions/workflows/build.yml)
[![Version](https://img.shields.io/badge/version-1.0.2-blue)](CHANGELOG.md)
[![License](https://img.shields.io/github/license/EladBarkay/dbt-lens)](LICENSE)
[![IDE](https://img.shields.io/badge/IntelliJ%20IDEA%20%7C%20PyCharm-2025.3%2B-000?logo=jetbrains)](#compatibility)
[![Stars](https://img.shields.io/github/stars/EladBarkay/dbt-lens?style=flat)](https://github.com/EladBarkay/dbt-lens/stargazers)
[![Contributors](https://img.shields.io/github/contributors/EladBarkay/dbt-lens)](https://github.com/EladBarkay/dbt-lens/graphs/contributors)

</div>

DBT Lens turns the `.sql` files in a dbt project into templates the IDE understands: Jinja is parsed,
highlighted and navigable, macros and resources resolve across your project and installed packages,
and saved dbt output (compiled SQL, docs, lineage) is one click away. Everything works offline.
Editor features never run dbt or your project's code.

## Features

| | |
| --- | --- |
| **Jinja editing** | Highlighting, folding, brace matching, error recovery, block templates and auto-closed `{% %}` / `{{ }}` / `{# #}` pairs. |
| **Smart completion** | Scope-aware variables, macro signatures with keyword labels, filters/tests, and dbt objects such as `api.Relation`. |
| **Navigation** | Go to Declaration and Find Usages for macros, `ref()`, `source()`, versioned models and closing tags, including installed packages. |
| **Adapter dispatch** | Jump from `adapter.dispatch(...)` to every `default__` / `snowflake__` / `postgres__` implementation. |
| **YAML assistance** | Bundled official schemas for `dbt_project.yml`, `profiles.yml`, `packages.yml`, selectors and property files. |
| **Saved output** | A **DBT Lens** tool window with compiled SQL, resource documentation and upstream/downstream lineage. |
| **dbt commands** | Explicit **Generate manifest**, **Compile current** and **Compile project** actions, plus macro invocation with arguments. |

See the [user guide](docs/user-guide.md) for details and limits of each feature.

## Install

1. Download `dbt-lens-<version>.zip` from the [latest release](https://github.com/EladBarkay/dbt-lens/releases).
2. In the IDE, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**, choose the ZIP and restart.

> [!NOTE]
> Builds before 1.0.0 used the plugin ID `dev.dbt.jinja`. Uninstall that version first; the IDE treats
> the two IDs as separate plugins.

## Quick start

1. Open a project that contains `dbt_project.yml`, then open any model's `.sql` file. Jinja assistance
   turns on automatically.
2. Use completion, **Go to Declaration** (⌘B / Ctrl+B) and **Quick Documentation** (F1 / Ctrl+Q) as usual.
3. Open **View → Tool Windows → DBT Lens** to see compiled SQL, documentation and lineage from an existing
   `target/manifest.json`. No dbt installation is needed for this.
4. To run dbt from the IDE, set the executable (and optional profiles/target) under
   **Settings → Languages & Frameworks → DBT Lens**, then use **Generate manifest** or **Compile**.

> [!TIP]
> Prefer JetBrains' native SQL dialect support? With Database Tools installed, enable **Keep the native
> SQL editor in dbt projects** in the settings. Jinja features are then limited to `.jinja`, `.j2` and `.dbt.sql` files.

## Compatibility

| | |
| --- | --- |
| **IDEs** | IntelliJ IDEA and PyCharm (including the free edition), build 253 (2025.3) and newer |
| **Tested in CI** | IntelliJ IDEA 2025.3.3 and PyCharm 2026.2.3, plus binary verification |
| **Optional IDE plugins** | Database Tools, TextMate, JSON and YAML; core editing works without any of them |
| **dbt** | Not needed for editing. Commands use your local dbt Core 1.5+; manifests v8–v12 are read |

## Privacy and safety

Editor features only read files. They don't evaluate Jinja, run dbt, query a warehouse or send
telemetry. dbt commands run only when you click an action, using your local executable, and
compilation may query your configured warehouse. See [SECURITY.md](SECURITY.md).

## Contributing

Bug reports and pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers building, the test
matrix and the release process. Release notes are in [CHANGELOG.md](CHANGELOG.md).

## License

[Apache-2.0](LICENSE). Bundled dbt JSON schemas keep their upstream license; see [NOTICE](NOTICE).

DBT Lens is an independent project and is not affiliated with or endorsed by dbt Labs or JetBrains.
