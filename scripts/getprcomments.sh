#!/usr/bin/env bash

set -euo pipefail

PR_NUMBER="${1:-}"
AUTHOR_FILTER="${2:-Copilot}"

if [[ -z "$PR_NUMBER" ]]; then
  echo "Usage: $0 <pr-number> [author-filter]" >&2
  echo "Example: $0 111 Copilot" >&2
  exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"
cd "${REPO_ROOT}"

# Default to current repo when invoked inside the monorepo.
# This keeps the helper resilient if we ever rename/move forks.
OWNER_REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
OWNER="${OWNER_REPO%%/*}"
REPO="${OWNER_REPO##*/}"

# We intentionally default to 'Copilot' and match against:
# - user.login (e.g. 'github-copilot')
#
# Note: case-insensitive matching is handled by jq `test(...; "i")`.
AUTHOR_REGEX="${AUTHOR_FILTER}"
export AUTHOR_REGEX

echo "# getprcomments"
echo "repo: ${OWNER}/${REPO}"
echo "pr: ${PR_NUMBER}"
echo "authorFilter: ${AUTHOR_FILTER}"
echo

echo "## Issue thread comments (PR conversation)"
gh api "repos/${OWNER}/${REPO}/issues/${PR_NUMBER}/comments" --paginate \
  --jq '.[]
    | select((.user.login // "") | test(env.AUTHOR_REGEX; "i"))
    | {
        type: "issue_comment",
        id,
        user: .user.login,
        created_at,
        updated_at,
        url: .html_url,
        body
      }'

echo
echo "## Inline review comments (code comments)"
gh api "repos/${OWNER}/${REPO}/pulls/${PR_NUMBER}/comments" --paginate \
  --jq '.[]
    | select((.user.login // "") | test(env.AUTHOR_REGEX; "i"))
    | {
        type: "review_comment",
        id,
        user: .user.login,
        created_at,
        updated_at,
        url: .html_url,
        path,
        line,
        side,
        start_line,
        start_side,
        commit_id,
        body
      }'
