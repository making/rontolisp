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
# No edit is left (2026-11-19): the classic example compiles from the unpatched
# coordinates, and the schema example's gap has no stand-in.
