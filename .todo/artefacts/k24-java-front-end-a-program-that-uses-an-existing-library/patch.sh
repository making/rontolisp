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
# Neither example needs an edit: the classic one since 2026-11-19, the schema one since
# k37 (2026-11-20), whose five went with k36, k38, k31, k35 and k37 (java.util.UUID,
# StreamSupport, java.math, java.time, java.util.Date and java.sql are in the library).
# The next gap a program meets gets its line here.
