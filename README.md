# dbt Lens

SQL and Jinja editing for dbt projects in JetBrains IDEs.

Version 1.0.0 combines offline Jinja editing, project/package/resource navigation, optional saved
artifact documentation and lineage, and explicit local dbt compilation. It works in IntelliJ IDEA
and PyCharm, including configurations without Database Tools or native YAML support.

The default editor provides lexical SQL coloring. **Settings → dbt Lens → Keep the native SQL editor**
retains JetBrains dialect assistance and disables dbt Lens Jinja editing in ordinary SQL files.
Simultaneous native SQL/Jinja assistance remains experimental.

## Get started

1. Build the plugin or obtain `dbt-lens-1.0.0.zip` from a passing CI run. Install it through
   **Settings → Plugins → Install Plugin from Disk**, then restart the IDE.
2. Open an IDE project containing `dbt_project.yml`, then open a model's `.sql` file. Jinja assistance
   activates automatically. Use completion, Go to Declaration and Quick Documentation as usual.
3. Open **View → Tool Windows → dbt Lens** for saved compiled SQL, documentation and lineage.
   These views work with an existing manifest without a dbt installation.
4. For CLI actions, choose the executable and optional profiles/target under **Settings → dbt Lens**.
   **Generate manifest** creates metadata; **Compile current** or **Compile project** generates SQL.
   Compilation can query the configured warehouse. Commands run only after an explicit action.

## Capabilities

- Source-preserving Jinja tokens, whitespace control, comments, raw blocks and restartable lexing.
- Nested template blocks and macro declarations with parameter names/default expressions.
- Expression PSI for names, strings, numbers, calls, keyword arguments, attributes, indexing,
  collections, arithmetic/comparison operators, filters and tests.
- Error recovery at subsequent Jinja tags, missing block/delimiter diagnostics and brace matching.
- Independent Jinja highlighting and basic SQL keyword/string/comment/number coloring in template text.
- Persistent folding for macro/block bodies, elif/else bodies, multiline tags, calls and collections.
  Macro folding keeps the entire signature visible and collapses only the body. Other structural
  blocks with multiline headers retain their first-line controls and closing-delimiter placeholders.
- Folding for multiline Jinja comments and SQL block comments.
- Go to Declaration on closing tags (such as `endmacro`, `endif`, `endfor` and `endset`) navigates to
  the matching opener, including nested blocks. On macOS, use ⌘B or ⌘-click on the closing keyword.
- The supplied dbt mark for configuration/property YAML and a code-badged variant for SQL in dbt
  projects. Ordinary SQL outside dbt projects retains its host icon.
- Automatic activation in ordinary `.sql` files inside a dbt project, including TextMate and plain-text
  hosts without Database Tools.
- A restrained palette: white variables and function usages, blue declarations, muted red keyword
  argument labels, and subdued violet dbt context globals. Categories remain configurable.
  Change them under **Settings → Editor → Color Scheme → dbt Lens**.
- Scope-aware completion for parameters, preceding set bindings, loop/with/call bindings, and contextual
  `loop`, `caller`, `kwargs`, and `varargs`. Filters and tests have separate completion catalogs.
- Static member suggestions for literal/inferred dictionaries, strings, lists, namespaces and dbt objects,
  including chained expressions such as `data.get('name').split(...)`, `graph.nodes.items()` and
  `api.Relation.create(...).include(...)`. Unknown receivers do not receive guessed methods.
- Completion signatures, macro/builtin keyword argument labels, call parentheses, and member/filter popups.
  Keyword labels already supplied are excluded; known positional-only parameters are excluded.
- Typed Jinja opener pairs (`{%` → `%}`, `{{` → `}}`, `{#` → `#}`), with overtyping of existing closers.
  Strings/raw blocks and ordinary non-template files are excluded. This respects the IDE's bracket-pair setting.
- Accept a tag completion such as `macro`, `if`, `for`, `with`, or `call` to insert an editable block template.
  Tab moves through its placeholders. Enter after a complete opening header at the end of the file adds
  its missing closing tag. Capture `set` headers receive `endset`; assignment `set` headers do not.
  Existing complete enclosures are not duplicated.
- Quick Documentation/hover for macros, using the adjacent Jinja or SQL comment immediately above their
  declaration. Comment text is escaped and displayed as plain text; Jinja is never executed.
- Static local-variable navigation and Find Usages/highlight usages from declarations. Reassignments in
  the same scope share usage searches; nested loop/with scopes remain separate. Go to Declaration on a
  read chooses its latest known definition. Branch-dependent definitions remain multiple candidates.
- Builtin/member hover documentation and special class highlighting for `api.Relation` and `api.Column`.
- Optional saved-artifact support: compiled SQL preview, resource/macro documentation and a focused
  upstream/downstream lineage view in the **dbt Lens** tool window.
- Explicit local CLI actions to generate a manifest, compile the current SQL resource or project, and
  compile a macro invocation with arguments. Settings select the executable, profiles, target and
  optional environment file; progress, cancellation and command output are shown in the tool window.
- Native YAML completion/validation and dbt icons for project/profile/package/dependency/selector files
  and resource property YAML. YAML retains its native file type and parser.
- Go to Declaration and Find Usages for static calls to top-level macros in the current dbt project and installed dependencies,
  including qualified calls using literal package names (for example `elementary.edr_type_timestamp()`).
  Calls in set assignments, if/elif conditions, for iterables/conditions and do statements are included.
  Duplicate declarations return multiple candidates. Bare pipeline filters are classified as callable
  usages. Unknown filters can navigate to a matching macro, but this is a static convenience, not proof
  of registration in Jinja's separate filter registry. Built-in filters/tests are not misresolved to
  same-named macros. Parameter/set/loop bindings take precedence for ordinary name references; macro
  callbacks passed as values also navigate. Installed dependencies are discovered under
  `dbt_packages` or a literal `packages-install-path`, using each dependency's `dbt_project.yml` name.
  Excluded package folders have a cached token catalog, so ordinary IDE indexing exclusions do not
  prevent navigation. Unqualified calls prefer the current project; conflicting dependency definitions
  remain ambiguous. Dispatch with dynamic arguments and runtime-generated namespaces remains deferred.

Macro signatures and supported statement operands have expression PSI. Other statement headers retain
a balanced token structure; full statement grammar and lexical-scope semantics remain incomplete. This is a Jinja editing subset,
not runtime equivalence. Inline conditionals (`value if condition else alternative`, including nested
alternatives and optional else) are supported, alongside filtered for loops, call argument unpacking,
slices, numeric underscores, `+` whitespace controls, with/call/test bindings and dbt docs blocks.
Break/continue tokens are recognized for dbt's loopcontrols extension; extension execution and complete
statement validation remain outside the parser's current scope. SQL coloring is lexical, without dialect-specific assistance; SQL strings
and comments crossing a Jinja hole are not yet context-aware.

Member inference is deliberately shallow: literals, simple aliases/defaults, collection iteration, capture
blocks, common filter results and documented dbt return types. It does not execute Python/Jinja, inspect a
live adapter, guarantee types at runtime, or infer arbitrary macro return values. Rich Agate assistance,
YAML macro argument types, imported symbols and environment/version-specific catalogs remain deferred.
Some context variables, such as `results` and `schemas`, are only available in hooks; suggestions are hints.
The catalog is grounded in the official [Jinja syntax reference](https://jinja.palletsprojects.com/en/stable/templates/),
[dbt Jinja reference](https://docs.getdbt.com/reference/dbt-jinja-functions),
[dbt classes](https://docs.getdbt.com/reference/dbt-classes),
[adapter API](https://docs.getdbt.com/reference/dbt-jinja-functions/adapter), and
[Python builtin types](https://docs.python.org/3/library/stdtypes.html), and the
[dbt Core 1.10.6 context implementation](https://github.com/dbt-labs/dbt-core/tree/v1.10.6/core/dbt/context).
Python method signatures use the common compatibility subset (for example, `str.replace` keyword
`count` support added in Python 3.13 is not yet offered).

## Open a template

The plugin automatically activates Jinja editing for `.sql` files underneath `dbt_project.yml` within
an IDE project content root. Database Tools are optional. The global file association stays intact,
so native SQL/TextMate hosts can still display **SQL** or **TextMate** as their file type.
PyCharm's legacy SQL syntax-table files and otherwise unclaimed SQL files receive a **dbt Jinja**
file type beneath a dbt marker. This fallback also works when commercial modules are disabled.
Default generated directories `target`, `logs` and `.git` are excluded. SQL files outside dbt project
roots keep their normal SQL editing behavior. Virtual environments and `node_modules` are also excluded.
Custom output directories are not yet excluded from editor discovery. Dependencies are only read; dbt commands and
project code are never executed for editor assistance. The name index tokenizes candidate template files
independently of file type; semantic assistance is scoped to the detected dbt root and its installed packages.

The plugin also associates `*.dbt.sql`, `*.jinja` and `*.j2`. Open
[examples/local-macros.dbt.sql](examples/local-macros.dbt.sql) to try completion, navigation and usages.
For a file without a detected dbt root, use **Override File Type** and
choose **dbt Jinja**. After updating to dbt Lens 1.0.0, restart the IDE and reopen the model to refresh its
existing language selection.

The plugin keeps its original `dev.dbt.jinja` ID so dbt Lens upgrades existing installations.
Its internal language and file-type name remains **dbt Jinja**.

## YAML assistance

With JetBrains JSON and YAML support installed, dbt Lens applies bundled schemas to root-level
`dbt_project.yml`, `profiles.yml`, `packages.yml`, `dependencies.yml`, and `selectors.yml` (also `.yaml`
for those configuration names). Resource YAML under the standard model/seed/snapshot/test/analysis/macro
folders receives the dbt properties schema; literal inline or indented block path lists in `dbt_project.yml` override those
folder defaults. Unrelated YAML, non-dbt projects and generated files keep their normal behavior.
The standard `~/.dbt/profiles.yml` also receives assistance when a dbt root is an IDE content root.

Project, packages, dependencies, selectors and properties schemas come from
[dbt Labs' official schema repository](https://github.com/dbt-labs/dbt-jsonschema), pinned at
`6a401244651d3b5e0fd2f7cfcfb120187118edb4` with its Apache-2.0 license. They are bundled with no
external schema references, so they work offline. Profiles use a dbt Lens schema for the common structure;
adapter-specific connection keys remain open and Jinja string values are accepted. These are schema
editor checks, not adapter/runtime validation. User schema mappings retain JetBrains' normal precedence.
No YAML-language replacement or Jinja execution is involved.

## Resource navigation

Go to Declaration on a literal `ref`/`source` call or its string arguments opens the resource definition.
`ref` finds SQL/Python models, CSV seeds and SQL/YAML snapshots, including installed dependencies and
`ref('package', 'model')`. Integer or quoted `v`/`version` arguments and model YAML `latest_version`/`defined_in`
select a versioned model; YAML supplies a definition target when the implementation file is missing.
`source('logical_source', 'table')` opens the table's YAML name, rather than its physical identifier.
With native YAML installed, exposure `depends_on` calls and wrapped Jinja calls in dbt property YAML
also navigate; `metric` links to metric YAML definitions. Exposures themselves are not `ref` targets.

Discovery reads literal configured resource folders, including inline and block path lists. Current
project resources take precedence over dependencies; duplicates remain multiple navigation candidates.
Without `latest_version`, version inference currently supports integer identifiers; other version schemes
need an explicit `latest_version`. Dynamic expressions, unresolved versions and shadowed calls stay unresolved. Generated/unrelated
folders are excluded. Resource catalogs update after committed unsaved edits and VFS changes, use
cancellation checks, and limit YAML/snapshot reads to 2 MB. YAML metadata is optional; SQL models,
seeds and SQL snapshots resolve without the YAML plugin. No manifest, adapter or dbt execution is needed.
Resource completion, Find Usages/rename and runtime-generated resource names remain deferred.

## Adapter dispatch navigation

On a literal `adapter.dispatch('format_value', 'utils')` call, use ⌘B/⌘-click on `dispatch` or the
macro-name string to choose among discovered `default__format_value`, `snowflake__format_value`,
`postgres__format_value`, and other implementations. A single result navigates directly. Defaults appear
first within each package; package order follows literal root-project `dispatch.search_order` when
native YAML is available. Without an override, a named package searches the root project then that
package. Installed packages' own dispatch configurations are ignored. Unqualified dispatch offers
implementations from reachable project/dependency namespaces. Dependencies in excluded/custom install
folders are supported, and committed unsaved source/config edits refresh results.

On an adapter-specific declaration such as `snowflake__format_value`, Go to Declaration opens its
package's `default__format_value`; if that package has none, it offers visible dependency defaults.
Find Usages on any implementation includes matching SQL/Jinja dispatch call sites and ordinary direct calls.
Namespace strings are not counted again as macro usages. Keyword arguments `macro_name=` and
`macro_namespace=` are also supported. Shadowed adapters, dynamic/unpacked arguments, malformed calls,
and unrelated dbt projects are excluded.

These are **available implementations and possible call sites**, not a prediction of the implementation
selected by the current adapter. Adapter profiles/inheritance, runtime registration and built-in dbt
macros absent from installed source are not inferred. Unsupported dynamic/alias search-order values
stay unresolved. Without native YAML, named dispatch with a root-level dispatch config stays unresolved.
See the official [dispatch rules](https://docs.getdbt.com/reference/dbt-jinja-functions/dispatch) and
[search-order configuration](https://docs.getdbt.com/reference/project-configs/dispatch-config).

## SQL editing modes

The default Jinja editor uses dialect-independent **lexical SQL coloring**. It does not provide native
SQL dialect parsing, completion or inspections, and it substitutes the editor language within detected
dbt SQL files. SQL file associations remain owned by the IDE.

To retain JetBrains' full SQL editor and its existing dialect/data-source settings, open
**Settings → Languages & Frameworks → dbt Lens** and enable **Keep the native SQL editor in dbt projects**.
Restart the IDE after changing it. This mode leaves native SQL files to JetBrains; dbt Lens's Jinja
features in those `.sql` files are disabled. Explicit `.jinja`/`.j2`/`.dbt.sql` templates remain Jinja editors.
Simultaneous native SQL assistance and complete Jinja editing remain a separate integration milestone.

## Build and test

Requires a **JDK 21** and network access for the first build. The Gradle wrapper is pinned to 9.3.0
with a distribution checksum; the Kotlin compiler is 2.3.21 (language/API level 2.2, Java 21 output)
and the IntelliJ Platform Gradle plugin is 2.19.0.
The default SDK is IntelliJ IDEA 2025.3.3. Use `./gradlew ktfmtFormat` to apply the pinned Kotlin style. No dbt installation is needed for the IDE suites;
the CLI process fixtures use `/usr/bin/python3` on Unix hosts. Packaging does not require Python or dbt.
The build resolves its matching JetBrains Runtime automatically, unless a local runtime is supplied.

```sh
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure buildPlugin
```

For binary compatibility and API checks against the same SDK, run `./gradlew verifyPlugin`.
Add `-PverifySupportedIdes=true` to also verify the pinned PyCharm version. CI runs both IDEs and
a minimal host with SQL, TextMate, JSON/YAML and commercial modules disabled.
Use `-PverifierOffline=true` to restrict the verifier to already downloaded dependencies; the validation
record describes the limits of the offline result.

The installation ZIP appears at `build/distributions/dbt-lens-1.0.0.zip`. Use **Settings → Plugins → Install Plugin
from Disk** to install it in a compatible IDE, or `./gradlew runIde` to start a separate development IDE.
The minimum IDE build is 253. CI exercises IntelliJ IDEA 2025.3.3 and PyCharm 2026.2.3; newer IDEs
can install the plugin, but compatibility claims cover the versions actually tested. Qualification
is recorded in [docs/feasibility.md](docs/feasibility.md). Marketplace publication is set up as described in
[CONTRIBUTING.md](CONTRIBUTING.md#marketplace-publication); the first upload is a manual, reviewed step.

To use an installed SDK and runtime, supply paths explicitly (on macOS, SDK paths end in `Contents`):

```sh
./gradlew test buildPlugin \
  -PlocalPlatformPath='/path/to/IDE.app/Contents' \
  -PlocalRuntimePath='/path/to/jdk/Contents/Home'
```

An SDK outside the tested versions is a development experiment, not a compatibility claim.

To check a locally installed IDE while continuing to compile against the pinned IntelliJ SDK:

```sh
./gradlew verifyPlugin -PverifierOffline=true \
  -PverificationIdePath='/Applications/PyCharm.app/Contents'
```

When running verification with Java 25, also install Java 21 for baseline compilation. If Gradle
cannot discover both installations, supply their paths with
`-Porg.gradle.java.installations.paths=/path/to/jdk21,/path/to/jdk25`.

PyCharm 2026.2 requires Java 25 at runtime. The plugin itself remains compiled for Java 21 so it also
works with the original IntelliJ 2025.3 baseline.
Building directly against the PyCharm 2026.2 SDK requires a Java 25 runtime; the installed bundled
runtime includes the compiler used by this build. Use `-PwithTextmateTests=false` to exercise plain-text SQL with both
TextMate and database test dependencies absent. TextMate regression tests run by default.
Use `-PwithYamlTests=false` to also disable JSON/YAML support and omit schema integration tests.
The test sandbox explicitly disables Database Tools unless `-PwithSqlTests=true`; omitting its
dependency alone would still allow the bundled plugin to load. Add `-PfreeEditionTests=true` when
testing PyCharm to disable commercial modules as well.

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

## Saved dbt output

Open a dbt resource file, then **View → Tool Windows → dbt Lens**. The panel reads `manifest.json`
and optional `catalog.json` from that project's `target` directory, or a literal `target-path` inside
the project. Installed dependency files use the host project's output. A selector distinguishes
multiple macros or YAML resources defined in the same file.

- **Compiled SQL** shows saved `compiled_code`/`compiled_sql`, with a generated-file fallback. The viewer
  uses that fallback only when the manifest identifies the resource as compiled, avoiding old files
  left behind by parse or a partial compile.
  The viewer is read-only and never changes source or output files. Resources without compiled output receive a
  clear message. Macro definitions and Python model code are not displayed as compiled SQL.
- **Documentation** uses the IDE font/theme with wrapped text, labeled column tables and one test per
  list item. It adds saved descriptions, documented macro arguments, columns/types, physical relation
  names and associated tests. Existing source comments remain first in macro hover documentation;
  literal `ref`/`source` calls can show resource metadata through the existing provider. Descriptions
  are escaped plain text; Markdown/Jinja is not evaluated. Catalog generation errors are labeled.
- **Lineage** shows saved dependencies, including macro relationships. Choose upstream, downstream or
  both, at depth 1–3. Click a node to open its local definition. The graph is capped at 80 resources;
  this is resource lineage, not column lineage.

Use **Generate manifest** to run `dbt parse`, **Compile current** for the selected SQL resource, or
**Compile project** for the whole project. Completion refreshes the artifact panel automatically.
**Command output** shows readable dbt logs; **Cancel** stops the running process. Only one command runs
per IDE project. Commands save pending source/configuration edits within the selected dbt root first.
Opening files, indexing and hover documentation never start dbt commands.
The actions follow the official [parse](https://docs.getdbt.com/reference/commands/parse) and
[compile](https://docs.getdbt.com/reference/commands/compile) workflows.

For a macro file, **Compile current** opens a call dialog. Choose a macro and supply its invocation and
arguments, for example `{{ elementary.my_macro('orders') }}`. This uses `dbt compile --inline`; its
result appears in the macro's Compiled SQL tab. Macros that emit SQL and perform introspective queries
are supported through dbt itself. Compilation may query the selected warehouse. It does not run a model
or invoke `dbt run-operation`; macros that primarily perform operations may produce no useful SQL.

`dbt parse` generates metadata without compiling resources; it can replace previously compiled output.
Compile again when you need a SQL preview. Existing output from external dbt commands is still accepted
with **Refresh saved output**. Run `dbt docs generate` externally for optional catalog data; catalog
creation, dependency installation, model execution and live data preview are not implemented.

Saved timestamps are displayed. SQL/Jinja source differences, including unsaved edits, receive a
staleness warning. Other changes—configuration, dependencies or warehouse state—can also invalidate
output and are not fully detected. Cached reads follow artifact file changes; opening a resource or
refreshing the panel reloads it. An initial hover on the UI thread may show artifact details on the
next hover after its background read finishes. Source navigation keeps working without artifacts.

Manifest schemas v8–v12 and catalog v1 are accepted. Missing, malformed, unsupported or mismatched
artifacts receive a readable status. Reads are bounded at 64 MB per artifact and 100,000 resources;
saved compiled text is limited to 2 million characters and generated SQL files to 2 million bytes.
External/dynamic output paths and symlinks outside the project's
output directory are rejected. JSON parsing and preview loading run in the background. Native SQL
coloring is used when available, but the detached viewer does not inherit source-file dialect mappings.

Reading saved JSON needs no dbt installation. To execute commands, configure **Settings → dbt Lens**:

- **dbt executable**: blank searches `.venv`/`venv` in the selected project, then the IDE's PATH. An explicit
  executable path supports other environments. The plugin does not install dbt or activate shell scripts.
- **Profiles directory**, **Profile override**, and **Target** are optional. Blank values use dbt defaults.
  For example, the project profile selects a `profiles.yml` entry whose `dev`/`prod` outputs may connect
  to different warehouses. Settings apply to the IDE project; relative paths resolve from each dbt root.
- **Environment file**: optional UTF-8 literal `NAME=value` entries, with optional `export`, quotes and
  comments. Values supplement the IDE environment. Shell commands, variable expansion, escape processing
  and multiline values are not evaluated. The plugin saves only the file path, not its contents.

The executable runs locally with arguments passed directly, without a shell. Core **1.5+** supplies
inline compilation; output remains subject to the supported manifest schema range. Select a dbt version
compatible with your adapters. Remote/WSL execution and interactive authentication prompts are not
supported by this workflow; configure the local environment before running commands. The command log
shows dbt's own messages, which may include details printed by your project macros.

## Deferred work

Complete configuration-aware resource discovery; complete scope-aware resolution; resource completion;
parameter information; argument/resource inspections; safe rename; automatic live compilation and broader dbt execution actions.
Rename is explicitly rejected until the project-wide reference model exists. Editor assistance does not
execute project code or dbt commands; CLI actions are explicit.

See [docs/feasibility.md](docs/feasibility.md) for the integration gate and the next milestones.

## Releases and maintenance

The original 0.1.0–0.1.12 development PRs are consolidated into five checkpoints:

- **0.2.0**: editor foundations, automatic activation, folding and enclosure navigation (#2–#7).
- **0.2.1**: package/resource navigation, macro documentation and YAML assistance (#8–#9).
- **0.2.2**: scoped usages, smart completion/templates and adapter dispatch (#10–#11).
- **0.2.3**: saved output, documentation and lineage with native styling (#12–#13).
- **0.2.4**: explicit local manifest generation and compilation (#14).

Each checkpoint has its own version, consolidated PR and `v0.2.x` tag. Historical development commits
are retained; their original PRs are merged. **1.0.0** adds the full project review fixes and repeatable
maintenance/release checks. See [CHANGELOG.md](CHANGELOG.md), [the release review](docs/release-review.md)
and [CONTRIBUTING.md](CONTRIBUTING.md).

Version tags trigger the complete CI matrix and binary verifier, then create a **draft** GitHub release
with the checked ZIP and SHA-256 checksum. Publishing the draft is a separate maintainer action; it
signs that same ZIP and uploads it to the JetBrains Marketplace (pre-releases go to the `eap` channel).
Tag names must match the packaged version and point to a commit in main.

The project uses [Apache-2.0](LICENSE). Bundled schemas retain their upstream license and provenance;
see [NOTICE](NOTICE). dbt Lens is an independent project and is not endorsed by dbt Labs or JetBrains.
