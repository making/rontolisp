# A build graph, in the language build files are written in: targets with their
# sources and dependencies, the order to build them in, and what a change rebuilds.

def target(name, srcs = [], deps = []):
    return {"name": name, "srcs": srcs, "deps": deps}

TARGETS = [
    target("app", srcs = ["main.c"], deps = ["net", "ui"]),
    target("ui", srcs = ["ui.c", "theme.c"], deps = ["core"]),
    target("net", srcs = ["http.c"], deps = ["core", "tls"]),
    target("tls", srcs = ["tls.c"]),
    target("core", srcs = ["list.c", "map.c", "str.c"]),
]

def build_order(targets):
    """Dependencies first: a target is ready once everything it needs is built."""
    waiting = {t["name"]: list(t["deps"]) for t in targets}
    order = []
    for _ in range(len(targets)):
        ready = sorted([name for name, deps in waiting.items() if not deps])
        if not ready:
            break
        for name in ready:
            waiting.pop(name)
            order.append(name)
        for deps in waiting.values():
            for name in ready:
                if name in deps:
                    deps.remove(name)
    if waiting:
        fail("dependency cycle among: " + ", ".join(sorted(waiting)))
    return order

def rebuilt_by(targets, changed):
    """The targets a change to one of them rebuilds, in build order."""
    by_name = {t["name"]: t for t in targets}
    affected = []
    for name in build_order(targets):
        if name == changed or any([dep in affected for dep in by_name[name]["deps"]]):
            affected.append(name)
    return affected

def describe(t):
    count = len(t["srcs"])
    needs = ", ".join(t["deps"]) or "nothing"
    return "%s: %d source%s, needs %s" % (t["name"], count, "" if count == 1 else "s", needs)

def report(targets):
    by_name = {t["name"]: t for t in targets}
    order = build_order(targets)
    print("build order:", " -> ".join(order))
    for name in order:
        print(" ", describe(by_name[name]))
    print("a change to tls rebuilds:", rebuilt_by(targets, "tls"))
    largest = max(targets, key = lambda t: len(t["srcs"]))
    print("largest: {name} {srcs}".format(**largest))
    print("sources:", sorted([src for t in targets for src in t["srcs"]]))

report(TARGETS)
