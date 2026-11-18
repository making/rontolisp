#!/bin/bash
# compare.sh File.java : javac's first error against the front end's, one line
jar=${JAR:-target/rontolisp-0.1.0-SNAPSHOT-exec.jar}; jar=$(realpath "$jar"); f=$1
d=$(dirname "$f"); b=$(basename "$f" .java); w=$(mktemp -d)
j=$(cd "$d" && javac -Xlint:none -d "$w" "$b.java" 2>&1 | grep -m1 ': error:' | sed -E 's/^[^:]*:([0-9]+): error: /\1: /')
o=$(cd "$d" && java -jar "$jar" "$b.java" 2>&1 </dev/null | grep -m1 '^error' | sed -E 's/^error: [^:]*:([0-9]+):[0-9]+: /\1: /')
case "$o" in *"no main method found"*) o="";; esac
if [ -z "$j" ] && [ -z "$o" ]; then echo "ok   $b (both accept)";
elif [ -n "$j" ] && [ "${o#"$j"}" != "$o" ]; then echo "ok   $b  $j";
else echo "DIFF $b | javac: $j | ours: $o"; fi
