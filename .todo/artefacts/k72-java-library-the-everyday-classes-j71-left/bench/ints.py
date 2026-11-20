# ints.py: writes ints.txt, the input of SumScan and SumTok -- a count, then a million
# seeded ints in [-10^9, 10^9], ten to a line (10,389,419 bytes)
import random
random.seed(72)
n = 1000000
out = [str(n)]
row = []
for i in range(n):
    row.append(str(random.randint(-10**9, 10**9)))
    if len(row) == 10:
        out.append(' '.join(row))
        row = []
if row:
    out.append(' '.join(row))
open('ints.txt', 'w').write('\n'.join(out) + '\n')
