def lookup(d, k):
    return d.get(k, 0)
def run(n):
    d = {}
    t = 0
    for i in range(n):
        d[i % 1000] = i
        t += lookup(d, i % 1500)
    return t
print(run(2400000))
