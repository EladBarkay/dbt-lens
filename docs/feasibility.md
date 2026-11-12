# Engineering notes: SQL integration and qualification

> [!NOTE]
> Internal design and qualification record, kept for maintainers. For using the plugin, see the
> [user guide](user-guide.md).

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

Version **0.1.4-dev** fixes navigation from set assignments, do statements, if/elif conditions and
for iterables/conditions. Assignment/loop bindings have separate PSI so a package name appearing on
the right-hand side no longer falsely shadows its macro references. Real local bindings and macro
parameters remain conservatively excluded. SQL text numbers (integers, decimals and exponents) now
have numeric highlighting; SQL identifiers, comments and strings remain separate tokens. The dbt
file type supplies the registered SQL icon when available, otherwise a bundled SQL icon.

The core registers a public `FoldingBuilderEx` implementing `DumbAware`, following the
[JetBrains folding builder extension](https://plugins.jetbrains.com/docs/intellij/folding-builder.html).
It folds complete macro/template bodies, elif/else bodies, multiline tags, calls and collections
without semantic indexes. Actual editor folding-model tests check macro collapse/expand, exact body
ranges, persistence across highlighting, caret movement, same-length document edits and reopening.
An automatically activated `.sql` regression checks nested folds and project-tree icons alongside
the same-file `elementary.agate_val_serialize` statement call, declaration navigation and Find Usages.
Prefix edits now cover set, do, for and if/elif/else syntax as well as macro signatures.

The final build passes **25 tests** on the exact **PY-262.10968.92** SDK with commercial/database
modules disabled, **23 tests** on **IU-253.31033.145** with database tools and TextMate disabled, and
**31 tests** with both enabled. Reports are saved under `build/reports/tests/014-*`. Plugin structure
checks and ZIP packaging pass; all packaged classes use Java **21** bytecode (class version **65**).
Offline Plugin Verifier **1.410** reports **Compatible** against both IDE builds with no reported
forbidden API use. Its merged-module and optional-dependency limitations remain as described above.
Git now tracks the source, tests and documentation; the working 0.1.3 baseline was committed before
these changes. Generated caches and build outputs are ignored.

Release candidate **dbt Lens 0.1.4** renames the plugin and build artifact while retaining the original
`dev.dbt.jinja` plugin ID and internal language/file-type IDs for installation compatibility. The release
ZIP is `build/distributions/dbt-lens-0.1.4.zip`. All **31 SQL-enabled tests**, plugin structure checks and
packaging pass again after the rename. Offline binary verification reports **Compatible** against
**IU-253.31033.145** and **PY-262.10968.92**, with the same qualification limits described above.
The `0.1.4` pull request targets `0.1.3`. Earlier version PRs target their immediate predecessor,
starting with `0.1.0` targeting the bootstrap-only `main`. Merging and Marketplace publication are separate steps.

Version **0.1.5** adds Go to Declaration from every supported closing keyword to its matching opener.
Nested enclosures are resolved through their parsed block, and malformed/mismatched closers do not
navigate to unrelated openings. A `DumbAware` `GotoDeclarationHandler` provides structural navigation
without adding closing tags to semantic macro usages. Tests invoke the IDE's actual declaration action,
including automatically activated `.sql` files and unchanged macro Find Usages counts.

Multiline Jinja comments (including whitespace control) and complete SQL block comments now fold.
String contents, single-line comments and incomplete comment blocks do not produce comment folds.
SQL comment folding shares the lexical fallback's existing limitation around Jinja holes.
Blocks and elif/else branches with multiline headers have a primary fold starting at the end of
their first line and extending through the body. Competing header/argument-only folds are suppressed
inside structural headers; standalone expression/set-call folds remain available. Editor tests check
that collapsing the first-line region actually hides the macro, loop or conditional body.

The final 0.1.5 source passes **35 SQL-enabled tests**, **27 minimal-host tests**, and **29 tests** on
the exact **PY-262.10968.92** SDK with commercial/database modules disabled. Reports are saved under
`build/reports/tests/015-*`. Structure checks and `dbt-lens-0.1.5.zip` packaging pass; packaged classes
retain Java **21** bytecode. Offline Plugin Verifier **1.410** reports **Compatible** against both
**IU-253.31033.145** and **PY-262.10968.92**, with the same documented qualification limits.
The `0.1.5` feature PR targets `0.1.4`.

Version **0.1.6** fixes the folded display of multiline structural headers: the placeholder closes
any brackets opened on the first line and displays the actual `%}`/`-%}` delimiter before the collapsed
body. An editor test asserts the resulting visible macro signature. Inline conditional expressions now
have their own PSI, right-associative alternatives and optional else; filtered for-loop headers retain
their separate condition. The user's multiline set/conditional example parses without error elements,
and prefix-edit fixtures retain every source character.

Static qualified/unqualified calls now navigate to installed dependencies in `dbt_packages` or a literal
`packages-install-path`. Package namespaces come from each `dbt_project.yml`. Current-project macros
win unqualified lookups; ambiguity remains explicit. Excluded installed packages use a cached token
catalog with cancellation checks, rather than relying on IDE content indexes. The fallback reads
SQL/Jinja files up to 2 MB and opens PSI for matching declarations only. Nested unrelated dbt roots,
generated folders and virtual environments are excluded. Unsaved committed macro edits invalidate the
catalog. Tests cover an excluded custom install folder, completion, opening dependency SQL files,
namespace shadowing and unchanged filter/test namespaces. Performance benchmarking and dynamic
adapter dispatch remain deferred.

Simple macro parameters, preceding set bindings and enclosing for bindings now provide variable
references. Namespace attribute assignment does not invent a new root binding. Local bindings take
priority over macro resolution; loop names do not leak into following statements. This remains a
conservative lexical subset, not a full runtime scope model. Variable/parameter, call/declaration and
dbt-global color categories are exposed through a native Color Scheme page. Macro hover/Quick
Documentation uses the first Jinja or SQL comment below the header, escaping HTML and showing a static
signature. It shares macro references, including dependencies, and executes no project code.

An optional JSON descriptor registers JetBrains' public schema-provider extension. YAML retains its
native parser. Project/profile/package/dependency/selector files and property YAML in configured
resource folders receive schemas and a dbt marker icon. Property-path overrides currently support
literal inline YAML lists. The standard user profiles file is supported when a dbt project is an IDE
content root. Five official dbt Labs schemas are bundled at revision
`6a401244651d3b5e0fd2f7cfcfb120187118edb4`, with their Apache-2.0 license and no external references.
A permissive profiles schema validates common structure while allowing adapter fields and Jinja
string scalars. Native YAML completion and schema inspections are exercised in tests, alongside
non-dbt/unrelated/generated-file exclusion and marker deletion.

The project setting **Keep the native SQL editor in dbt projects** leaves ordinary native SQL files
to JetBrains' dialect selection and parser. Jinja features are disabled in those files in that mode;
explicit template associations retain Jinja editing. The default remains lexical SQL coloring. The
experimental two-root parser remains opt-in PostgreSQL, with the same failed relation-hole gate;
no simultaneous production SQL/Jinja integration or dialect fidelity is claimed.

The 0.1.6 build passes **45 SQL-enabled tests**, **33 minimal-host tests** with Database Tools,
TextMate, JSON and YAML disabled, and **38 tests** on **PY-262.10968.92** with commercial/database
modules disabled and native JSON/YAML enabled. Reports are saved under `build/reports/tests/016-*`.
Plugin structure and project-configuration checks and packaging pass; the checker retains its existing
bounded-until-build warning. `dbt-lens-0.1.6.zip` uses Java **21** bytecode. Offline Plugin Verifier
**1.410** reports **Compatible** against both **IU-253.31033.145** and **PY-262.10968.92**, with no
reported forbidden API use and the same merged-module/optional-dependency qualification limits.
The feature PR targets `0.1.5`.

Version **0.1.7** keeps the complete macro signature visible and folds only the body. Other multiline
structural headers retain their existing first-line controls. Documentation now uses the adjacent
comment **above** a declaration, superseding 0.1.6's below-header convention. Tests reject comment-like
strings and body comments as docs. The palette defaults to ordinary identifier colors for variables
and calls, declaration blue, muted red keyword labels, and subdued violet globals. Unknown identifiers
are not assigned a variable category; filters with or without parentheses are callable usages.

Static macro references also cover callbacks passed as values. Unknown filter names may navigate to
matching macros, but this does not establish registration in Jinja's separate filter registry. Built-in
filters and tests stay separate from same-named macros, and normal lexical bindings retain precedence.
The supplied Streamline dbt SVG replaces the provisional mark; dbt SQL uses a small code badge and
configuration/property YAML uses the original mark. The icon provider leaves unrelated SQL alone.

Literal `ref` calls navigate to SQL/Python models, CSV seeds and SQL snapshot declarations. An optional
native-YAML bridge supplies source-table, snapshot, metric and model-version definitions, with offsets
from native YAML PSI. Versioned model navigation honors integer/quoted `v`/`version`, `latest_version` and
`defined_in`, using YAML as a fallback when a model file cannot be selected. Default latest-version
inference is limited to integer identifiers; other schemes require explicit `latest_version`. Unqualified resources
prefer the current dbt project; package-qualified refs and installed dependencies share the macro
resolver's package roots. Duplicate candidates are retained, dynamic/shadowed/incomplete calls remain
unknown, and arbitrary nested projects are excluded. Exposure dependency links are supported in native
YAML, including YAML quote escaping; exposures themselves are not ref targets. Full-expression parsing
prevents call-looking strings or Jinja comments from becoming resource links.

Resource folders support literal inline and block YAML path lists. A cancellable cached catalog reads
configured folders, excludes generated/environment folders and nested projects, and invalidates on
committed PSI edits and VFS changes. YAML and SQL snapshot reads are capped at 2 MB; model files and
CSV seeds need only names. This is source-based static discovery without manifest or dbt execution,
not complete config/runtime semantics. Resource completion, rename and resource Find Usages remain
out of scope. YAML metadata classes load only through the optional YAML descriptor.

The 0.1.7 test matrix passes **52 SQL-enabled tests**, **38 minimal-host tests** with SQL, TextMate,
JSON and YAML disabled, and **45 tests** on **PY-262.10968.92** with commercial/database modules disabled.
Reports are saved under `build/reports/tests/017-*`. Configuration/structure checks and packaging pass;
`dbt-lens-0.1.7.zip` retains Java **21** bytecode. Offline Plugin Verifier **1.410** reports **Compatible**
against both **IU-253.31033.145** and **PY-262.10968.92**, with the same documented qualification limits.
The feature PR targets `0.1.6`.

Version **0.1.8** makes parameter and binding PSI named/searchable and provides local search scopes.
Find Usages and declaration-based editor highlighting include reads and reassignments in the same scope;
nested loop/with/call scopes remain separate. Go to Declaration still chooses the latest known write.
If branches remain transparent but retain multiple possible definitions after branch-dependent assignments.
References, completion and static inference share the visibility model, including loop filters, with/call/test
bindings and outer constants. Imports and full runtime scope semantics remain incomplete.

Completion now separates tags, filters, tests, members and expression names. It suggests local definitions,
project/dependency macros, contextual loop/caller helpers, common string/dictionary/list methods, and dbt
namespaces such as adapter, builtins, exceptions, modules and api. Static types follow literals, simple aliases,
default arguments, captures, collection iteration, common filters and documented return values. Receiver
chains and dictionary fields are supported conservatively; arbitrary macros and unknown receivers remain
unknown. Signatures and hover use a shared catalog, and known keyword labels exclude existing keyword or
positional arguments and positional-only parameters. Runtime version-specific typing, rich Agate objects,
YAML macro argument metadata and imported-symbol inference remain future work. The catalog was checked
against official Jinja/Python/dbt documentation and the dbt Core 1.10.6 context source. `api.Relation` and
`api.Column` receive the declaration color; ordinary call/variable colors remain unchanged.

Public typed/Enter handlers pair Jinja delimiters, allow overtyping existing closers and respect bracket-pair
settings. They avoid strings, raw blocks and other file types; fixtures caught and fixed native brace pairing
that initially produced an extra `}`. Accepted structural tag completions create native live templates with
editable header placeholders and a body cursor. Enter after a complete opening tag at EOF inserts a missing
end tag; set assignments never get endset and existing complete enclosures are not duplicated. The editing
subset also parses slices, call unpacking, filtered capture sets, with/call/test headers, dbt docs blocks,
numeric underscores and plus whitespace controls. Loopcontrols tokens are recognized without claiming
runtime execution/validation. Resource calls with unpacking or overridden builtin macros remain unresolved.

The 0.1.8 matrix passes **70 SQL-enabled tests**, **56 minimal-host tests** with SQL, TextMate, JSON and
YAML disabled, and **63 tests** on the exact **PY-262.10968.92** SDK with commercial/database modules disabled.
Reports are saved under `build/reports/tests/018-*`. Configuration and structure checks and packaging pass;
`dbt-lens-0.1.8.zip` uses Java **21** bytecode (class version **65**). Offline Plugin Verifier **1.410** reports
**Compatible** against both **IU-253.31033.145** and **PY-262.10968.92**, with the same merged-module and
optional-dependency qualification limits. The feature PR targets `0.1.7`.

Version **0.1.9** fixes the reported null-AST exception in the Enter handler. Physical template files
have directory parents with no AST; prior light-file fixtures did not expose that path. Enter,
keyword-argument completion and block templates now stop ancestor traversal at the file and tolerate
missing nodes. Physical-file regressions exercise SQL text, comments, raw blocks, empty files,
expression completion and the existing EOF macro closer behavior.

Literal `adapter.dispatch` calls now expose all discovered adapter/default implementations through
Go to Declaration on the callee or macro-name string. Package order follows the host project's literal
search order through an optional native-YAML bridge; dependency-local overrides are ignored. Defaults
appear first within each package. Navigation does not infer an active adapter or its inheritance.
Adapter-specific declarations navigate to their same-package default, offering visible dependency
defaults when no own default exists. Find Usages adds matching dispatch strings to ordinary direct
calls through public indexed reference search. These are possible SQL/Jinja call sites, not claims
about runtime selection. Dynamic/shadowed/malformed calls remain unresolved. Root configuration and
excluded/custom installed dependencies use the existing project-root and cached catalog boundaries.

The 0.1.9 matrix passes **86 SQL-enabled tests**, **68 minimal-host tests**, and **79 tests** on the
exact **PY-262.10968.92** SDK with commercial/database modules disabled. Reports are saved under
`build/reports/tests/019-*`. Project-configuration and structure checks and ZIP packaging pass;
`dbt-lens-0.1.9.zip` retains Java **21** bytecode (class version **65**). Offline Plugin Verifier
**1.410** reports **Compatible** against both **IU-253.31033.145** and **PY-262.10968.92**, with the
same merged-module and optional-dependency qualification limits. The feature PR targets `0.1.8`.

Version **0.1.10** adds optional saved-artifact enrichment through a shared project service and the
existing documentation provider. The reader accepts manifest schemas **v8–v12** and catalog **v1**;
it preserves resource/package/version identities and maps files through the existing root/dependency
boundaries. Source navigation remains authoritative and independent of artifact availability. Saved
descriptions, argument metadata, columns/types, relation names and tests enrich documentation. Source
comments retain priority in macro hovers. Unique case-insensitive column matches accommodate warehouse
case normalization without merging ambiguous documented names.

The **dbt Lens** tool window shares this metadata across a detached read-only compiled SQL viewer,
documentation and a native resource-lineage graph. Saved SQL may come from the manifest or a bounded
generated-file fallback. Macro definitions and Python model code are never presented as compiled SQL.
The resource selector handles multiple macros or property-YAML resources in one file. Lineage has
upstream/downstream/both controls, depth 1–3, cycle handling and an 80-resource cap. Node navigation
uses local original files, macro offsets and the existing optional YAML resource resolver where possible.
The detached viewer uses the available native SQL file type; source-file dialect mappings are not inherited.

Parsing and preview reads run on workers. Artifact cache invalidation observes modification time,
size and file identity; explicit refresh bypasses the cache. A generation check prevents old async
results from replacing a newly selected file/resource. Source differences include unsaved SQL/Jinja
edits. Configuration, dependency and warehouse changes are not completely detected. Artifact reads are
limited to 64 MB/file and 100,000 resources; compiled output to 2 MB. Invalid/unsupported/foreign
artifacts and catalog-generation errors receive readable statuses. Dynamic/external output directories
and symlink escapes are rejected. These limits are conservative development safeguards, not large-project
performance qualification. File-change races are detected within a read; two independently generated
artifacts can still be of different ages, which are displayed separately.

No CLI command, Jinja evaluation or warehouse access is implemented. Future compilation should delegate
to dbt with explicit executable/profile/target settings because introspective macros may query the warehouse.
Macro invocation requires its own argument/context workflow. Live data preview remains deferred.

The 0.1.10 matrix passes **101 SQL-enabled tests**, **83 minimal-host tests**, and **94 tests** on the
exact **PY-262.10968.92** SDK with commercial/database modules disabled. Reports are saved under
`build/reports/tests/0110-*`. Disk-backed artifact fixtures exercise invalid/missing/foreign output,
custom/nested roots and dependency files, schema versions, resource versions, catalog errors and column
case normalization, source/macro docs, unsaved changes, file replacement/deletion, size limits and
symlink escapes. Native panel fixtures exercise async preview loading, resource switching, read-only
documents, stale-source labels and non-dbt exclusion; graph fixtures cover direction/depth/cycles/caps.

Configuration/structure checks and packaging pass. `dbt-lens-0.1.10.zip` retains Java **21** bytecode
(class version **65**). Offline Plugin Verifier **1.410** reports **Compatible** against both
**IU-253.31033.145** and **PY-262.10968.92**, with merged-module and optional-dependency qualification
limits. It reports **4 deprecated API usages** on IntelliJ and **6** on PyCharm, plus **6 experimental
API usages** from ToolWindowFactory default methods. The SDK-matched JetBrains Runtime is now selected
automatically unless an explicit local runtime is supplied; this avoids the indexing-process startup
failure observed with the temporary Temurin 21 runtime. The feature PR targets `0.1.9`.

Version **0.1.11** replaces the artifact tab's unstyled Swing HTML pane with JetBrains' native
`JBHtmlPane`. The local renderer uses regular IDE label-font text, native theme colors, scaled padding,
compact paragraphs, bold section headings and forced word wrapping. It is registered under the panel's
disposable. The shared HTML formatter adds labeled column tables, omits the type column when no types
are known, and displays tests as individual list items. The artifact tab keeps its staleness warning
in the status area; hover documentation retains the warning in its content.

The rendering regression uses the actual component with long test identifiers at **360 px** and
**800 px**, checks the regular IDE font and minimum width, and optionally exports native-component PNGs
when `DBT_DOCS_RENDER_DIR` is supplied. Those PNGs were inspected on both SDKs during development.
The suites pass **102 SQL-enabled tests** and **95 tests** on the exact PyCharm SDK with commercial/database
modules disabled. Reports are saved under `build/reports/tests/0111-*`. Packaging/structure checks pass;
the final package reuses the already-generated settings index because no configurable changed.
Plugin Verifier **1.410** reports **Compatible** against **IU-253.31033.145** and **PY-262.10968.92**,
subject to the existing merged-module/optional-dependency limits. It reports **23 experimental API
usages**, including the public native HTML pane APIs, and **4/6 deprecated usages** respectively.
The feature PR targets `0.1.10`. No CLI generation or compilation behavior changes in this version.

Version **0.1.12** adds explicit local dbt CLI integration. Project settings select a dbt executable,
profiles directory/profile/target and optional literal environment file. The runner prefers the project's
`.venv`/`venv`, then PATH, inherits the IDE environment, and passes arguments directly without a shell.
Only the environment-file path is persisted. File contents are not copied into settings; dbt's own log
messages can still include values printed by macros. Remote execution, shell activation and interactive
prompts are not supported. Commands save pending documents within the selected dbt root before execution.

**Generate manifest** delegates to `dbt parse`; **Compile current/project** delegate to `dbt compile`.
The macro call dialog accepts an explicit invocation and arguments for `dbt compile --inline`, and shows
its compiled event output separately from saved macro definitions. Known models containing local macro
declarations still compile as models. Saved resource selection uses package/type/FQN provenance, with
an exact file-name fallback, so dependency-only paths do not rely on dbt's host-root path glob.
Compile can run introspective queries through dbt; no dbt command is started by indexing, hovering or
opening files. Catalog generation, dependency installation, run-operation, model execution and live
query results remain deferred.

A project service runs at most one command, with native background progress, process cancellation,
bounded readable logs and project-disposal cleanup. Success requires a zero exit, matching resources
and a newly written supported manifest. Artifact metadata/SQL refresh automatically, including when a
panel opens after completion. Inline results are associated with the originating macro/file; another
resource never receives that result. Generated-file fallback requires manifest compilation provenance;
parse or a partial compile cannot silently display older files left on disk for uncompiled resources. Both literal output paths and physical ancestors are checked to
keep generated output within the selected project.

The CLI fixture suite covers argument vectors, executable resolution, literal environments, missing
manifest generation, selected compile/inline output, unsaved edits, model/macro routing, versioned
resource selectors, delayed panel refresh, process failure, zero-exit/no-output failure, cancellation
and restart, malformed environment errors and bounded chunked JSON logs. The process fixtures use
`/usr/bin/python3` as a fake dbt executable; no dbt installation is needed for those IDE tests.
Tests run under macOS on the two SDKs; Windows process behavior is not qualified.

A separate isolated **dbt Core 1.10.6 / dbt-duckdb 1.10.1** smoke check uses the same CLI arguments.
Parse, selected-model compile, project compile, dependency-model compile and inline macro compile pass.
The macro executes `run_query('select 7 as id')` against the temporary local DuckDB database before
emitting SQL. This verifies dbt's query-dependent compilation path, without connecting to user warehouse
profiles. Results/logs are saved under `build/reports/cli/0112`. External warehouse adapters and Fusion
are not qualified by this check.

The final matrix passes **116 SQL-enabled tests**, **98 minimal-host tests**, and **109 tests** on the
exact **PY-262.10968.92** SDK with commercial/database modules disabled. Reports are saved under
`build/reports/tests/0112-*`. Packaging/structure checks pass and the settings search index is regenerated.
The final ZIP keeps Java **21** bytecode (class version **65**) and the builds 253–262 descriptor range.
Plugin Verifier **1.410** reports **Compatible** for **IU-253.31033.145** and **PY-262.10968.92**, with the
existing merged-module/optional-dependency limits, **23 experimental** API usages and **4/6 deprecated**
usages respectively. The feature PR targets `0.1.11`.

No interactive user-IDE UI qualification, PostgreSQL/Snowflake assistance qualification,
IntelliJ 2026.1 compatibility run or performance benchmark has been performed. Parser fixtures are
editing correctness checks, not runtime/dbt equivalence tests. The project-configuration checker
warns about the deliberately bounded `until-build` range. Earlier runs with an explicitly selected
Temurin test runtime also warn about using a non-JBR runtime. These do not change the test results and
remain release-qualification items.

## 1.0.0 review and qualification

The release review covers the original main through 0.1.12, the cumulative 0.2.0–0.2.4
checkpoints and the reviewed 1.0.0 changes. See [release-review.md](release-review.md) for
the code, architecture, product/business, maintenance, security and CI/CD assessment, fixed
findings and repeated-review record. This section supersedes historical release claims above
where behavior or qualification changed.

The final source passes the following real IDE fixture configurations on macOS:

| Host configuration | SDK | Tests | Failures/errors/skipped |
| --- | --- | --- | --- |
| SQL, TextMate and YAML enabled | IU-253.31033.145 | 124 | 0/0/0 |
| SQL disabled; TextMate and YAML enabled | IU-253.31033.145 | 117 | 0/0/0 |
| SQL, TextMate, JSON/YAML and commercial modules disabled | IU-253.31033.145 | 106 | 0/0/0 |
| Installed PyCharm with commercial/database modules disabled | PY-262.10968.92 | 117 | 0/0/0 |

Every configuration also passes the formatter, project-configuration and plugin-structure
checks. Packaging passes against both SDKs; the native settings search index is regenerated.
The ZIP contains the selected Apache-2.0 LICENSE/NOTICE and unchanged upstream schema license.
The original plugin ID is retained, all plugin classes use Java 21 bytecode (class version 65),
and the descriptor has `since-build=253` without an arbitrary upper-build ceiling. Compatibility
claims still cover the exact tested versions, not every installable future IDE.

The Gradle wrapper is upgraded to **9.3.0**, with both its distribution and regenerated wrapper
binary checked against Gradle's official SHA-256 values. This version supports Java 25 and is
within Kotlin **2.3.21**'s supported Gradle range. Java 21 compiles the baseline; Java 25 runs
the PyCharm SDK. Verification with a Java 25 Gradle runtime additionally requires a discoverable
Java 21 compilation toolchain. CI installs both and supplies their installation paths explicitly.
Kotlin language/API levels remain 2.2 and the plugin bytecode target remains 21.

Plugin Verifier **1.410** reports **Compatible** for both exact SDKs. Each reports **4 deprecated**
and **23 experimental** API usages. The deprecated usages are Kotlin-generated delegations to
ToolWindowFactory defaults; the previous direct deprecated ReadAction calls are removed.
Experimental usages include the native documentation pane and factory defaults. These remain
watchpoints for future IDE updates, not compatibility errors on the tested versions.
Gradle also emits a Gradle 10 deprecation notice during verification; a future build-tool
major upgrade needs its own plugin/deprecation qualification rather than an unchecked wrapper bump.
The local run is offline: it does not fetch missing optional dependency variants, and merged
SDK modules can produce layout warnings. The minimal/free-host runtime suites complement that
binary result. CI runs the online verifier against both pinned IDE distributions.

GitHub CI retains per-configuration test reports, binary verification reports and the baseline
ZIP/checksum. Both workflow files pass actionlint **1.7.12**; XML descriptors and JSON schemas
parse, and all bundled schema references remain internal/offline. The tag-to-draft workflow
is statically checked; its first complete release run requires the workflow to be merged and
a matching version tag on main. No 1.0.0 release tag or Marketplace publication is created by
the review PR.

New regressions cover quoted/literal configuration, malformed JSON/paths/lineage, byte limits
including file growth, rapid selection/disposal and ambiguous/malformed saved identities.
Compile current no longer falls back to a basename or shortens malformed FQN metadata into a
different selector. Missing identity requires manifest regeneration or explicit project compile.
Lineage bounds include examined missing IDs, and artifact/background work checks cancellation
during reading and reverse-index construction.

The prior dbt Core/DuckDB smoke remains historical evidence. No new interactive user-IDE,
large-project benchmark, Windows, Fusion or external warehouse-adapter qualification is claimed.
Since the repository became public, `main` is protected: the four editor configurations and the
Plugin Verifier must pass before a merge, and force pushes and deletion are blocked.

## Next independently testable milestones

1. Complete statement and expression grammar, lexical scope and editing recovery corpus. Add YAML value
   injection without changing the host YAML structure. Do not publish runtime-equivalence claims.
2. Extend existing resource discovery with configured macro-path filtering and custom generated-output
   exclusions, then qualify project-scale cache/index behavior and unsaved-source invalidation.
3. Add resource completion, parameter information and argument/resource inspections against the
   existing resolvers. Dynamic references remain unknown. Add rename only after a project-wide
   reference model and collision/ambiguity tests exist.
4. Iterate on typed SQL holes and diagnostics policy; qualify dialects and editions using actual SDKs.
   Retain lexical fallback if the gate remains incomplete.
5. Extend artifact enrichment with configuration/dependency freshness checks, resource inspections and
   performance qualification. Extend the explicit CLI workflow with catalog generation, richer macro
   argument assistance and environment/adapter qualification.

Project macro lookup uses a content-dependent `ScalarIndexExtension<String>` over SQL/Jinja template
tokens. Current-project lookups open PSI for indexed files containing the requested macro name and
scope results to the nearest dbt root. Installed dependencies use their own roots and, when necessary,
the cached fallback catalog described above. Same-file unqualified declarations take precedence.
Literal package names and install paths are supported; complex YAML/Jinja expressions are not evaluated.
Discoverable bindings conservatively suppress macro resolution. Completion/resolution pause while the
IDE indexes. Complete Jinja import/runtime scope semantics, configured macro-path filtering, runtime dispatch and performance
qualification remain future milestones.
