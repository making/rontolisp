# 814: measuring the printed-literal fold

`hello.lisp` is one `princ` of a literal plus a `terpri` -- the smallest program where the
two lowerings sit side by side in the emitted code, the literal going through
`local.tee; i32.const 4; i32.add; local.get; i32.load; call` while `terpri`'s `"\n"` is
already `i32.const <addr>; i32.const 1; call`. `report.lisp` is nine `princ` literal sites
over seven spellings.

```bash
JAR=/abs/path/to/rontolisp-0.1.0-SNAPSHOT-exec.jar ./measure-print.sh out-before
```

It compiles both programs at `--optimize=off` and `=size`, prints raw/gzip and the section
payloads, runs each under `wasmtime` and diffs stdout against the interpreter running the
`-interp.lisp` spelling. The tail of the run prints the data-segment walk and the emitted
body of `hello`, which is where the two lowerings are visible.

## `ext-patch.py` is a MEASUREMENT HACK, not a design

It lowers every `(princ <literal>)` through `emitWriteLiteral` and counts those occurrences
as needing no `[len]` header. **The value of such a form becomes VOID, which is wrong** --
`princ` returns its argument. The benchmarks here never consume it; a real implementation
has to carry statement position down the reachability walk instead. Apply it to a
throwaway worktree, measure, revert:

```bash
SRC=src/main/java python3 ext-patch.py
```

Measured that way, `--no-gc --optimize=size`: hello 219 -> 206 (code 63 -> 54, data
50 -> 46), report 765 -> 660 (code 504 -> 429, data 141 -> 112). The win is the CODE
section, roughly 8-9 bytes a site -- the header is a bonus, which is the opposite of
`.todo/810`.
