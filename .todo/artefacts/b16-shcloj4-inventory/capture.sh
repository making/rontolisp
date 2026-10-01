#!/usr/bin/env bash
# Capture expected outputs for every probe: oracle (clj) + rontolisp (exec jar).
# Usage: capture.sh [jar-path]
set -u
cd "$(dirname "$0")/probes"
JAR="${1:-/nvme1n1-disk/rontolisp/target/rontolisp-0.1.0-SNAPSHOT-exec.jar}"
mkdir -p ../expected
for probe in *.clj; do
  base="${probe%.clj}"
  timeout 60 clj -M "$probe" > "../expected/${base}.oracle.txt" 2>&1
  echo "oracle exit=$? $base"
  java -jar "$JAR" --source-language clojure "$probe" > "../expected/${base}.ronto.txt" 2>&1
  echo "ronto exit=$? $base"
done
