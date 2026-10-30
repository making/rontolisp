#!/bin/bash
O=@WORK@/lem-oracle
docker rm -f lem-oracle >/dev/null 2>&1
tmux kill-session -t lem 2>/dev/null
kill "$(cat $O/proxy.pid)" "$(cat $O/micros.pid)" 2>/dev/null
