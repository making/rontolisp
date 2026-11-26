#!/bin/sh
# run.sh LEG PROBE: stages a directory and runs a probe on one leg (jdk interp jvm wasm comp)
# with the worktree's classes; the output goes to PROBE.LEG.txt
leg=$1; p=$2
here=$(cd "$(dirname "$0")" && pwd)
cp="${CP:?the classes of a build}"
d=$here/run-$p-$leg
chmod -R u+rwx $d 2>/dev/null; rm -rf $d
$here/stage.sh $d && cp $here/$p.java $d/
cd $d
cli="java --enable-native-access=ALL-UNNAMED -cp $cp am.ik.rontolisp.cli.RontoLispCli"
case $leg in
  jdk) java $p.java ;;
  interp) $cli $p.java ;;
  jvm) mkdir -p ../cls-$p && $cli $p.java -o ../cls-$p/$p.class && java -cp ../cls-$p $p ;;
  wasm) $cli $p.java -o ../$p.wasm && wasmtime run --dir . --dir $here/run-other::other ../$p.wasm ;;
  comp) $cli $p.java -o ../$p-c.wasm --component && wasmtime run --dir . --dir $here/run-other::other ../$p-c.wasm ;;
esac > $here/$p.$leg.txt 2>&1
chmod -R u+rwx $d
