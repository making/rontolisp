#!/bin/bash
# pages.sh <dist> <timeout-seconds> <files...> : per page, the cells that hang, fail or differ.
dist=$1; limit=$2; shift 2
for f in "$@"; do
  out=$(timeout "$limit" node --stack-size=4000 "$(dirname "$0")/page.js" "$dist" "$f" 2>&1)
  code=$?
  if [ $code -eq 124 ]; then echo "HANG $f :: $(echo "$out" | grep '^START' | tail -1)"; fi
  if [ $code -ne 0 ] && [ $code -ne 124 ]; then echo "CRASH($code) $f :: $(echo "$out" | tail -2 | cut -c1-300)"; fi
  echo "$out" | awk -v f="$f" '/^START/{s=$0} /^END/ && /ERROR|DIFF/{print "  " f " :: " s " :: " substr($0,1,400)}'
done
echo finished
