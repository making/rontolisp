#!/usr/bin/env bash
# Moves a snapshot tag onto this run's commit, so the release step after it updates the
# release in place (.kb/session-workflow.md, "The snapshot tag"). Writes publish=true|false
# to $GITHUB_OUTPUT; false means this build must not be published, and the job still passes.
#
# Usage: move-snapshot-tag.sh <tag>
# Needs GH_TOKEN with contents: write (the ref) and actions: write (the hand-over dispatch).
set -euo pipefail

tag="$1"
repo="$GITHUB_REPOSITORY"
sha="$GITHUB_SHA"

publish() {
	echo "publish=$1" >>"$GITHUB_OUTPUT"
	exit 0
}

current=$(gh api "repos/$repo/git/ref/tags/$tag" --jq .object.sha 2>/dev/null || true)
if [[ "$current" == "$sha" ]]; then
	publish true
fi
if [[ -n "$current" ]]; then
	# A run that finishes after a newer commit's run must not take the release back.
	if [[ "$(gh api "repos/$repo/compare/$current...$sha" --jq .status)" == behind ]]; then
		echo "::notice::$tag already points at $current, a descendant of $sha; this build is not published"
		publish false
	fi
	if gh api -X PATCH "repos/$repo/git/refs/tags/$tag" -f "sha=$sha" -F force=true >/dev/null; then
		publish true
	fi
elif gh api -X POST "repos/$repo/git/refs" -f "ref=refs/tags/$tag" -f "sha=$sha" >/dev/null; then
	publish true
fi

# Refused. GITHUB_TOKEN never holds the workflows permission, and GitHub refuses it any
# ref at a commit whose .github/workflows has a file added or changed relative to the
# DEFAULT BRANCH TIP (the 403 says only "Resource not accessible by integration"). That
# happens once a workflow change lands on the branch while this run builds. Any other
# refusal is a real failure.
default=$(gh api "repos/$repo" --jq .default_branch)
workflows() {
	gh api "repos/$repo/contents/.github/workflows?ref=$1" --jq '.[] | "\(.name) \(.sha)"' | sort
}
changed=$(comm -23 <(workflows "$sha") <(workflows "$default"))
if [[ -z "$changed" ]]; then
	echo "::error::moving $tag to $sha was refused, and its workflows match $default's tip"
	exit 1
fi
echo "::notice::$tag stays put: a workflow change on $default overtook $sha (${changed//$'\n'/, })"

# Hand over to a run of the tip: a newer run of this workflow publishes in its place;
# with none, dispatch one (a GITHUB_TOKEN dispatch does start a run).
workflow="${GITHUB_WORKFLOW_REF%@*}"
workflow="${workflow##*/}"
newer=$(gh run list --repo "$repo" --workflow "$workflow" --branch "$GITHUB_REF_NAME" --limit 50 \
	--json databaseId,status --jq "[.[] | select(.databaseId > $GITHUB_RUN_ID and .status != \"completed\")] | length")
if [[ "$newer" == 0 ]]; then
	gh workflow run "$workflow" --repo "$repo" --ref "$GITHUB_REF_NAME"
	echo "::notice::dispatched $workflow on $GITHUB_REF_NAME to publish its tip"
fi
publish false
