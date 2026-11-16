def collatz(n):
    steps = 0
    while_guard = 1000
    for _ in range(while_guard):
        if n == 1:
            break
        if n % 2 == 0:
            n = n // 2
        else:
            n = 3 * n + 1
        steps += 1
    return steps
def run(n):
    best = 0
    for i in range(1, n):
        s = collatz(i)
        if s > best:
            best = s
    return best
def sieve(n):
    flags = [True] * (n + 1)
    count = 0
    for i in range(2, n + 1):
        if flags[i]:
            count += 1
            for j in range(i * 2, n + 1, i):
                flags[j] = False
    return count
print(run(100000), sieve(2000000))
