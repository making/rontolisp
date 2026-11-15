def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)
def loop(n):
    total = 0
    for i in range(n):
        total += i % 7
    return total
print(fib(27), loop(1000000))
