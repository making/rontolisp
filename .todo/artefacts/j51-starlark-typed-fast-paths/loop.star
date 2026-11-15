def loop(n):
    total = 0
    for i in range(n):
        total += i % 7
    return total
print(loop(30000000))
