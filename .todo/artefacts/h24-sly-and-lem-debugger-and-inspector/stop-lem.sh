#!/bin/bash
O=@WORK@/lem-ronto
docker rm -f lem-h24 >/dev/null 2>&1
tmux kill-session -t lemh24 2>/dev/null
kill "$(cat $O/proxy.pid)" "$(cat $O/micros.pid)" 2>/dev/null
