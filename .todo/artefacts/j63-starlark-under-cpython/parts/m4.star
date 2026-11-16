def shout(s):
    return s.upper().lower().strip()
def run(n):
    t = 0
    ws = "the quick brown fox jumps over the lazy dog".split(" ")
    for i in range(n):
        if i % 10 == 0:
            t += len(shout(ws[i % 9]))
    return t
print(run(2400000))
