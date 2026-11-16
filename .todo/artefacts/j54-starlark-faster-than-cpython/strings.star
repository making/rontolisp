# String building: +, +=, join, format, %, str().
def concat(n):
    t = 0
    for i in range(n):
        s = "item" + str(i)
        s += ":" + str(i % 7)
        t += len(s)
    return t

def joined(n):
    parts = []
    for i in range(n):
        parts.append(str(i))
    s = ",".join(parts)
    return len(s) + len(s.split(","))

def formatted(n):
    t = 0
    for i in range(n):
        s = "{}-{}".format(i, "x")
        u = "%d:%s" % (i, s)
        t += len(u)
    return t

def accumulate(n):
    s = ""
    for i in range(n):
        s += "ab"
    return len(s)

print(concat(1200000), joined(1200000), formatted(900000), accumulate(5000))
