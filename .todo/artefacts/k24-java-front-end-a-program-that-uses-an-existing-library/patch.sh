#!/bin/sh
# Rebuilds the patched copy of a YAVI source tree the measurement lowers: each edit
# routes around ONE gap of the front end, named in the README beside this file, so that
# the next one shows; a class standing in for one a gap names goes under shim/ beside
# this file. usage: patch.sh <pristine sources dir> <patched dir to make>
set -e
src=$1
out=$2
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
cp -r "$src"/. "$out"/
if [ -d "$here"/shim ]; then
    cp -r "$here"/shim/. "$out"/
fi
# The classic example needs none (2026-11-19). The schema example's five (2026-11-20),
# one since k36, k38, k31 and k35 (java.util.UUID, StreamSupport, java.math and
# java.time are in the library; the number keeps its place), in what reads an input of
# unknown type; GNU sed.
untyped="$out"/am/ik/yavi/core/UntypedInput.java
# 3. java.util.Date and java.sql
sed -i -e '/value instanceof java\.util\.Date date/,/^\t\t}$/d' \
    -e '/private static final class SqlTypes/,/^\t}$/d' "$untyped"
