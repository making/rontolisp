#!/usr/bin/env bash
# Writes the notices the GraalVM that builds rontolisp's native binary asks to travel with
# a binary it produced:  graalvm-notices.sh GRAALVM_HOME OUT_FILE
# The binary carries the file as META-INF/graalvm-notices.txt and `rontolisp notices --tool`
# prints it. What goes in: GraalVM's LICENSE.txt (the GraalVM Free Terms and Conditions, which
# deem the Substrate VM and JDK portions of a Native Image output "an unmodified Program" and
# allow redistributing it free of charge, under those terms), the licence and COPYRIGHT of the
# JDK classes the image links (the No-Fee Terms and Conditions), and the third-party notices
# (*.md) of the modules whose classes it links. Only licence texts are read from the JDK, never
# a class or a jar (.kb/java-frontend-provenance.md, rule 1).
#
# MODULES lists the modules of the image that ship third-party notices: java.base (always) and
# jdk.incubator.vector (--add-modules, for the simd flag). A module with *.md files in
# GRAALVM_HOME/legal/ that enters the image (a new import outside java.base) is added here.
set -euo pipefail

home=${1:?usage: graalvm-notices.sh GRAALVM_HOME OUT_FILE}
out=${2:?usage: graalvm-notices.sh GRAALVM_HOME OUT_FILE}
MODULES=(java.base jdk.incubator.vector)

for file in LICENSE.txt legal/java.base/LICENSE legal/java.base/COPYRIGHT; do
  [[ -f $home/$file ]] || {
    echo "error: $home/$file not found: -Pnative needs an Oracle GraalVM (java.home is $home)" >&2
    exit 1
  }
done

rule='================================================================================'
dash='--------------------------------------------------------------------------------'

section() { # title file [iconv-from-charset]
  printf '%s\n%s\n%s\n' "$rule" "$1" "$dash"
  if [[ -n ${3:-} ]]; then iconv -f "$3" -t UTF-8 "$2"; else cat "$2"; fi
  printf '\n'
}

mkdir -p "$(dirname "$out")"
{
  printf 'The notices of the GraalVM that built this binary: its Substrate VM and the JDK\n'
  printf 'classes it links are in the binary. Distribute them with it.\n\n'
  section 'Oracle GraalVM (Substrate VM and the Native Image runtime): LICENSE.txt' "$home/LICENSE.txt"
  section 'JDK classes linked into the binary: legal/java.base/LICENSE' "$home/legal/java.base/LICENSE"
  section 'JDK classes linked into the binary: legal/java.base/COPYRIGHT' "$home/legal/java.base/COPYRIGHT" ISO-8859-1
  for module in "${MODULES[@]}"; do
    for file in "$home/legal/$module"/*.md; do
      [[ -f $file ]] || continue
      section "Third-party component of $module: $(basename "$file" .md)" "$file"
    done
  done
} >"$out.tmp"
mv "$out.tmp" "$out"
