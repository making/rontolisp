#!/bin/bash
# Records a real SLY session against a Slynk-speaking server through the logging proxy.
# Usage: run.sh sbcl|ronto OUTDIR [steps]
set -u
H18=@CHECKOUT@/.todo/artefacts/h18-common-lisp-swank-server
H=$S/h21
JAR=${JAR:-@CHECKOUT@/target/rontolisp-0.1.0-SNAPSHOT-exec.jar}
WHICH=$1
OUT=$2
STEPS=${3:-basic}
mkdir -p "$OUT/work"
rm -f "$OUT/wire.log"
LISP_PORT=4305
PROXY_PORT=4306
if [ "$WHICH" = sbcl ]; then
  sbcl --non-interactive --load "$H/sly-oracle/start-sbcl-slynk.lisp" --end-toplevel-options $LISP_PORT > "$OUT/lisp.log" 2>&1 &
else
  java -jar "$JAR" --swank=$LISP_PORT > "$OUT/lisp.log" 2>&1 &
fi
LISP_PID=$!
for i in $(seq 1 150); do
  if (echo > /dev/tcp/127.0.0.1/$LISP_PORT) 2>/dev/null; then break; fi
  sleep 0.2
done
python3 "$H18/swank_proxy.py" $PROXY_PORT $LISP_PORT "$OUT/wire.log" &
PROXY_PID=$!
sleep 0.5
SLYNK_PORT=$PROXY_PORT DRV_DIR="$OUT/work" DRV_STEPS=$STEPS DRV_SLY_DIR=$H/dl/sly timeout 300 "$H18/emacs.sh" --batch -l "$H/sly-oracle/drive-sly.el" > "$OUT/emacs.log" 2>&1
echo "emacs exit=$?"
kill $PROXY_PID $LISP_PID 2>/dev/null
wait 2>/dev/null
