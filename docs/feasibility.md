# First development milestone and SQL gate

## Implementation boundaries

The core plugin depends on `com.intellij.modules.platform` and `com.intellij.modules.lang`. The optional
`com.intellij.database` descriptor installs `com.intellij.lang.fileViewProviderFactory` only when database
tools are available. The factory uses a normal single-root provider unless the experiment is enabled.
The core descriptor registers `com.intellij.lang.substitutor` with the empty language ID (the platform's
all-language hook), independent of database tools. This runs before host-specific dialect substitution
and handles TextMate/plain-text SQL in free editions. The substitutor selects Jinja only for `.sql`
files within a project content root beneath `dbt_project.yml`,
excluding default generated directories. A project service caches at most 512 directory decisions and
invalidates them on VFS structure and project-root changes. It checks VFS metadata without reading
configuration contents, scanning source files, using semantic indexes or executing project code.
Custom configured output paths and complete resource classification remain deferred.
Free PyCharm also registers a legacy SQL syntax table (`AbstractFileType`) without a `Language`;
substitution cannot run for it. The public `com.intellij.fileTypeOverrider` extension selects the dbt
file type for this non-language SQL host only under a dbt marker. It leaves language-backed hosts
to the substitutor and avoids binary hosts. `com.intellij.fileTypeDetector` covers otherwise unclaimed
text SQL files. These file-type callbacks have no project parameter, so their marker lookup follows
VFS ancestors and excludes the same generated directories. Native associations outside dbt remain
untouched. File-type detection caching means marker changes in the unclaimed-file fallback may require
reopening/re-detecting the file; the existing language-substitution path has structural cache invalidation.
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

Development version **0.1.1-dev** extends the descriptor ceiling to **262.*** after the original
**261.*** ceiling prevented installation in the user's PyCharm. IntelliJ Plugin Verifier **1.410**
reports **Compatible** for the rebuilt ZIP against both **PY-262.10968.92** (installed PyCharm
**2026.2.3**, bundled JBR **25.0.4+1-b508.27**) and **IU-253.31033.145**. The 15-test suite and plugin
structure checks also pass for this rebuilt version. These are binary compatibility checks against
PyCharm; its interactive editor workflows have not yet been qualified. The fixture suite still runs
against the pinned IntelliJ SDK, and native SQL remains experimental.

Development version **0.1.2-dev** fixes activation in ordinary `.sql` models. The expanded **18-test**
suite passes against the pinned IntelliJ SDK with database tools. The new editor integration test opens
an actual SQL-associated file under `dbt_project.yml`, checks the active Jinja PSI and editor highlighter,
and invokes completion at a macro call. Additional tests verify native SQL outside dbt/inside `target`
and cache invalidation when the project marker is created or deleted. This avoids treating a directly
invoked substitutor or a forcibly assigned test file type as evidence of real editor activation.
The 12-test core suite also passes with the optional database test dependency absent. The final ZIP
passes plugin structure checks, and offline binary verification reports **Compatible** against both
**PY-262.10968.92** and **IU-253.31033.145**.

The 0.1.0–0.1.2 core runs omitted the database *test dependency*, but this did **not** disable the
database plugin bundled in the IDE. Their passing core fixtures therefore did not qualify the free
edition. Version 0.1.3 explicitly disables optional plugins in `prepareTestSandbox`, and a regression
asserts that SQL/TextMate languages are absent when disabled. This corrects the earlier evidence gap.

Version **0.1.3-dev** passes **22 tests** against the exact installed **PY-262.10968.92** SDK using
its bundled **JBR 25.0.4+1-b508.27**, with both `com.intellij.modules.ultimate` and `com.intellij.database`
explicitly disabled. This run exercises PyCharm's default legacy SQL syntax-table host without file-type
overrides, the real TextMate host, actual editor token highlighting, function colors, completion,
project macro navigation/Find Usages, duplicates, shadowed names, nested projects, unsaved macro/config
edits and deletion. The baseline **IU-253.31033.145** passes **20 tests** with database tools and TextMate
both disabled and **28 tests** with both enabled. The legacy/unclaimed fallback is covered alongside
the original native SQL integration experiment. Saved reports are in `build/reports/tests/013-*`.
The build compiler is Kotlin **2.3.21**, with language/API level **2.2** and Java **21** bytecode explicitly
set on compilation tasks. A full Temurin **25.0.4.1+1** JDK drives the PyCharm SDK build; Temurin
**21.0.12.1+1** drives the baseline. ZIP contents have class-file version **65**. Structure checks and
development ZIP packaging pass.
Offline IntelliJ Plugin Verifier **1.410** reports **Compatible** for this final ZIP against both
**IU-253.31033.145** and **PY-262.10968.92**, with no reported forbidden API use. The same missing
merged-module/optional-dependency limitations noted above apply. The verifier uses its dedicated
`offline` property, avoiding duplicate CLI flags when Gradle also runs with `--offline`.

No interactive user-IDE UI qualification, PostgreSQL/Snowflake assistance qualification,
IntelliJ 2026.1 compatibility run or performance benchmark has been performed. Parser fixtures are
editing correctness checks, not runtime/dbt equivalence tests. The project-configuration checker
warns about the deliberately bounded `until-build` range. Earlier runs with an explicitly selected
Temurin test runtime also warn about using a non-JBR runtime. These do not change the test results and
remain release-qualification items.

## Next independently testable milestones

1. Complete statement and expression grammar, lexical scope and editing recovery corpus. Add YAML value
   injection without changing the host YAML structure. Do not publish runtime-equivalence claims.
2. Extend marker-based discovery with configuration-aware resource classification and source indexes. Resolve ordinary/package macros and literal
   `ref`/`source` through one provenance-aware resolver. Test no-manifest and unsaved-source behavior.
3. Add completion, documentation, parameter information and Find Usages against that shared resolver.
   Dynamic references remain unknown. Add rename only after collision and ambiguity tests exist.
4. Iterate on typed SQL holes and diagnostics policy; qualify dialects and editions using actual SDKs.
   Retain lexical fallback if the gate remains incomplete.
5. Add schema-validated artifact enrichment, stale-artifact handling and resource inspections; only then
   consider optional execution and compiled previews.

Project macro lookup uses a content-dependent `ScalarIndexExtension<String>` over SQL/Jinja template
tokens. It opens PSI only for files containing the requested macro name, scopes results to the nearest
dbt root, and excludes nested projects/default generated directories. Same-file unqualified macros take
precedence. Qualified calls resolve only through the current project's literal identifier `name:` scalar;
complex YAML expressions and dependency-package namespaces are not evaluated. Resolution suppresses a
name if any supported statement header binds it, intentionally under-resolving scopes that are not yet
understood. Index-backed completion and resolution pause during indexing. Performance qualification,
full lexical scopes, configured macro paths and dependency packages remain future milestones.
