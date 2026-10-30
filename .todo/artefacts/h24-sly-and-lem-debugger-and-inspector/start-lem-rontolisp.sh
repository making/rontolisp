#!/bin/bash
# Starts micros (host SBCL 2.2.9), the logging proxy, and Lem (ncurses, in Docker) in a tmux session "lem".
# Usage: start.sh   (then drive.sh; stop.sh)
set -u
H=@H21@
O=@WORK@/lem-ronto
LISP_PORT=4435
PROXY_PORT=4436
mkdir -p "$O/work"
rm -f "$O/wire.log"
java -jar @CHECKOUT@/target/rontolisp-0.1.0-SNAPSHOT-exec.jar --swank=$LISP_PORT > "$O/micros.log" 2>&1 &
echo $! > "$O/micros.pid"
for i in $(seq 1 300); do
  if (echo > /dev/tcp/127.0.0.1/$LISP_PORT) 2>/dev/null; then break; fi
  sleep 0.2
done
python3 "$H/../oracle/swank_proxy.py" $PROXY_PORT $LISP_PORT "$O/wire.log" &
echo $! > "$O/proxy.pid"
sleep 0.5
tmux kill-session -t lemh24 2>/dev/null
tmux new-session -d -s lemh24 -x 150 -y 45 \
  "docker run --rm -it --name lem-h24 --network host -e TERM=xterm-256color -v $O/work:$O/work -w $O/work lem-oracle:ncurses /app/lem"
