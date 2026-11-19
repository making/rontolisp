#!/bin/bash
P=$1
python3 origin.py 18490 30 &
OP=$!
wasmtime serve -W gc=y -W exceptions=y -S cli=y -S http=y --addr 127.0.0.1:$P App.wasm > serve.log 2>&1 &
SP=$!
for i in $(seq 1 40); do curl -s -o /dev/null --max-time 2 http://127.0.0.1:$P/fast && break; sleep 0.25; done
echo "--- sleep + fast"
( curl -s --max-time 10 http://127.0.0.1:$P/sleep; echo " [$(date +%s.%N)]" ) &
C=$!
sleep 0.4; curl -s --max-time 5 http://127.0.0.1:$P/fast; echo " [$(date +%s.%N)]"; wait $C
echo "--- later"
curl -s --max-time 5 http://127.0.0.1:$P/later; echo " [$(date +%s.%N)]"; sleep 1.2
echo "--- upstream + fast"
( curl -s --max-time 10 "http://127.0.0.1:$P/upstream?http://127.0.0.1:18490/x"; echo " [$(date +%s.%N)]" ) &
C=$!
sleep 0.4; curl -s --max-time 5 http://127.0.0.1:$P/fast; echo " [$(date +%s.%N)]"; wait $C
sleep 2.5
echo "--- after idle"
curl -s --max-time 5 http://127.0.0.1:$P/fast; echo
kill $SP $OP
