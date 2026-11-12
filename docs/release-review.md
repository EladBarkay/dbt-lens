# 1.0.0 project review

This review covers the complete repository from the original main commit `18596be` through
the final 0.1.12 implementation `e2d9fd0`, the five consolidated 0.2.x checkpoints, and the
1.0.0 fixes. It includes all 54 production Kotlin files, 15 test suites, plugin descriptors,
schemas, examples, build configuration, workflows and project documentation. This is a source
and release review; user research, production warehouse tests and a performance benchmark
have not been performed.

## Release consolidation

Each checkpoint is cumulative. Compatible changes were grouped around a usable capability,
while original implementation commits were retained in main's history. All original PRs
#2–#14 and the consolidation PRs #15–#19 were merged after the corresponding checks passed.
The original stacked branches also contain merge commits from reconciling their PRs; use the
immutable source commits or tags when comparing historical code.

| Version | Capability | Original PRs | Consolidation PR | Tag commit |
| --- | --- | --- | --- | --- |
| 0.2.0 | Editor, activation, project macros and structural navigation | #2–#7 | #15 | `de683c1` |
| 0.2.1 | Packages, resource navigation, documentation and YAML | #8–#9 | #16 | `14bf8bf` |
| 0.2.2 | Scoped editing, completion, templates and dispatch | #10–#11 | #17 | `b974745` |
| 0.2.3 | Saved artifacts, previews, documentation and lineage | #12–#13 | #18 | `44692f4` |
| 0.2.4 | Explicit local dbt CLI actions | #14 | #19 | `53def67` |

Annotated `v0.2.0`–`v0.2.4` tags point to those commits. Each tag's build version matches its
name. Consolidation changed versioning and documentation, not feature source. The 1.0.0 PR
contains the reviewed fixes and remains a separate release decision.

## Product and business assessment

The product serves dbt developers who want template assistance in JetBrains IDEs, including
hosts without commercial Database Tools. Its usable workflow is offline editing/navigation,
optional enrichment from saved output, and explicitly requested local compilation. This
ordering keeps a dbt installation, working credentials and warehouse access out of the basic
editor's prerequisites.

The principal product tradeoff is native SQL assistance. The default editor supplies lexical
SQL coloring and Jinja features; the native SQL setting retains JetBrains SQL assistance and
disables those Jinja features in ordinary SQL files. This tradeoff is now visible before
installation instructions. Simultaneous assistance is still experimental and is not a 1.0.0
promise. Static suggestions and dispatch candidates likewise do not claim runtime certainty.

Installation, project activation, saved-output use, command setup and command consequences
are documented as one initial workflow. Missing artifacts do not prevent editor use. Compile
current must identify a resource precisely; a readable failure is preferable to silently
compiling unrelated resources. Macro definitions and compiled invocations have distinct views.

Apache-2.0 is the owner's selected project license. The package includes LICENSE and NOTICE,
preserves the upstream schema license and identifies dbt Lens as an independent project.
The plugin ID changes to `eladbarkay.dev.dbtlens` for Marketplace publication, so earlier `dev.dbt.jinja`
installations must be removed rather than upgraded. The repository has a buildable ZIP and
checked draft-release path; Marketplace distribution, signing, monetization and an adoption
strategy have not been established. No demand, competitive advantage or revenue claim is
inferred from passing tests. The practical next product evidence is feedback from users of
the documented editing modes before expanding expensive adapter/runtime integrations.

Maintenance is deliberately concentrated on one latest release. Historical checkpoints are
comparison points, not five independently supported products. The contribution guide and
security policy document that boundary and reduce the cost of interpreting future changes.

## Architecture, readability and maintainability

The existing package boundaries fit the responsibilities: `lang`/`psi` implement syntax and
scope; `editor` consumes them; `project` discovers roots and indexed names; `resources` and
`dispatch` resolve static project metadata; `artifacts` reads saved output; `cli` plans and
runs explicit commands. Optional SQL, JSON and YAML extensions remain behind separate plugin
descriptors. Minimal-host tests exercise the absence of those dependencies.

The review favored small shared implementations over introducing service interfaces with
one implementation. Literal configuration interpretation is now shared by artifact requests,
macro namespaces/install paths and resource folders. Named declaration scanning is shared
by the macro index, excluded dependencies and snapshot discovery. Generated-directory rules
and bounded byte reads also have single implementations. This removes real duplication
without combining unrelated resolution policies.

Kotlin and Gradle Kotlin scripts use a pinned formatter enforced by `check`; EditorConfig
specifies whitespace. The large formatting diff is mechanical. Source-preserving lexer/parser
behavior, plugin ID, optional-dependency boundaries and source-authoritative navigation remain
intact. Public data snapshots remain separate from PSI and process state. Extension points
are used where optional implementations actually exist, rather than applying SOLID as a
reason to add abstraction everywhere.

The tool-window panel still coordinates several UI concerns. Its ownership, cancellation,
generation checks and background boundaries are now explicit. Splitting it into speculative
controllers would add indirection without a second consumer; future UI features should be
evaluated against the existing separation between formatting, graph slicing and process
planning before enlarging the panel further.

## Findings fixed in 1.0.0

| Finding | Change | Regression evidence |
| --- | --- | --- |
| Quoted keys and `#`/`,` inside literal paths were misinterpreted | Shared quote-aware configuration parser; support literal inline/block lists and tabs | Quoted configuration/artifact-path and literal-path-list tests |
| Duplicate or dynamic configuration could be guessed | Duplicate keys, aliases, anchors and unsupported constructs fail closed | Ambiguous/dynamic configuration tests |
| A file could grow beyond the limit after its initial size check | Count bytes during every read of JSON, generated SQL and environment files | UTF-8 byte-limit and grow-after-open tests |
| JSON permissiveness and duplicate lineage keys could hide malformed input | Gson strict parsing and duplicate parent-map rejection | Lenient JSON and duplicate lineage tests |
| NUL and empty relative-path components were not rejected consistently | Validate those components before filesystem resolution | Malformed path tests, existing traversal/symlink tests |
| Old artifact reads continued after selection changes/disposal | Cancel load/render work and project-owned documentation tasks | Rapid selection/disposal test and existing delayed refresh tests |
| Background PSI reads could hold a blocking read lock | Use cancellable nonblocking read actions; keep JSON outside the lock | Real IDE fixture suites and source-boundary review |
| Compiled-file fallback could swallow cancellation | Catch only input/IO failures at that boundary | Cancellation-aware shared reads and disposal tests |
| Large lineage adjacency sets were sorted/scanned beyond the visible cap | Cap visited nodes and examined adjacency entries; build edges from visible node pairs | Bounded, cyclic, depth-limited and missing-parent lineage tests |
| Incomplete saved identities broadened Compile current to all matching basenames; malformed FQN parts could be dropped | Require a complete validated FQN; reject partial interpretation and explain regeneration/project compile fallback | Missing/ambiguous/malformed FQN rejection and selected/dependency compile fixtures |
| Declaration scans and configuration rules had separate copies | Share the token scanner, configuration parser and generated-directory rules | Existing macro/index/package/snapshot/resource tests |
| Formatting and release qualification were inconsistent | Formatter gate, four host configurations, binary verifier, pinned Actions and checked draft workflow | Formatter, package/structure checks, workflow lint and CI |
| Project licensing and maintenance guidance were missing | Apache-2.0 LICENSE/NOTICE, contribution guide and security policy | Package license inspection and provenance review |

Quoted configuration and malformed artifact inputs were reproduced before fixing them. The complete
baseline suite passed before the changes, showing why those additional regression cases were
needed. The subsequent review found and corrected an escaping error in the new tab-aware key
pattern, ambiguous CLI fallback selection, cancellation gaps and duplicated declaration scans.
The final inspection also bounded traversal through missing lineage IDs and added cancellation
checks to reverse-index/catalog construction. Tests were rerun after those corrections, rather
than relying on the first successful pass.

## CI/CD and supply-chain assessment

The wrapper is upgraded to Gradle 9.3.0, within the pinned compiler's supported range and
with official Java 25 support. Its distribution has a checksum; compiler, platform plugin, formatter and IDE SDKs
are pinned. GitHub Actions reference release tags, except in the secret-holding Marketplace job, which pins
commit SHAs (both updated by Dependabot). Checkout does not persist credentials,
and ordinary checks have read-only repository permissions. Weekly grouped dependency PRs
must pass the same matrix. Tests use isolated process fixtures instead of user credentials.

The matrix runs SQL-enabled, TextMate-only, minimal and free-PyCharm configurations. The
Plugin Verifier checks both pinned IDE binaries in a separate job with both required Java
toolchains installed and explicitly discoverable. Reports and the packaged
baseline ZIP/checksum are retained. Archive order is stable and timestamps are omitted;
environment-specific build metadata means cross-platform byte-identical ZIPs are not claimed.

A version tag reuses the full checks. Only the later draft job receives contents-write access.
It requires a semantic version tag, a commit already in main, the expected versioned ZIP and
a matching checksum before creating a draft. Only publishing that draft starts the separate
Marketplace workflow, which signs and uploads the checked ZIP with secrets scoped to a
`marketplace` environment that accepts only version tags. Its actions are pinned to commit SHAs.

The current private repository plan rejects branch-protection configuration. Consequently,
checks-before-merge remains a documented owner rule; it is not represented as server-enforced
protection. The draft workflow's dependency on checks applies independently of that limitation.
The new tag-to-draft workflow has been statically validated; its first end-to-end execution
will occur on a matching tag after the workflow is merged.

The review follows the platform's guidance on
[background cancellation](https://plugins.jetbrains.com/docs/intellij/background-processes.html),
[PSI threading](https://plugins.jetbrains.com/docs/intellij/threading-model.html),
[Gradle platform dependencies](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html),
and GitHub's guidance on [immutable action references](https://docs.github.com/en/actions/reference/security/secure-use).
Toolchain selection follows the [Gradle Java matrix](https://docs.gradle.org/current/userguide/compatibility.html)
and [Kotlin/Gradle support table](https://kotlinlang.org/docs/gradle-configure-project.html).

## Review closure and qualification

The final pass rechecked syntax recovery, editor scope/identity, project/package discovery,
optional extensions, artifact limits/provenance, CLI argument/environment/output boundaries,
disposal, package contents, workflow permissions and documentation against the implementation.
No further reproducible defect or necessary release fix was found in that reviewed scope.
That is a bounded review conclusion, not a claim that no future improvement is possible.

The exact results and compatibility warnings are recorded in [feasibility.md](feasibility.md).
Full Jinja statement validation, simultaneous SQL/Jinja assistance, resource rename/completion,
dynamic names/dispatch, custom generated-directory exclusions, large-project performance,
interactive user-IDE qualification, Windows/Fusion and external warehouse adapters remain
explicitly deferred. Experimental/deprecated platform APIs remain compatibility watchpoints.
The prior isolated dbt Core/DuckDB smoke results are historical evidence; they are not presented
as a new live-adapter qualification for 1.0.0.
