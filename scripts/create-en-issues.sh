#!/usr/bin/env bash
# Create GitHub issues from the EN-* markdown files in docs/issues/
#
# Usage:
#   ./scripts/create-en-issues.sh              # create all 30 issues
#   ./scripts/create-en-issues.sh --dry-run    # preview without creating
#   ./scripts/create-en-issues.sh --phase 1    # create Phase 1 issues only (EN-001 to EN-009)
#
# Prerequisites:
#   - gh CLI installed and authenticated (gh auth login)
#   - Run from the repo root
#
# Each issue gets:
#   - Title from the markdown H1 heading
#   - Body from the full markdown file content
#   - Label: ensemble-network
#   - Label: phase-N (derived from the Phase line in the file)

set -euo pipefail

DRY_RUN=false
PHASE_FILTER=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --phase)   PHASE_FILTER="$2"; shift 2 ;;
        *)         echo "Unknown option: $1"; exit 1 ;;
    esac
done

ISSUES_DIR="docs/issues"
CREATED=0
SKIPPED=0

if [ ! -d "$ISSUES_DIR" ]; then
    echo "Error: $ISSUES_DIR not found. Run from the repo root."
    exit 1
fi

# Ensure labels exist (create if missing, ignore errors if they already exist)
if [ "$DRY_RUN" = false ]; then
    gh label create "ensemble-network" --description "v3.0.0 Ensemble Network" --color "1d76db" 2>/dev/null || true
    gh label create "phase-1" --description "Phase 1: Foundation" --color "0e8a16" 2>/dev/null || true
    gh label create "phase-2" --description "Phase 2: Durable Transport" --color "fbca04" 2>/dev/null || true
    gh label create "phase-3" --description "Phase 3: Human + Observability" --color "d93f0b" 2>/dev/null || true
    gh label create "phase-4" --description "Phase 4: Advanced" --color "5319e7" 2>/dev/null || true
fi

for file in "$ISSUES_DIR"/EN-*.md; do
    # Extract issue number (e.g., EN-001)
    basename=$(basename "$file" .md)
    issue_num="${basename%%-*}-${basename#*-}"
    issue_num="${basename%%_*}"

    # Extract title from first line (strip "# ")
    title=$(head -1 "$file" | sed 's/^# //')

    # Extract phase number from the Phase line
    phase_num=$(grep "^\*\*Phase\*\*:" "$file" | head -1 | grep -o '[0-9]' | head -1)

    # Filter by phase if requested
    if [ -n "$PHASE_FILTER" ] && [ "$phase_num" != "$PHASE_FILTER" ]; then
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    labels="ensemble-network,phase-${phase_num}"

    if [ "$DRY_RUN" = true ]; then
        echo "[DRY RUN] Would create: $title"
        echo "          Labels: $labels"
        echo "          File: $file"
        echo ""
    else
        echo "Creating: $title ..."
        gh issue create \
            --title "$title" \
            --body-file "$file" \
            --label "$labels"
        echo "  Created."
        echo ""
    fi

    CREATED=$((CREATED + 1))
done

echo "---"
echo "Done. Created: $CREATED, Skipped: $SKIPPED"
if [ "$DRY_RUN" = true ]; then
    echo "(Dry run -- no issues were actually created)"
fi
