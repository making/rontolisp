#!/bin/sh
cd /tmp/claude-501/-Users-toshiaki-git-rontolisp/6511ccd0-a166-482a-8367-c7c92abfbb8f/scratchpad/spike || exit 1
for f in "$@"; do
  printf "%-16s total=%5s  " "$f" "$(stat -f%z $f.wasm)"
  wasm-tools objdump $f.wasm | awk '/types|functions |code|data/ {printf "%s=%sB/%s ", $1, $4, $6}'
  printf "\n"
done
