def words(s):
    return s.split(" ")
def run(n):
    t = 0
    line = "the quick brown fox jumps over the lazy dog"
    for i in range(n):
        if i % 10 == 0:
            ws = words(line)
            t += len(ws)
    return t
print(run(2400000))
