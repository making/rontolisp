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
# A number is three characters up to z99: 000-999, then a letter and two digits
# (999 -> a00, ... z99), so names sort in claim order and no existing reference
# changes width. Past z99 it continues as `z` + era letter + base-36 payload:
# za000...zazzz (era a), then zb0000...zbzzzz (era b), each era one character
# wider and 36x larger than the one before. An era's letter sorts after every
# earlier era's, so every new number still sorts after every number issued
# before it -- the one property the sequence has to keep. Era a holds 46656 and
# the ladder runs to era j (payload 12), far past anything reachable. `NEXT`
# holds the plain decimal ordinal (3600 is za000).
set -euo pipefail

digits=0123456789abcdefghijklmnopqrstuvwxyz

# 3599 is z99, the last three-character ordinal.
legacy_max=3599

# 000..z99 -> ordinal
decode_legacy() {
	local head=${digits%%"${1:0:1}"*}
	echo $((${#head} * 100 + 10#${1:1:2}))
}

# 1005 -> l05
encode_legacy() {
	printf '%s%02d\n' "${digits:$(($1 / 100)):1}" "$(($1 % 100))"
}

# ordinal -> fixed-width base-36 payload, most significant character first
encode_base36() {
	local n=$1 width=$2 out="" i
	for ((i = 0; i < width; i++)); do
		out="${digits:$((n % 36)):1}$out"
		n=$((n / 36))
	done
	printf '%s\n' "$out"
}

# payload -> ordinal
decode_base36() {
	local n=0 i c head
	for ((i = 0; i < ${#1}; i++)); do
		c=${1:i:1}
		head=${digits%%"$c"*}
		n=$((n * 36 + ${#head}))
	done
	echo "$n"
}

# codes issued before era $1, era 0 (`za`) carrying 36^3 payloads
era_prefix() {
	local sum=0 k
	for ((k = 0; k < $1; k++)); do
		sum=$((sum + 36 ** (k + 3)))
	done
	echo "$sum"
}

# 3600 -> za000
encode_new() {
	local n=$(($1 - legacy_max - 1)) era=0 width cap
	while :; do
		width=$((era + 3))
		cap=$((36 ** width))
		if [ "$n" -lt "$cap" ]; then
			printf 'z%s%s\n' "${digits:era+10:1}" "$(encode_base36 "$n" "$width")"
			return 0
		fi
		n=$((n - cap))
		era=$((era + 1))
		if [ "$era" -ge 10 ]; then
			echo "claim-number.sh: $1 is past the last numbering era" >&2
			exit 1
		fi
	done
}

# za000, zb0000, ... -> ordinal; silence for a shape no era issues
decode_new() {
	local head=${digits%%"${1:1:1}"*}
	local era=$((${#head} - 10))
	if [ "$era" -lt 0 ] || [ "$era" -gt 9 ]; then
		return 0
	fi
	echo $((legacy_max + 1 + $(era_prefix "$era") + $(decode_base36 "${1:2}")))
}

decode() {
	if [ ${#1} -eq 3 ]; then
		decode_legacy "$1"
	else
		decode_new "$1"
	fi
}

encode() {
	if [ "$1" -le "$legacy_max" ]; then
		encode_legacy "$1"
	else
		encode_new "$1"
	fi
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
		git ls-tree -r --name-only origin/develop .todo/ | sed 's|^\.todo/||' \
			| grep -oE '^([0-9a-z][0-9]{2}|z[a-z][0-9a-z]{3,})-' || true
		git ls-tree --name-only origin/develop .todo/artefacts/ | sed 's|^\.todo/artefacts/||' \
			| grep -oE '^([0-9a-z][0-9]{2}|z[a-z][0-9a-z]{3,})-' || true
		git grep -h -oE '\.todo/([0-9a-z][0-9]{2}|z[a-z][0-9a-z]{3,})' origin/develop -- .todo/history/ \
			| sed 's|^\.todo/||' || true
	} | sed 's/-$//' | while read -r n; do decode "$n"; done | sort -n | tail -1)
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
