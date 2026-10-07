# dbt Lens

SQL and Jinja editing for dbt projects in JetBrains IDEs.

An early, installable editor prototype for the production-plugin design. This repository implements
the first parsing/editor milestone and the mixed-language feasibility experiment. It is not the full
dbt project-intelligence release.

## What works in this development build

- Source-preserving Jinja tokens, whitespace control, comments, raw blocks and restartable lexing.
- Nested template blocks and macro declarations with parameter names/default expressions.
- Expression PSI for names, strings, numbers, calls, keyword arguments, attributes, indexing,
  collections, arithmetic/comparison operators, filters and tests.
- Error recovery at subsequent Jinja tags, missing block/delimiter diagnostics and brace matching.
- Independent Jinja highlighting and basic SQL keyword/string/comment/number coloring in template text.
- Persistent folding for macro/block bodies, elif/else bodies, multiline tags, calls and collections.
  Blocks with multiline headers collapse from their first line, including the body.
- Folding for multiline Jinja comments and SQL block comments.
- Go to Declaration on closing tags (such as `endmacro`, `endif`, `endfor` and `endset`) navigates to
  the matching opener, including nested blocks. On macOS, use ⌘B or ⌘-click on the closing keyword.
- SQL file icons, with a bundled SQL icon when the host does not supply one.
- Automatic activation in ordinary `.sql` files inside a dbt project, including TextMate and plain-text
  hosts without Database Tools.
- Function call/declaration coloring, completion for dbt context names, project macros and parameters.
- Go to Declaration and Find Usages for static calls to top-level macros in the current dbt project,
  including qualified calls using its literal package name (for example `elementary.edr_type_timestamp()`).
  Calls in set assignments, if/elif conditions, for iterables/conditions and do statements are included.
  Duplicate declarations return multiple candidates. Filters, parameter-shadowed names and discoverable
  statement bindings are excluded. Other package namespaces and dynamic dispatch are still deferred.

Macro signatures and supported statement operands have expression PSI. Other statement headers retain
a balanced token structure; full statement grammar and lexical-scope semantics remain incomplete. This is a Jinja editing subset,
not runtime equivalence. Conditional expressions, call argument unpacking, slice syntax and extension
semantics still need coverage. SQL coloring is lexical, without dialect-specific assistance; SQL strings
and comments crossing a Jinja hole are not yet context-aware.

## Open a template

The plugin automatically activates Jinja editing for `.sql` files underneath `dbt_project.yml` within
an IDE project content root. Database Tools are optional. The global file association stays intact,
so native SQL/TextMate hosts can still display **SQL** or **TextMate** as their file type.
PyCharm's legacy SQL syntax-table files and otherwise unclaimed SQL files receive a **dbt Jinja**
file type beneath a dbt marker. This fallback also works when commercial modules are disabled.
Default generated directories `target`, `logs` and `.git` are excluded. SQL files outside dbt project
roots keep their normal SQL editing behavior. Custom output-directory configuration is not yet parsed.

The plugin also associates `*.dbt.sql`, `*.jinja` and `*.j2`. Open
[examples/local-macros.dbt.sql](examples/local-macros.dbt.sql) to try completion, navigation and usages.
For a file without a detected dbt root, use **Override File Type** and
choose **dbt Jinja**. After updating to dbt Lens 0.1.5, restart the IDE and reopen the model to refresh its
existing language selection.

The plugin keeps its original `dev.dbt.jinja` ID so dbt Lens upgrades existing installations.
Its internal language and file-type name remains **dbt Jinja**.

## Build and test

Requires a **JDK 21** and network access for the first build. The Gradle wrapper is pinned to 9.0.0
with a distribution checksum; the Kotlin compiler is 2.3.21 (language/API level 2.2, Java 21 output)
and the IntelliJ Platform Gradle plugin is 2.19.0.
The default SDK is IntelliJ IDEA 2025.3.3. No Python interpreter or dbt installation is needed.

```sh
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure buildPlugin
```

For binary compatibility and API checks against the same SDK, run `./gradlew verifyPlugin`.
Use `-PverifierOffline=true` to restrict the verifier to already downloaded dependencies; the validation
record describes the limits of the offline result.

The installation ZIP appears at `build/distributions/dbt-lens-0.1.5.zip`. Use **Settings → Plugins → Install Plugin
from Disk** to install it in a compatible IDE, or `./gradlew runIde` to start a separate development IDE.
The compatibility range in the descriptor is builds 253–262, including PyCharm 2026.2. Only versions
actually exercised in [docs/feasibility.md](docs/feasibility.md) are validated; this development build is
not published to the JetBrains Marketplace.

To use an installed SDK and runtime, supply paths explicitly (on macOS, SDK paths end in `Contents`):

```sh
./gradlew test buildPlugin \
  -PlocalPlatformPath='/path/to/IDE.app/Contents' \
  -PlocalRuntimePath='/path/to/jdk/Contents/Home'
```

An SDK outside the proposed range is a development experiment, not a compatibility claim.

To check a locally installed IDE while continuing to compile against the pinned IntelliJ SDK:

```sh
./gradlew verifyPlugin -PverifierOffline=true \
  -PverificationIdePath='/Applications/PyCharm.app/Contents'
```

PyCharm 2026.2 requires Java 25 at runtime. The plugin itself remains compiled for Java 21 so it also
works with the original IntelliJ 2025.3 baseline.
Building directly against the PyCharm 2026.2 SDK also requires a full JDK 25; its bundled runtime alone
does not include the compiler. Use `-PwithTextmateTests=false` to exercise plain-text SQL with both
TextMate and database test dependencies absent. TextMate regression tests run by default.
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

## Deferred work

Configuration-aware resource discovery; dependency-package resolution; model/source/YAML
navigation; manifest ingestion; complete scope-aware resolution; resource completion; documentation and
parameter information; argument/resource inspections; safe rename; compiled SQL previews and dbt execution.
Rename is explicitly rejected until the project-wide reference model exists. No project code or dbt command
is executed to provide editor features.

See [docs/feasibility.md](docs/feasibility.md) for the integration gate and the next milestones.

## Version review workflow

`main` initially contains repository basics. The implementation is reviewed as stacked PRs:
`0.1.0` → `main`, `0.1.1` → `0.1.0`, `0.1.2` → `0.1.1`, `0.1.3` → `0.1.2`, `0.1.4` → `0.1.3`,
and `0.1.5` → `0.1.4`.
Review each PR against its immediate predecessor. Earlier development versions retain their original
names and compatibility ranges; dbt Lens branding is introduced in 0.1.4.

Versions 0.1.0–0.1.3 were recovered from the original editing history because Git was initialized at
0.1.3. The recovered 0.1.3 tree matches the original Git baseline, and rebuilt historical classes
are checked against the retained development packages. These are reconstructed commits, not the
original timestamps of development. The previous Git history is preserved on `archive/pre-stack`.
