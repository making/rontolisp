def joined(n):
    parts = []
    for i in range(n):
        parts.append(str(i))
    s = ",".join(parts)
    return len(s)
print(joined(1200000))
