#!/bin/sh
# Rebuilds the patched copy of a YAVI source tree the measurement lowers: each edit
# routes around ONE gap of the front end, named in the README beside this file, so that
# the next one shows. usage: patch.sh <pristine sources dir> <patched dir to make>
set -e
src=$1
out=$2
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
cp -r "$src"/. "$out"/
cp -r "$here"/shim/. "$out"/
# 1. java.text.Normalizer
sed -i 's/^import java\.text\.Normalizer;/import shim.Normalizer;/' "$out"/am/ik/yavi/constraint/CharSequenceConstraint.java
# 2. the collections and the text classes the library does not have: by import
for c in java.util.LinkedHashMap java.util.LinkedHashSet java.util.LinkedList java.text.MessageFormat; do
	n=${c##*.}
	grep -rl "^import $c;" "$out"/am | while read -r f; do sed -i "s/^import $c;/import shim.$n;/" "$f"; done
done
