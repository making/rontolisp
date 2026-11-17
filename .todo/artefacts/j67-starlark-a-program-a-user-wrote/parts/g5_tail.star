# A build graph at size: the shape of examples/starlark/targets.star, its targets made
# by a loop -- each needs up to three earlier ones -- and its report a checksum.

def target(name, srcs = [], deps = []):
    return {"name": name, "srcs": srcs, "deps": deps}

def make_targets(n):
    targets = []
    for i in range(n):
        srcs = ["t%d_%d.c" % (i, k) for k in range(1 + i % 3)]
        deps = []
        for step in (1, 7, 31):
            j = i - step - (i * step) % 5
            if j >= 0:
                name = "t%d" % j
                if name not in deps:
                    deps.append(name)
        targets.append(target("t%d" % i, srcs = srcs, deps = deps))
    return targets

def build_order(targets):
    """Dependencies first: a target is ready once everything it needs is built."""
    needs = {t["name"]: len(t["deps"]) for t in targets}
    users = {}
    for t in targets:
        for dep in t["deps"]:
            users.setdefault(dep, []).append(t["name"])
    ready = sorted([name for name, count in needs.items() if count == 0])
    order = []
    for _ in range(len(targets)):
        if not ready:
            break
        name = ready.pop(0)
        order.append(name)
        for user in users.get(name, []):
            needs[user] -= 1
            if needs[user] == 0:
                ready.append(user)
    if len(order) != len(targets):
        fail("dependency cycle")
    return order

def rebuilt_by(targets, by_name, order, changed):
    """The targets a change to one of them rebuilds, in build order."""
    affected = {}
    for name in order:
        if name == changed or any([dep in affected for dep in by_name[name]["deps"]]):
            affected[name] = True
    return list(affected.keys())

def describe(t):
    count = len(t["srcs"])
    needs = ", ".join(t["deps"]) or "nothing"
    return "%s: %d source%s, needs %s" % (t["name"], count, "" if count == 1 else "s", needs)

def report(targets):
    by_name = {t["name"]: t for t in targets}
    order = build_order(targets)
    total = 0
    for name in order:
        total += len(describe(by_name[name]))
    for changed in ("t0", "t17", "t250", "t999"):
        total += len(rebuilt_by(targets, by_name, order, changed))
    largest = max(targets, key = lambda t: len(t["srcs"]))
    total += len("largest: {name} {srcs}".format(**largest))
    total += len(sorted([src for t in targets for src in t["srcs"]]))
    return total


def run(n, rounds):
    total = 0
    targets = make_targets(n)
    for r in range(rounds):
        largest = max(targets, key = lambda t: len(t["srcs"]))
        total += len("largest: {name} {srcs}".format(**largest))
        total += len(sorted([src for t in targets for src in t["srcs"]]))
    return total

print(run(2000, 40))
