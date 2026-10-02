# dbt Jinja for JetBrains

An early, installable editor prototype for the production-plugin design. This repository implements
the first parsing/editor milestone and the mixed-language feasibility experiment. It is not the full
dbt project-intelligence release.

## What works in this development build

- Source-preserving Jinja tokens, whitespace control, comments, raw blocks and restartable lexing.
- Nested template blocks and macro declarations with parameter names/default expressions.
- Expression PSI for names, strings, numbers, calls, keyword arguments, attributes, indexing,
  collections, arithmetic/comparison operators, filters and tests.
- Error recovery at subsequent Jinja tags, missing block/delimiter diagnostics and brace matching.
- Independent Jinja highlighting and basic SQL keyword/string/comment coloring in template text.
- Completion for dbt context names, file-local macros and the enclosing macro's parameters.
- Go to Declaration and Find Usages for statically identified unqualified calls to top-level macros
  in the same file. Duplicate declarations return multiple candidates. Qualified names, filters,
  parameter-shadowed names and discoverable statement bindings are excluded from this resolver.

Statements other than macro declarations currently retain a balanced token structure; they do not
have complete statement-specific grammar or lexical-scope semantics. This is a Jinja editing subset,
not runtime equivalence. Conditional expressions, call argument unpacking, slice syntax and extension
semantics still need coverage. SQL coloring is lexical, without dialect-specific assistance; SQL strings
and comments crossing a Jinja hole are not yet context-aware.

## Open a template

The plugin associates `*.dbt.sql`, `*.jinja` and `*.j2`. Open
[examples/local-macros.dbt.sql](examples/local-macros.dbt.sql) to try completion, navigation and usages.
Ordinary `*.sql` files are not automatically reassigned. For a model in an existing project, use the
IDE's **Override File Type** action and choose **dbt Jinja** for that file.

## Build and test

Requires a **JDK 21** and network access for the first build. The Gradle wrapper is pinned to 9.0.0
with a distribution checksum; Kotlin is 2.2.21 and the IntelliJ Platform Gradle plugin is 2.19.0.
The default SDK is IntelliJ IDEA 2025.3.3. No Python interpreter or dbt installation is needed.

```sh
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure buildPlugin
```

For binary compatibility and API checks against the same SDK, run `./gradlew verifyPlugin`.
Use `-PverifierOffline=true` to restrict the verifier to already downloaded dependencies; the validation
record describes the limits of the offline result.

The installation ZIP appears in `build/distributions/`. Use **Settings → Plugins → Install Plugin
from Disk** to install it in a compatible IDE, or `./gradlew runIde` to start a separate development IDE.
The proposed compatibility range in the descriptor is builds 253–261. Only versions actually exercised
in [docs/feasibility.md](docs/feasibility.md) are validated; this development build is not published.

To use an installed SDK and runtime, supply paths explicitly (on macOS, SDK paths end in `Contents`):

```sh
./gradlew test buildPlugin \
  -PlocalPlatformPath='/path/to/IDE.app/Contents' \
  -PlocalRuntimePath='/path/to/jdk/Contents/Home'
```

An SDK outside the proposed range is a development experiment, not a compatibility claim.

## SQL feasibility experiment

The database plugin is optional. Without it, all baseline editing features still load. With it, the
default remains the same lexical SQL fallback. To exercise the real bundled SQL parser:

```sh
./gradlew test -PwithSqlTests=true --rerun-tasks
```

To inspect the experimental PostgreSQL two-root provider in the sandbox, run
`./gradlew runIde -PexperimentalSql=true`. Do not enable this for ordinary work yet:
template removal can produce misleading SQL diagnostics and generated identifiers cannot be inferred.
No SQL completion, formatting, navigation or dialect fidelity is claimed by this prototype. Snowflake
integration is deferred.

## Deferred work

Project-root discovery and ordinary SQL substitution; project/package symbol indexes; model/source/YAML
navigation; manifest ingestion; complete scope-aware resolution; resource completion; documentation and
parameter information; argument/resource inspections; safe rename; compiled SQL previews and dbt execution.
Rename is explicitly rejected until the project-wide reference model exists. No project code or dbt command
is executed to provide editor features.

See [docs/feasibility.md](docs/feasibility.md) for the integration gate and the next milestones.
