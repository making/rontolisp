#!/usr/bin/env python3
"""Run every SICP sample through rontolisp (file mode + REPL mode), record results as JSON."""
import json, os, subprocess, sys, glob
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "sicp", "programs_scm")
JAR = os.path.join(HERE, "ronto.jar")
TIMEOUT = 20


def run(args, stdin_path):
    with open(stdin_path, "rb") as stdin:
        try:
            p = subprocess.run(["java", "-Xss16m", "-jar", JAR] + args, stdin=stdin, capture_output=True,
                               timeout=TIMEOUT)
            return {"exit": p.returncode, "out": p.stdout.decode("utf-8", "replace")[-4000:],
                    "err": p.stderr.decode("utf-8", "replace")[:3000]}
        except subprocess.TimeoutExpired as e:
            return {"exit": "timeout", "out": (e.stdout or b"").decode("utf-8", "replace")[-2000:],
                    "err": (e.stderr or b"").decode("utf-8", "replace")[:2000]}


def one(path):
    rel = os.path.relpath(path, ROOT)
    return rel, {"file": run([path], os.devnull), "repl": run(["--source-language", "scheme"], path)}


def main():
    files = sorted(glob.glob(os.path.join(ROOT, "**", "*.scm"), recursive=True))
    if len(sys.argv) > 1:
        files = [f for f in files if sys.argv[1] in f]
    results = {}
    with ThreadPoolExecutor(max_workers=8) as pool:
        for i, (rel, r) in enumerate(pool.map(one, files)):
            results[rel] = r
            if i % 100 == 0:
                print(i, rel, file=sys.stderr, flush=True)
    with open(os.path.join(HERE, "results.json"), "w") as out:
        json.dump(results, out, indent=1)


main()
