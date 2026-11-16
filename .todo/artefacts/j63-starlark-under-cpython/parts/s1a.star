def concat(n):
    t = 0
    for i in range(n):
        s = str(i)
        t += len(s)
    return t
print(concat(1200000))
