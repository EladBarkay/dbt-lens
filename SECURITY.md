# Security

Report suspected vulnerabilities privately to the repository owner. Include the plugin/IDE version,
a minimal reproduction and the affected input boundary. Do not include credentials, environment-file
contents, warehouse data or unredacted command output in a public report.

Security fixes target the latest maintained release. Historical 0.1.x and 0.2.x checkpoints are retained
for review and comparison; they do not receive independent maintenance branches.

## Execution and data boundaries

Editor features parse project files and optional saved artifacts locally. They do not evaluate Jinja,
execute Python/dbt, query a warehouse or send telemetry. Static resolution is intentionally conservative.

Explicit CLI actions execute the selected local dbt executable with argument vectors. Compilation and
project macros may query the configured warehouse. Project settings store environment-file paths, not
secret values. Command output contains dbt's own messages and may include data printed by project macros.

Artifact and environment reads have size limits. Artifact paths remain within the selected project's
output directory, including symlink checks. External processes can replace files during reads; changed
artifact generations are rejected and readers enforce their byte limits throughout the read.

These checks are input-validation boundaries, not a sandbox for an executable or a trusted project's
macros. Only run dbt commands in projects/environments you trust.
