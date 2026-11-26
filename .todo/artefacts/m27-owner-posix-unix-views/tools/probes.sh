#!/bin/sh
# Runs each probe on the JDK, the interpreter and the JVM in fresh directories; prints the diffs.
# usage: run.sh <jar> <outdir> Probe...
jar=$1; out=$2; shift 2
here=$(cd "$(dirname "$0")" && pwd)
for p in "$@"; do
  for leg in jdk interp jvm; do
    d=$out/$p/$leg; mkdir -p $d; cp $here/$p.java $d/
    case $leg in
      jdk) (cd $d && java $p.java > ../$leg.out 2>../$leg.err) ;;
      interp) (cd $d && java -jar $jar $p.java > ../$leg.out 2>../$leg.err) ;;
      jvm) (cd $d && mkdir -p ../cls && java -jar $jar $p.java -o ../cls/$p.class > ../$leg.out 2>../$leg.err && java -cp ../cls $p > ../$leg.out 2>>../$leg.err) ;;
    esac
  done
  echo "== $p interp"; diff $out/$p/jdk.out $out/$p/interp.out
  echo "== $p jvm"; diff $out/$p/jdk.out $out/$p/jvm.out
done
