"""Print one shell line per GC-wasm leg of examples/examples.yaml, compiling it the way
ExamplesE2eTest does, into OUT. Pipe into `xargs -P` (or bash) to build the census corpus.
usage: build_examples.py OUT"""
import os
import sys

import yaml

repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../.."))
out = os.path.abspath(sys.argv[1])
jar = os.path.join(repo, "target/rontolisp-0.1.0-SNAPSHOT-exec.jar")
flags = {
    "wasm": "--optimize",
    "wasm-component-run": "--component --optimize",
    "wasm-component": "--component --optimize",
    "wasm-reactor": "--no-wasi --optimize",
}
for e in yaml.safe_load(open(os.path.join(repo, "examples/examples.yaml")))["examples"]:
    for b in e["backends"]:
        if b in flags:
            name = os.path.splitext(e["path"])[0].replace("/", "__") + "__" + b
            src = os.path.join(repo, "examples", e["path"])
            print("cd %s && java -jar %s %s -o %s/%s.wasm %s > %s/%s.log 2>&1"
                  % (os.path.dirname(src), jar, src, out, name, flags[b], out, name))
