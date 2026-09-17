#!/usr/bin/env python3
"""Shimmed corpus on interpreter / JVM / wasm; compares stdout. Only files whose interpreter run exits 0."""
import json, os, subprocess, sys, glob, tempfile, shutil
from concurrent.futures import ThreadPoolExecutor
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "sicp_shim"); JAR = os.path.join(HERE, "ronto.jar")
prev = json.load(open(os.path.join(HERE, "results_shim.json")))
def sh(cmd, cwd, timeout=60):
    try:
        p = subprocess.run(cmd, cwd=cwd, stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout)
        return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")[:1500]
    except subprocess.TimeoutExpired:
        return "timeout", "", ""
def one(rel):
    path = os.path.join(ROOT, rel); d = tempfile.mkdtemp(dir=os.path.join(HERE, "tmp"))
    try:
        res = {"interp": sh(["java", "-jar", JAR, path], d)}
        c = sh(["java", "-jar", JAR, path, "-o", "Prog.class"], d)
        res["jvm"] = sh(["java", "-Xss16m", "Prog"], d) if c[0] == 0 else ("compile", c[1], c[2])
        c = sh(["java", "-jar", JAR, path, "-o", "prog.wasm"], d)
        res["wasm"] = sh(["wasmtime", "run", "prog.wasm"], d) if c[0] == 0 else ("compile", c[1], c[2])
        return rel, res
    finally:
        shutil.rmtree(d, ignore_errors=True)
os.makedirs(os.path.join(HERE, "tmp"), exist_ok=True)
files = sorted(k for k, v in prev.items() if v["file"]["exit"] == 0)
if len(sys.argv) > 1: files = [f for f in files if sys.argv[1] in f]
out = {}
with ThreadPoolExecutor(max_workers=8) as pool:
    for i, (rel, r) in enumerate(pool.map(one, files)):
        out[rel] = r
        if i % 100 == 0: print(i, rel, file=sys.stderr, flush=True)
json.dump(out, open(os.path.join(HERE, "results_backends.json"), "w"), indent=1)
