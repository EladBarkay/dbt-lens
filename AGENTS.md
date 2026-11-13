# Agent guide

DBT Lens is a JetBrains IDE plugin (Kotlin, IntelliJ Platform) that adds Jinja-aware editing,
navigation, documentation and saved-output views for dbt projects. Read [CONTRIBUTING.md](CONTRIBUTING.md)
for the full design principles; this file lists what an agent must know before changing code.

## Commands

```sh
./gradlew check buildPlugin        # ktfmt, detekt, all tests and the plugin ZIP; run before every PR
./gradlew ktfmtFormat              # fix Kotlin formatting
./gradlew test --tests '<Class>'   # run one test class
./gradlew check -PwithSqlTests=true  # include the Database Tools suite
pre-commit run --all-files         # hygiene, secret, workflow and formatting hooks
```

JDK 21 is required. The first build downloads the IntelliJ SDK. Other CI configurations and Gradle
properties are listed in CONTRIBUTING.md under "Test matrix".

## Layout

- `src/main/kotlin/dev/dbt/intellij/`: `lang` (lexer, parser, language), `psi`, `editor` (highlighting,
  completion, folding, documentation, typing), `project` (dbt project detection, macro index),
  `resources` and `dispatch` (navigation), `semantic`, `artifacts` (tool window, saved output),
  `cli` (dbt commands), `config` (settings, YAML schemas), `sql` (optional Database Tools bridge), `io`.
- `src/main/resources/META-INF/plugin.xml`: core descriptor. `dbt-sql.xml`, `dbt-json.xml` and
  `dbt-yaml.xml` load only when those optional plugins are present.
- `src/test/kotlin/dev/dbt/intellij/`: platform test suites, one per feature area.

## Rules

- Editor features must never run dbt, Python or project code, or touch the network.
  dbt commands run only from explicit actions, as argument vectors, never through a shell.
- Core code must load without Database Tools, TextMate, JSON or YAML. Optional APIs belong
  behind their descriptor; extend the minimal-host test suite when changing that boundary.
- Read PSI in cancellable read actions; do file and JSON work off the UI thread; bound every scan.
- Leave dynamic values unresolved and return every duplicate candidate rather than guessing.
- Add a regression test for each bug fix. Keep helpers small; no abstractions without two users.

## Quality gates

- Kotlin warnings are errors. Don't suppress a warning to make the build pass; fix its cause.
- detekt must not report new findings. Never add entries to `config/detekt-baseline.xml`;
  only regenerate it (`./gradlew detektBaseline`) after removing findings.
- Formatting is ktfmt's Kotlin-lang style; don't hand-format against it.
- Numbers live in named constants. Put limits and layout values in the area's object
  (`SourceLimits`, `ArtifactLimits`, `ArtifactLayout`, `CommandLimits`); grammar facts stay next
  to their code. detekt's `MagicNumber` reports literals anywhere else.
- Use `@Suppress` only for a deliberate exception, with a one-line reason above it (today: the three
  error-boundary `catch (e: Exception)` blocks). If a rule needs suppressing everywhere, change
  `config/detekt.yml` instead.

## Don't change without the maintainer

- The plugin ID `eladbarkay.dev.dbtlens` (permanent on the Marketplace).
- Third-party files kept verbatim: `LICENSE`, `src/main/resources/schemas/`, the dbt icons
  `icons/dbt.svg` and `icons/dbt-sql.svg` (see NOTICE).
- `.github/workflows/marketplace.yml`: its actions are pinned to commit SHAs on purpose.
- Version tags and releases: tags are protected and publishing signs and uploads to the Marketplace.

## Pull requests

- Branch from `main`; all CI checks must pass (`main` is protected).
- Add a `CHANGELOG.md` entry for user-visible changes. A release needs a `## X.Y.Z` section matching
  `version` in `build.gradle.kts`, or the build fails.
- Never commit credentials, signing keys or machine-specific paths.
