def formatted(n):
    t = 0
    for i in range(n):
        s = "{}-{}".format(i, "x")
        u = "%d:%s" % (i, s)
        t += len(u)
    return t
print(formatted(900000))
