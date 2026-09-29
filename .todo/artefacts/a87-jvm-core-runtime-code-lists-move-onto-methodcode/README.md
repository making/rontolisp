# Moving the core runtime builders' raw lists onto `MethodCode`: the tools

The recipe is `.kb/jvm-method-size-limits.md`, "How a slice moves". These extend a86's raw-list
tools (`../a86-jvm-io-and-socket-runtime-code-lists-move-onto-methodcode/`) to a builder written
through helpers of its own; the verifying tools are a85's
(`../a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode/`: `extract.py`, `runchunks.sh`,
`cmpcli.sh`, `Cmp.java`, `MethodDiff.java`, with `WORK` and `ROOT` set). Run
`PYTHONDONTWRITEBYTECODE=1` so no `__pycache__` lands in the tree.

Converting:

- `rawx.py FILE...` -- a86's `raw.py` after rewriting the builder's own helpers into its idiom;
  prints what is left for a hand.
- a85's `jc.sh` + `fix.py` in turns (the `.entry()` at each typed call on a pool wrapper), then
  `unused_imports.py --fix`.
- `labels.py FILE...` -- the labels no `labelBinding` in their method binds (a returned or
  passed label is fine; a `checkComplete` failure at `addTo` is the other kind).
- `Trace.java JAR PROGRAM` -- compiles one program in process and prints the whole stack trace
  (the CLI prints the message only).

Verifying:

- `mkjar.sh BASE.jar NAME` -- a comparison jar in seconds: the worktree's classes, compiled by
  plain javac, over a packaged jar; build both sides from the same `BASE.jar`.
- `phase1.py apply|revert` -- the temporary patch a87 compared bytes under (a spread case
  measured as the raw list did, `_cmul`'s dead `goto` kept): byte-identical with it, then the
  two changes measured alone without it.
- `clidiffs.sh` -- after `cmpcli.sh`, the differing CLI class pairs through `MethodDiffAll.java`
  (every pair's differing methods, then a histogram of their names; also takes the `diffs`
  directory `Cmp.java` dumps).

Expected differences: none under `phase1.py`; without it, `_invoke_v` and its segments and
`_cmul` only.
