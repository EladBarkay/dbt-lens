# Security policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/EladBarkay/dbt-lens/security/advisories/new),
not in a public issue.

Include the plugin and IDE versions, a minimal reproduction and the affected input (for example a
manifest, YAML file or setting). Don't include credentials, environment-file contents, warehouse data or
unredacted command output.

## Supported versions

Security fixes go to the latest release only. Earlier 0.1.x and 0.2.x versions are kept for reference
and aren't maintained separately.

## What the plugin does and doesn't do

**Editor features** (highlighting, completion, navigation, documentation, saved output) only read
project files and saved dbt artifacts. They don't evaluate Jinja, run Python or dbt, query a warehouse
or send telemetry.

**dbt commands** run only when you click an action. They start your configured local dbt executable
with an argument list (never a shell). Compilation and your project's macros may query your warehouse,
and the command log shows dbt's output, which can include anything your macros print. Settings store
the environment-file *path*, never its values.

**Input limits**: artifact and environment-file reads are size-limited and must stay inside the
project's output directory, symlinks included. Artifacts that change during a read are rejected.

These are input-validation checks, not a sandbox. Only run dbt commands in projects and environments
you trust.
