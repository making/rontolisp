def run(n):
    t = 0
    flag = False
    for i in range(n):
        flag = not flag
        if flag and i:
            t += 1
        if not flag or t:
            t += 2
    return t
print(run(20000000))
