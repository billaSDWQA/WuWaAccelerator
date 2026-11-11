# Copilot Cross-Review Bridge

This is an isolated, read-only GitHub Actions bridge for ChatGPT ↔ GitHub Copilot technical reviews.

## How it works

1. A repository owner comments on a pull request with `/copilot-review <request>`.
2. The workflow checks out its trusted runner code from the default branch and checks out the PR head into a separate `target/` directory as data.
3. The Copilot SDK starts a fresh session and receives the recent PR discussion as context.
4. Copilot can use only three host-provided tools: list files, read files, and search literal text. No shell, write, URL, MCP, or GitHub mutation tools are exposed.
5. The workflow posts the answer back to the same PR discussion.

Each GitHub Actions run is ephemeral. Earlier discussion is passed into the next session as a bounded transcript; the SDK session itself is not persisted between runs.

## Required setup

After this PR is merged into the repository's default branch:

1. Confirm the GitHub account has an active Copilot plan that permits Copilot SDK/CLI usage.
2. Create a **fine-grained personal access token** for the Copilot user authentication flow. The SDK documents support for `github_pat_` fine-grained tokens and does not support classic `ghp_` tokens.
3. In the repository, open **Settings → Secrets and variables → Actions → New repository secret**.
4. Add a secret named `COPILOT_GITHUB_TOKEN`. Paste the token into GitHub's secret form only; never paste it into ChatGPT, PR comments, source files, or logs.
5. Keep the token's repository permissions at the minimum required. The workflow itself uses its separate short-lived `GITHUB_TOKEN` with only `contents: read`, `pull-requests: read`, and `issues: write`.

The repository is user-owned rather than organization-owned. Therefore, this setup uses user-token authentication and does not assume the organization-only Copilot Requests server-to-server policy is available.

## Trigger

Only comments authored by the repository owner and beginning with `/copilot-review` start a run. Fork PRs are rejected. The workflow has a 20-minute job timeout and one run per PR at a time.

Example:

```text
/copilot-review Re-evaluate the FrameTimeline assumptions. Challenge ChatGPT's claims with source evidence and list unresolved questions.
```

## Security boundaries

- The workflow definition and runner code are checked out from the default branch, not from the PR head.
- PR source is checked out into a separate directory and is never executed.
- The agent receives only custom repository-reading tools with path traversal and symlink escape checks.
- No shell, file-write, network, MCP, or GitHub mutation tools are available to Copilot.
- The Copilot token is passed only to the runner process and is never written to a file or printed.
- The workflow's own comment-posting permission is separate from the Copilot session.
- GitHub Actions comments created with the workflow's `GITHUB_TOKEN` do not recursively trigger another workflow run.

## Validation

Run local helper tests with:

```bash
npm install --prefix tools/copilot-review
npm test --prefix tools/copilot-review
```

The first live Copilot invocation must be tested after the secret is configured. A successful build or unit test does not prove the Copilot account/token is authorized.
