#!/usr/bin/env bash
# Renders every deck under decks/ with lispresen: decks/<name>/<name>.lisp becomes
# <name>.html beside it, with its images embedded (--embed), and the page image its
# :page-image names. Any diagnostic, a warning included, fails the build (--strict).
#
#   decks/build.sh                     # every deck
#   decks/build.sh rontolisp-intro     # the named decks only
#
# Needs the executable jar (./mvnw package); RONTOLISP_JAR overrides its path.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
jar=${RONTOLISP_JAR:-$root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar}

if [[ ! -f $jar ]]; then
  echo "decks/build.sh: no jar at $jar (run ./mvnw package or set RONTOLISP_JAR)" >&2
  exit 2
fi

if (($# > 0)); then
  names=("$@")
else
  names=()
  for dir in "$here"/*/; do
    names+=("$(basename "$dir")")
  done
fi

status=0
for name in "${names[@]}"; do
  deck=$here/$name/$name.lisp
  if [[ ! -f $deck ]]; then
    echo "skip $name: no $name/$name.lisp"
    continue
  fi
  # lispresen resolves the deck's files (images, code, fonts) against the deck's
  # directory and writes the page image beside the HTML.
  if java -jar "$jar" "$root/lispresen/cli.lisp" -- "$deck" --embed --strict; then
    echo "ok   $name"
  else
    echo "FAIL $name"
    status=1
  fi
done
exit $status
