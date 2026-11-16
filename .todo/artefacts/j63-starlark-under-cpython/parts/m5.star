def run(n):
    t = 0
    line = "the quick brown fox jumps over the lazy dog"
    ws = line.split(" ")
    for i in range(n):
        if i % 10 == 0:
            if ws[3].startswith("f") and line.endswith("dog"):
                t += line.find("fox") + line.count("o")
    return t
print(run(2400000))
