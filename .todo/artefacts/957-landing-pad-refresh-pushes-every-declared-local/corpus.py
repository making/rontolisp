"""Concatenate the ci-spec.yaml case sources into one program, the way
YamlResources.corpusSource does. usage: corpus.py ci-spec.yaml out.lisp"""
import sys

import yaml

spec = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
cases = spec["cases"] if isinstance(spec, dict) else spec
with open(sys.argv[2], "w", encoding="utf-8") as out:
    for c in cases:
        src = c["source"]
        out.write(src)
        if not src.endswith("\n"):
            out.write("\n")
print(len(cases), "cases")
