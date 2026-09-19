# Probes behind `.todo/828` .. `.todo/837`

The corpus is NOT checked in: `https://sicp.sourceacademy.org/sicp.zip`
(sha256 `eb815d37...dbd77df` on 2026-09-17, 1,592 `.scm` under `programs_scm/`).

```bash
cd <an empty scratch directory>
cp <repo>/.todo/artefacts/828-sicp-sample-corpus-harness/* .
cp <repo>/target/rontolisp-0.1.0-SNAPSHOT-exec.jar ronto.jar
curl -sSLO https://sicp.sourceacademy.org/sicp.zip && unzip -q sicp.zip -d sicp

python3 run.py            # every file, file mode + REPL mode  -> results.json        (~3 min, 8 workers)
python3 free.py           # static scan of free identifiers     -> free.json
grep -oE '^\s+\("[^"]+"' <repo>/src/main/java/am/ik/rontolisp/scheme/SchemeBuiltins.java \
  | sed -E 's/^[[:space:]]*\("//; s/"$//' > known.txt
python3 shim.py           # corpus + prelude.scm, cons-stream/delay rewritten -> sicp_shim/
python3 run_shim.py       # the shimmed corpus                  -> results_shim.json
python3 run_backends.py   # interpreter / JVM / wasm, stdout compared -> results_backends.json (~20 min)
python3 baseline.py       # -> baseline.tsv, the per-file table
python3 exp.py            # REPL echo vs the `; expected:` annotations
```

- `prelude.scm` is a throw-away stand-in for the names the items add, written in Scheme so
  the SECOND layer of failures shows. Its `sqrt`/`sin`/`atan` are series approximations:
  good for "does it run", not for comparing digits.
- `shim.py` rewrites `(cons-stream a b)` and `(delay e)` textually; it skips `delay` in a
  file that binds `delay` as a variable (13 files do).
- `baseline.tsv` columns: category, interpreter today, interpreter with the shim, JVM and
  wasm with the shim (`ok` = exit 0 AND stdout equal to the interpreter's), and the
  not-yet-provided names the file uses.
- `repl.py` drives one REPL transcript: `python3 repl.py $'(car 5)\n'`.
- `internal_tail_cycles.py sicp` lists the bodies and `letrec`s whose local procedures
  tail-call each other in a cycle (`.todo/898`: none in the corpus).
