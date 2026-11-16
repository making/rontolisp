# Method calls on values the lowering knows nothing of: parameters and list elements.
def push(xs, v):
    xs.append(v)

def lookup(d, k):
    return d.get(k, 0)

def shout(s):
    return s.upper().lower().strip()

def words(s):
    return s.split(" ")

def run(n):
    xs = []
    d = {}
    t = 0
    line = "the quick brown fox jumps over the lazy dog"
    for i in range(n):
        push(xs, i)
        d[i % 1000] = i
        t += lookup(d, i % 1500)
        if i % 10 == 0:
            ws = words(line)
            t += len(ws) + len(shout(ws[i % 9]))
            if ws[3].startswith("f") and line.endswith("dog"):
                t += line.find("fox") + line.count("o")
    for i in range(n // 2):
        t += xs.pop()
    ys = list(xs)
    ys.extend(xs)
    return t + len(ys) + ys.index(77)

print(run(2400000))
