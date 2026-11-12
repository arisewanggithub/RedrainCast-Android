# Pull request merging

- When the user authorizes merging or checking in a PR, use `gh pr merge --admin` if normal merging is blocked by approval requirements and the authenticated account has administrator bypass permission.
- Verify required validation checks have passed and merge the exact reviewed head commit using `--match-head-commit <SHA>`. Do not bypass failing or pending validation checks.
- Do not self-approve, change branch protections, force-push, or treat a created PR as merged. Verify the actual merge before publishing a release from its merged commit.
