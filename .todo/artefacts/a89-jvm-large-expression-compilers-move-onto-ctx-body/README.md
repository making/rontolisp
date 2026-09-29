# Moving an expression compiler onto `ctx.body`: the tools

The recipe is `.kb/jvm-method-size-limits.md`, "How a slice moves". Run from the directory of
the files; the verifying tools are a85's (`../a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode/`).

- `ctxmig.py FILE...` -- the mechanical pass over `ctx.emit`/`ctx.emitU2`; prints what it left
  (an opcode chosen at run time, a pool operand held as an `int`, a branch position used in a
  shape it does not know). Safe to run again on a file.
- `listlabel.py FILE NAME...` -- named `List<Integer>` position lists (and parameters) into
  labels.
- `intidx.py FILE TYPE NAME...` -- a pool operand held as `int NAME = x.index()` into
  `TYPE NAME = x`, its `emitU2(NAME)` uses into the shape `ctxmig.py` maps; run `ctxmig.py`
  after it.

Then `jc.sh` + `fix.py` (a85's), `unused_imports.py --fix`, a jar, and `runchunks.sh` /
`cmpcli.sh` against the jar before.
