#!/bin/bash
# Records a real SLIME session against SBCL's SWANK 2.32 through the logging proxy.
# Usage: run-sbcl.sh OUTDIR [steps]
set -u
S=@WORK@
OUT=$1
STEPS=${2:-basic}
mkdir -p "$OUT/work"
rm -f "$OUT/wire.log"
LISP_PORT=4105
PROXY_PORT=4106
sbcl --non-interactive --load "$S/oracle/start-sbcl-swank.lisp" --end-toplevel-options $LISP_PORT > "$OUT/sbcl.log" 2>&1 &
LISP_PID=$!
for i in $(seq 1 100); do
  if (echo > /dev/tcp/127.0.0.1/$LISP_PORT) 2>/dev/null; then break; fi
  sleep 0.2
done
python3 "$S/oracle/swank_proxy.py" $PROXY_PORT $LISP_PORT "$OUT/wire.log" &
PROXY_PID=$!
sleep 0.5
SWANK_PORT=$PROXY_PORT DRV_DIR="$OUT/work" DRV_STEPS=$STEPS timeout 300 "$S/emacs.sh" --batch -l "$S/oracle/drive.el" > "$OUT/emacs.log" 2>&1
echo "emacs exit=$?"
kill $PROXY_PID $LISP_PID 2>/dev/null
wait 2>/dev/null
