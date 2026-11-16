def concat(n):
    t = 0
    for i in range(n):
        s = "item" + str(i)
        s += ":" + str(i % 7)
        t += len(s)
    return t
print(concat(1200000))
