def run(n):
    a = 0
    b = 1
    for i in range(n):
        a, b = b, (a + b) % 1000000007
    return a
print(run(10000000))
