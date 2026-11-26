#!/usr/bin/env bash
# Fetch the ANSI Common Lisp test suite into ansi-test/suite/ (git-ignored).
#
# The suite is NOT vendored: it is a foreign 867-file corpus with its own history,
# and a report is only comparable to the previous one when both name the revision
# they measured -- so the revision is pinned HERE and printed into every report.
set -euo pipefail

REPO="${ANSI_TEST_REPO:-https://gitlab.common-lisp.net/ansi-test/ansi-test.git}"
REV="${ANSI_TEST_REV:-ca06bd919661af162c67407c9d994e881870bdb3}"
DIR="$(cd "$(dirname "$0")" && pwd)/suite"

if [ -d "$DIR/.git" ]; then
  git -C "$DIR" fetch --quiet origin "$REV" 2>/dev/null || git -C "$DIR" fetch --quiet origin
else
  rm -rf "$DIR"
  git clone --quiet "$REPO" "$DIR"
fi
git -C "$DIR" checkout --quiet "$REV"
echo "ansi-test suite at $REV in $DIR"
