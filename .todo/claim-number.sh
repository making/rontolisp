#!/usr/bin/env bash
# Claim the next todo number(s) atomically, so two sessions never pick the same one.
#
#   .todo/claim-number.sh "<why>" [count]
#
# Prints the claimed number(s), one per line. The claim is the push: the counter
# lives in ONE file on the orphan branch `todo-seq`, so two sessions racing for a
# number race for the same file, and git rejects the loser instead of merging both
# (which is exactly what two differently-named .todo/NNN-*.md files do). The loser
# re-reads and retries.
#
# Uses plumbing only -- no checkout, no index, no stash. Safe from any worktree,
# with uncommitted changes, mid-task. `todo-seq` shares no history with develop
# and must never be merged into it.
#
# The counter is authoritative but not omniscient: a session that has not adopted
# this script can still file a number behind its back (that happened the hour the
# branch was created). So each claim also reads what develop actually uses -- live
# `.todo/NNN-*.md` files, the artefact directories under `.todo/artefacts/` that
# outlive them, AND the numbers recorded in `.todo/history/` -- and skips past
# anything already taken, healing the counter in the same push. That check is what
# makes a manual cross-check afterwards unnecessary.
#
# A number is always three characters. Past 999 the first character continues 0-9
# with a-z (999 -> a00, a99 -> b00, ... up to z99), so names still sort in claim
# order and no existing reference changes width. `NEXT` holds the plain decimal
# ordinal (1000 is a00).
set -euo pipefail

digits=0123456789abcdefghijklmnopqrstuvwxyz

# a05 -> 1005
decode() {
	local head=${digits%%"${1:0:1}"*}
	echo $((${#head} * 100 + 10#${1:1:2}))
}

# 1005 -> a05
encode() {
	if [ "$1" -ge 3600 ]; then
		echo "claim-number.sh: $1 is past z99, the last three-character number" >&2
		exit 1
	fi
	printf '%s%02d\n' "${digits:$(($1 / 100)):1}" "$(($1 % 100))"
}

reason=${1:?usage: claim-number.sh "<why>" [count]}
count=${2:-1}
ref=refs/remotes/origin/todo-seq

for attempt in $(seq 1 10); do
	git fetch -q origin "+refs/heads/todo-seq:$ref"
	base=$(git rev-parse "$ref")
	cur=$(git show "$ref:NEXT" | tr -d '[:space:]')

	# Skip past any number develop already uses -- live file, artefact directory
	# or history row.
	git fetch -q origin '+refs/heads/develop:refs/remotes/origin/develop'
	# Anchor both: a title can carry three digits of its own, and an unanchored
	# match would read `700-the-step-at-batch-999-regresses.md` as 999 and burn
	# every number in between.
	used=$( {
		git ls-tree --name-only origin/develop .todo/ | sed 's|^\.todo/||' | grep -oE '^[0-9a-z][0-9]{2}-' || true
		git ls-tree --name-only origin/develop .todo/artefacts/ | sed 's|^\.todo/artefacts/||' \
			| grep -oE '^[0-9a-z][0-9]{2}-' || true
		git grep -h -oE '\.todo/[0-9a-z][0-9]{2}' origin/develop -- .todo/history/ | sed 's|^\.todo/||' || true
	} | cut -c1-3 | while read -r n; do decode "$n"; done | sort -n | tail -1)
	if [ -n "$used" ] && [ "$used" -ge "$cur" ]; then
		cur=$((used + 1))
	fi

	next=$((cur + count))

	blob=$(printf '%s\n' "$next" | git hash-object -w --stdin)
	tree=$(printf '100644 blob %s\tNEXT\n' "$blob" | git mktree)
	encode "$((next - 1))" >/dev/null
	if [ "$count" = 1 ]; then
		msg="Claim $(encode "$cur") for $reason"
	else
		msg="Claim $(encode "$cur")-$(encode "$((next - 1))") for $reason"
	fi
	commit=$(git commit-tree "$tree" -p "$base" -m "$msg")

	if git push -q origin "$commit:refs/heads/todo-seq" 2>/dev/null; then
		for n in $(seq "$cur" "$((next - 1))"); do encode "$n"; done
		exit 0
	fi
	sleep "$((RANDOM % 3 + 1))"
done

echo "claim-number.sh: lost the race 10 times -- is something claiming in a loop?" >&2
exit 1
