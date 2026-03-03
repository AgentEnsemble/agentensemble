# Workflow: getprcomments (Copilot PR comments)

## Goal
When the user says something like:

- "getprcomments 111"
- "get Copilot comments for PR 111"

I should fetch **all Copilot-authored PR comments**, including:

1) **Issue thread comments** (PR conversation)
2) **Inline review comments** (code comments)

And then return the **raw, complete bodies** (do not paraphrase unless the user asks).

> Important: `gh pr view <n> --comments` is NOT sufficient; it misses inline review comments.
> Inline comments must be pulled via the Pulls Review Comments API.

You can do this in PLAN mode because it is read-only

## Preferred command (repo-local helper)

From repo root:

```bash
./scripts/getprcomments.sh <PR_NUMBER> Copilot
```

This script calls the two authoritative endpoints with pagination:

- `GET /repos/:owner/:repo/issues/:pr_number/comments` (PR conversation)
- `GET /repos/:owner/:repo/pulls/:pr_number/comments` (inline review comments)

## Manual fallback (if the script is unavailable)

```bash
gh api repos/AgentEnsemble/agentensemble/issues/<PR_NUMBER>/comments --paginate \
  --jq '.[] | select((.user.login // "") | test("copilot"; "i")) | {type:"issue_comment", id, user:.user.login, created_at, body}'

gh api repos/AgentEnsemble/agentensemble/pulls/<PR_NUMBER>/comments --paginate \
  --jq '.[] | select((.user.login // "") | test("copilot"; "i")) | {type:"review_comment", id, user:.user.login, created_at, path, line, body}'
```

## Output requirements
- Include **all matching comments**, not summaries.
- Keep output structured.
  - Prefer JSON objects per comment (JSONL) so it can be parsed.
  - Include at least: `type`, `id`, `user`, `created_at`, `body`, and for review comments: `path` + `line`.

## Common failure modes
- **No inline comments returned**: you likely queried `issues/.../comments` only.
- **Missing older comments**: you forgot `--paginate`.
