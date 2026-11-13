# Contributing

Thanks for helping improve DBT Lens. Bug reports, ideas and pull requests are all welcome.

- **Bugs and ideas**: open an [issue](https://github.com/EladBarkay/dbt-lens/issues) with your IDE
  version, plugin version and a minimal example.
- **Security problems**: please don't open a public issue; see [SECURITY.md](SECURITY.md).

## Setup

You need **JDK 21**. No dbt installation is needed; the CLI tests use `/usr/bin/python3` fixtures on
Unix and never touch a warehouse. The first build downloads the IntelliJ SDK and its JetBrains Runtime.

```sh
./gradlew check buildPlugin     # formatting, static analysis, tests and the plugin ZIP
./gradlew runIde                # start a sandbox IDE with the plugin installed
./gradlew ktfmtFormat           # apply the project's Kotlin style
pre-commit install              # run the commit hooks automatically (one-time)
```

The ZIP is written to `build/distributions/`. The Gradle wrapper, Kotlin compiler, IntelliJ Platform
plugin and formatter versions are pinned in the repository.

## Quality checks

| Check | Runs in | What it enforces |
| --- | --- | --- |
| [ktfmt](https://github.com/facebook/ktfmt) | `./gradlew check`, commit hook | Kotlin formatting (Kotlin-lang style) |
| [detekt](https://detekt.dev) | `./gradlew check` | Static analysis. Existing findings are in `config/detekt-baseline.xml`; new code may not add any |
| Kotlin compiler | every build | Warnings fail the build |
| [pre-commit](https://pre-commit.com) | commit hook, CI `lint` job | Whitespace and file hygiene, YAML/JSON/XML syntax, private keys and secrets (gitleaks), workflow lint (actionlint) |
| [Kover](https://github.com/Kotlin/kotlinx-kover) | CI `sql` job, `./gradlew koverHtmlReport` | Test coverage report (on the run summary and as an artifact); informational, not a gate |
| [Qodana](https://www.jetbrains.com/qodana/) | CI `qodana` job | JetBrains IDE inspections on changed code; reports findings on the PR, not a required check |

Install pre-commit with `pipx install pre-commit` (or `pip install pre-commit`), then run
`pre-commit install` once in the repository. `pre-commit run --all-files` checks everything on demand.
Agents working in this repository should also follow [AGENTS.md](AGENTS.md).

## Test matrix

CI runs four IDE configurations and a binary compatibility check on every pull request. To reproduce
them locally:

| Configuration | Command |
| --- | --- |
| IntelliJ with Database Tools | `./gradlew check -PwithSqlTests=true` |
| TextMate only | `./gradlew check` |
| Minimal host (no SQL, TextMate, JSON or YAML) | `./gradlew check -PwithTextmateTests=false -PwithYamlTests=false -PfreeEditionTests=true` |
| Free PyCharm (needs Java 25) | `./gradlew check -PplatformType=PY -PplatformVersion=2026.2.3 -PfreeEditionTests=true` |
| Plugin Verifier, both IDEs | `./gradlew verifyPlugin -PverifySupportedIdes=true` |

The verifier needs both Java 21 and Java 25. If Gradle can't find them, pass
`-Porg.gradle.java.installations.paths=/path/to/jdk21,/path/to/jdk25`.

### Useful properties

| Property | Effect |
| --- | --- |
| `-PlocalPlatformPath=/path/to/IDE.app/Contents` | Build against an installed IDE instead of downloading one |
| `-PlocalRuntimePath=/path/to/jdk` | Use a local runtime instead of downloading the JetBrains Runtime |
| `-PverificationIdePath=/path/to/IDE.app/Contents` | Also verify against an installed IDE |
| `-PverifierOffline=true` | Verify using only already-downloaded dependencies |
| `-PexperimentalSql=true` | Enable the experimental native SQL integration in `runIde` |

Results against an SDK outside the CI matrix are experiments, not compatibility claims.

## Design principles

- **Never execute project code for editor features.** CLI commands are explicit, cancellable argument
  vectors, never shell strings.
- **Be conservative.** Leave dynamic values unresolved and show every duplicate candidate instead of
  picking one.
- **Stay optional.** Core editing must load without SQL, TextMate, JSON or YAML. Optional APIs belong
  behind their descriptor bridges, with coverage in the minimal-host suite.
- **Bound the work.** Keep the lexer and parser source-preserving and restartable, cap scans and
  artifact reads, and check for cancellation in work that grows with project size.
- **Respect threading.** Read PSI in cancellable read actions; parse artifact JSON off the UI thread
  and outside the read lock. Dispose listeners and background work with their owner.
- **Test behavior.** Add a regression test for each observable bug. Prefer a small shared helper over
  repeated logic, and skip abstractions without a concrete use.

## Pull requests

1. Branch from `main` and keep the change focused.
2. Run `./gradlew ktfmtFormat check` (and `pre-commit run --all-files` if hooks aren't installed) before pushing.
3. Add an entry to `CHANGELOG.md` for user-visible changes.
4. All CI checks must pass before merging; `main` is protected and enforces this.

## Releasing

1. Update `version` in `build.gradle.kts` and add a matching `## X.Y.Z` section to `CHANGELOG.md`.
   That section becomes the Marketplace change notes.
2. Merge the release PR, then tag that commit `vX.Y.Z` and push the tag.
3. CI rebuilds, verifies and creates a **draft** GitHub release with the ZIP and its SHA-256 checksum.
4. Review the draft, then publish it. After you approve the `marketplace` deployment, the workflow signs
   the same ZIP and uploads it to the JetBrains Marketplace. Mark the release as a pre-release to target
   the `eap` channel instead. Version tags can't be moved or deleted once pushed.

### Marketplace setup

The publish job uses secrets stored in the `marketplace` GitHub environment, which only accepts runs
from `v*.*.*` tags and waits for a maintainer's approval before it starts. Set each one with `gh secret set NAME --env marketplace`:

| Secret | Value |
| --- | --- |
| `CERTIFICATE_CHAIN` | PEM certificate chain (`chain.crt`) |
| `PRIVATE_KEY` | Encrypted PEM private key (`private.pem`) |
| `PRIVATE_KEY_PASSWORD` | The private key's password |
| `PUBLISH_TOKEN` | A Marketplace token from **My Tokens** |

A self-signed certificate is enough; the Marketplace adds its own signature. Generate the key outside
the repository:

```sh
openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

The first version of a plugin must be uploaded by hand on the Marketplace website and approved by
JetBrains before the workflow can publish updates. The plugin ID can't change after that upload.

Because the publish job holds these secrets, its actions are pinned to full commit SHAs; the other
workflows use release tags. Dependabot keeps both up to date.
