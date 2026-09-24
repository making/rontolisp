"""Count long consecutive runs of local.get / local.set (landing-pad push / refresh shapes).
usage: runs.py file.wat [minrun]"""
import sys

minrun = int(sys.argv[2]) if len(sys.argv) > 2 else 8
total = {"local.get": 0, "local.set": 0}
runs = {"local.get": 0, "local.set": 0}
cur, n = None, 0


def flush():
    if cur in total and n >= minrun:
        total[cur] += n
        runs[cur] += 1


for line in open(sys.argv[1]):
    s = line.split()
    op = s[0] if s else None
    if op == cur:
        n += 1
        continue
    flush()
    cur, n = op, 1
flush()
print("runs>=%d" % minrun, runs, "instrs", total)
