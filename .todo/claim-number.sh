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
# `.todo/NNN-*.md` files AND the numbers recorded in `.todo/history/` -- and skips
# past anything already taken, healing the counter in the same push. That check is
# what makes a manual cross-check afterwards unnecessary.
set -euo pipefail

reason=${1:?usage: claim-number.sh "<why>" [count]}
count=${2:-1}
ref=refs/remotes/origin/todo-seq

for attempt in $(seq 1 10); do
	git fetch -q origin "+refs/heads/todo-seq:$ref"
	base=$(git rev-parse "$ref")
	cur=$(git show "$ref:NEXT" | tr -d '[:space:]')

	# Skip past any number develop already uses -- live file or history row.
	git fetch -q origin '+refs/heads/develop:refs/remotes/origin/develop'
	used=$( {
		git ls-tree --name-only origin/develop .todo/
		git grep -h -oE '\.todo/[0-9]{3}' origin/develop -- .todo/history/ || true
	} | grep -oE '[0-9]{3}' | sort -n | tail -1)
	if [ -n "$used" ] && [ "$used" -ge "$cur" ]; then
		cur=$((used + 1))
	fi

	next=$((cur + count))

	blob=$(printf '%s\n' "$next" | git hash-object -w --stdin)
	tree=$(printf '100644 blob %s\tNEXT\n' "$blob" | git mktree)
	if [ "$count" = 1 ]; then
		msg="Claim $cur for $reason"
	else
		msg="Claim $cur-$((next - 1)) for $reason"
	fi
	commit=$(git commit-tree "$tree" -p "$base" -m "$msg")

	if git push -q origin "$commit:refs/heads/todo-seq" 2>/dev/null; then
		seq "$cur" "$((next - 1))"
		exit 0
	fi
	sleep "$((RANDOM % 3 + 1))"
done

echo "claim-number.sh: lost the race 10 times -- is something claiming in a loop?" >&2
exit 1
