# 816: measuring the print-support surface a module never reads

Four one-line programs, each printing ONE kind of thing, so the built-in literal pool
(`"\n"`, `"\""`, `"\\"`, `"T"`, `"NIL"`) can be seen to arrive whole whatever the program
uses:

| file | prints | needs from the pool |
| --- | --- | --- |
| `princ-literal.lisp` | a string literal | `"\n"` |
| `print-literal.lisp` | a string literal, quoted | `"\n"` `"` `\` |
| `princ-boolean.lisp` | a boolean | `"\n"` `T` `NIL` |
| `princ-number.lisp` | a number | `"\n"` |

```sh
JAR=/abs/path/to/rontolisp-0.1.0-SNAPSHOT-exec.jar ./measure-dead.sh out
```

## `dead816.mjs` is a MEASUREMENT PROBE, not a pass

It rewrites an EMITTED binary: no compiler change, so what it prints is a floor a real
pass has to reach rather than a prototype's claim. It drops the data bytes no code names,
compacts what is left, rewrites the address constants, and -- unless `KEEP_BRACKET=1` --
removes the heap-mark bracket, its scratch local and the heap global.

Liveness is read off the CODE section, because after the printed-literal fold a data
segment is a MIXTURE of headered and headerless literals and cannot be walked
structurally:

- `i32.const A; i32.const L; call F` with `A` inside the segment -> `[A, A+L)` is live
- any other in-segment `i32.const A` -> `[A, A+4+[A])` is live (a header pointer)

The second rule over-approximates on a module that prints NUMBERS: an arithmetic constant
that happens to land in the segment's address range is indistinguishable from a header
pointer without dataflow, and marks a region live that is not. The counts are therefore
EXACT only for the literal-only modules, which is where the item's numbers come from.

Removing the bracket is dead-code elimination only where nothing allocates. On
`princ-number` / `princ-boolean` the module reaches `__itoa`, which does allocate, so the
script is run there with `KEEP_BRACKET=1` and only the data saving is counted.

## `boolean-printing.lisp`

Belongs to `.todo/817`, kept here because it is what showed `T`/`NIL` to be unreachable:

```sh
JAR=... ; java -jar "$JAR" boolean-printing.lisp -o b.wasm --no-gc --optimize=size
wasmtime run --invoke main b.wasm 42     # 1 0 1 0
java -jar "$JAR" boolean-printing.lisp -o b-gc.wasm --optimize=size
wasmtime run --invoke main b-gc.wasm 42  # T NIL T NIL
```
