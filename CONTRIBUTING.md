# Contributing

Use Java 21 for the IntelliJ IDEA 2025.3.3 baseline. PyCharm 2026.2.3 requires Java 25 for its
SDK/runtime; the plugin's bytecode and Kotlin language/API levels stay compatible with the baseline.
The wrapper, compiler, IntelliJ Platform plugin and formatter are pinned in the repository.

## Development checks

```sh
./gradlew ktfmtFormat
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure buildPlugin -PwithSqlTests=true
./gradlew check -PwithSqlTests=false -PwithTextmateTests=false -PwithYamlTests=false -PfreeEditionTests=true
./gradlew check -PplatformType=PY -PplatformVersion=2026.2.3 -PfreeEditionTests=true
./gradlew verifyPlugin -PverifySupportedIdes=true
```

The CI matrix also tests the TextMate host with SQL disabled. Binary verification has its own job
with Java 21 for baseline compilation and Java 25 for verification; both toolchain paths are explicit.
Local SDK/runtime overrides and offline verifier limits are documented in README.md and
`docs/feasibility.md`. CLI fixtures use Python 3 on Unix and do not query a warehouse.
Do not present fixture results as Windows, Fusion or live-adapter qualification.

## Design constraints

- Keep the lexer/parser source-preserving and restartable. Bound nesting, scanning and artifact reads;
  check cancellation during work that can grow with project size.
- Share configuration, declaration and scope rules between editor features. Keep dynamic values
  unresolved and retain duplicate navigation candidates instead of selecting an arbitrary result.
- Core editor code must load without SQL, TextMate, JSON or YAML. Optional language APIs belong behind
  their descriptor/extension bridges. Add coverage to the minimal-host suite when changing a boundary.
- Read PSI under cancellable read actions. Read artifact JSON outside the read lock and UI thread.
  Dispose editors, listeners and background work with their owning panel or project service.
- Editor assistance never executes dbt or project code. CLI commands are explicit, cancellable argument
  vectors; preserve their environment/logging boundaries and exact resource selection.
- Add regression tests for observable bugs and meaningful behavior. Prefer a small shared helper to
  repeated logic; avoid interfaces and abstractions that have no concrete use.

## Release process

1. Update the single version in `build.gradle.kts`, installation references and CHANGELOG.md.
2. Open a PR to main. Require all four editor configurations and Plugin Verifier to pass on its head.
3. Merge the reviewed PR, then create a matching `vX.Y.Z` tag on that commit.
4. The tag rebuilds and verifies the source and creates a draft with its ZIP and checksum.
5. Review the draft and qualification limits before publishing. Marketplace publication and signing
   need a separate setup; no Marketplace token is used by this repository's workflows.

The current private repository plan does not expose branch protection. Maintain the checks-before-merge
rule manually unless repository permissions/plan are changed; the draft-release workflow still enforces
its checks regardless of branch protection. Dependency update PRs require the same validation.
