def push(xs, v):
    xs.append(v)
def run(n):
    xs = []
    for i in range(n):
        push(xs, i)
    return len(xs)
print(run(2400000))
