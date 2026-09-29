#!/bin/bash
# clidiffs.sh: the class pairs a85's cmpcli.sh found differing (under $WORK/cli) copied into
# $WORK/clidiffs as NAME.A.class / NAME.B.class, then MethodDiffAll over them -- which methods
# differ, per pair, and a histogram of their names.
T=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-/tmp/a87-work}
rm -rf "$WORK/clidiffs"
mkdir -p "$WORK/clidiffs"
for a in "$WORK"/cli/*-a; do
  name=$(basename "$a" -a)
  b="$WORK/cli/$name-b"
  while IFS= read -r f; do
    if ! cmp -s "$a/$f" "$b/$f"; then
      base="$name-$(echo "$f" | tr '/' '_' | sed 's/^\._//')"
      cp "$a/$f" "$WORK/clidiffs/$base.A.class"
      cp "$b/$f" "$WORK/clidiffs/$base.B.class"
    fi
  done < <(cd "$a" && find . -name '*.class' | sort)
done
java "$T/MethodDiffAll.java" "$WORK/clidiffs"
