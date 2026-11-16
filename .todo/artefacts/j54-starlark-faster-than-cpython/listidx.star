# List indexing and assignment: a[i], a[i] = x, a[i] += x, len(a).
def fill(n):
    xs = [0] * n
    for i in range(n):
        xs[i] = (i * 7919) % 1000003
    return xs

def prefix(xs):
    for i in range(1, len(xs)):
        xs[i] = (xs[i] + xs[i - 1]) % 1000000007
    return xs[len(xs) - 1]

def reverse(xs):
    i = 0
    j = len(xs) - 1
    for _ in range(len(xs) // 2):
        t = xs[i]
        xs[i] = xs[j]
        xs[j] = t
        i += 1
        j -= 1

def histogram(xs, buckets):
    counts = [0] * buckets
    for x in xs:
        counts[x % buckets] += 1
    best = 0
    for b in range(buckets):
        if counts[b] > counts[best]:
            best = b
    return best

def run(n, rounds):
    total = 0
    for r in range(rounds):
        xs = fill(n)
        reverse(xs)
        total += histogram(xs, 97)
        total += prefix(xs)
        total += xs[r] + xs[-1 - r]
    return total % 1000000007

print(run(200000, 20))
