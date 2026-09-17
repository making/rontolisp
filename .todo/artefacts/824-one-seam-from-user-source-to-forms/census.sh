#!/usr/bin/env bash
# Where does source text become forms today? Run from the repository root.
set -euo pipefail
cd "$(dirname "$0")/../../../src"

echo "== USER source -> forms (the sites the seam replaces)"
grep -rnE 'LispReader\.readAll(WithReadEvalMarkers|FromString)\(source' \
	main/java/am/ik/rontolisp/cli web/java | cut -c1-160

echo "== every LispReader caller in main/web, by package (most read LIBRARY source, which stays CL)"
grep -rlE 'new LispReader|LispReader\.(read|parse)|new LispLexer' main/java web/java \
	| sed -E 's#.*/rontolisp/##; s#/[^/]+$##' | sort | uniq -c

echo "== test files calling LispReader directly"
grep -rlE 'new LispReader|LispReader\.(read|parse)' test/java | wc -l

echo "== hard-coded .lisp extension"
grep -rnE '"\.lisp"|endsWith\("\.lisp' main/java web/java | cut -c1-160
