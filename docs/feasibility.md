# First development milestone and SQL gate

## Implementation boundaries

The core plugin depends on `com.intellij.modules.platform` and `com.intellij.modules.lang`. The optional
`com.intellij.database` descriptor installs `com.intellij.lang.fileViewProviderFactory` only when database
tools are available. The factory uses a normal single-root provider unless the experiment is enabled.
There are no SQL plugin classes in the core class signatures. YAML is deferred, so no YAML dependency
is required by this first editor milestone.

The experiment uses `MultiplePsiFilesPerDocumentFileViewProvider`,
`TemplateLanguageFileViewProvider`, `TemplateDataElementType` and `OuterLanguageElementType`. It creates
the SQL file through `LanguageParserDefinitions` and sets its template content through the public
`PsiFileImpl.setContentElementType` method. No tree patcher or internal template modification APIs are
used. This last implementation-class dependency remains a compatibility-review risk, despite being
public in the pinned SDK.

Reference source: [JetBrains file view providers](https://plugins.jetbrains.com/docs/intellij/file-view-providers.html)
and [IntelliJ 253 template-data implementation](https://github.com/JetBrains/intellij-community/blob/idea/253.28294.334/platform/analysis-impl/src/com/intellij/psi/templateLanguages/TemplateDataElementType.java).
Build dependency configuration follows the [official Gradle dependencies extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html).

## Gate criteria

| Criterion | Required evidence |
| --- | --- |
| Core loads without database tools | Core fixture suite with no database test dependency |
| SQL and Jinja can coexist | Separate roots using the actual bundled SQL parser |
| Original text and offsets are retained | Both roots match complete source, including holes and incomplete edits |
| Recovery survives incomplete input | Prefix edits and malformed expressions followed by valid declarations |
| Generated SQL does not receive misleading diagnostics | Relation/identifier/branch fixtures; no false source-template errors |
| Dialect completion and navigation remain useful | PostgreSQL/Snowflake manual or platform integration tests |

The final two criteria must pass before enabling native SQL by default. Removing Jinja holes is the
smallest experiment, not the proposed final semantic solution. `select customer_id from {{ ref('orders') }}`
becomes an incomplete SQL query in the projected parser input, so the expected-failure test guards
against accidentally treating text/offset preservation as semantic success.

## Validation record

Executed on IntelliJ IDEA **2025.3.3**, build **253.31033.145**, on macOS/aarch64. The build uses Eclipse
Temurin **21.0.12.1+1**; the final fixture runs use JetBrains Runtime **21.0.10-b1163.108**. The 12 core
lexer/editor tests passed without a database test dependency. The suite with the actual bundled database
plugin passed all 15 tests, including three SQL integration tests.

- Both PostgreSQL/Jinja roots preserve full original text for projection holes, relation holes,
  conditional statements, identifier fragments and incomplete expressions. Named SQL leaf offsets
  are checked before and after holes.
- Removing `ref('orders')` from a `FROM` clause produces SQL parser errors. The test verifies this
  blocker. Native SQL remains disabled by default: **the semantic feasibility gate has not passed**.
- The SQL base language's parser throws when creating a file because it requires a concrete
  `SqlLanguageDialect`. The prototype therefore uses the registered PostgreSQL parser. This was
  established against the bundled SDK implementation and fixture execution, not assumed from the
  public language registry. Snowflake has not been exercised.
- `verifyPluginStructure` and development ZIP packaging passed. IntelliJ Plugin Verifier **1.410**
  reports **Compatible** against **IU-253.31033.145**, with no reported forbidden API use. The successful
  run uses `-PverifierOffline=true` and the downloaded SDK. The online run was stopped while downloading
  transitive Marketplace dependencies. The SDK layout checker warns about missing merged-module paths,
  and the offline dependency report lists unavailable optional frontend modules; this limits the check
  to the locally available distribution, not a complete remote dependency/edition qualification.

No subscribed/free-edition UI qualification, PostgreSQL/Snowflake assistance qualification,
IntelliJ 2026.1 compatibility run or performance benchmark has been performed. Parser fixtures are
editing correctness checks, not runtime/dbt equivalence tests. The project-configuration checker
warns about the deliberately bounded `until-build` range. Earlier runs with an explicitly selected
Temurin test runtime also warn about using a non-JBR runtime. These do not change the test results and
remain release-qualification items.

## Next independently testable milestones

1. Complete statement and expression grammar, lexical scope and editing recovery corpus. Add YAML value
   injection without changing the host YAML structure. Do not publish runtime-equivalence claims.
2. Add cancellable project discovery and source indexes. Resolve ordinary/package macros and literal
   `ref`/`source` through one provenance-aware resolver. Test no-manifest and unsaved-source behavior.
3. Add completion, documentation, parameter information and Find Usages against that shared resolver.
   Dynamic references remain unknown. Add rename only after collision and ambiguity tests exist.
4. Iterate on typed SQL holes and diagnostics policy; qualify dialects and editions using actual SDKs.
   Retain lexical fallback if the gate remains incomplete.
5. Add schema-validated artifact enrichment, stale-artifact handling and resource inspections; only then
   consider optional execution and compiled previews.

Local navigation currently rescans PSI in the current file and suppresses a name if any supported
statement header binds it. This intentionally under-resolves scopes that are not yet understood.
It is unsuitable for large project resolution; project indexes and measured budgets belong to milestone 2.
