# cl-unicode does not fit a compiled program

Difficulty: High

`(ql:quickload "str")` -- and any other program that reaches cl-unicode --
loads and runs on the interpreter and in the native binary, but
`-o Prog.class` dies with

```
constant pool overflow: this class needs more than 65534 constant pool entries,
the JVM class-format limit; split the program
```

The tables rontolisp generates for cl-unicode (`eval/ClUnicodeTables`) are ~5 MB
of data: ~40,000 character names in both directions, plus fourteen range trees.
That is a legitimate amount of data for a Unicode library -- SBCL compiles it to
a fasl once -- and it is simply more than one `.class` can name.

Related, and probably the same fix: `.todo/017` (the JVM baked-constant limit)
and `.kb/jvm-method-size-limits.md`.

**WASM is a different story and was measured** (2026-08-26, wasmtime 47,
`(ql:quickload "str")` + one `str:title-case`): Preview 1 COMPILES it, in 8.6 s,
to a 6.7 MB module. The run then dies inside cl-unicode's own load with
`Unhandled condition: Unknown property name "Cs".` -- which is `.todo/543`, not
this item: `*property-map*` is an `equalp` table and only the interpreter folds
its keys, so the case-insensitive lookup `derived.lisp` depends on misses. What
this item still owns on WASM is the three MINUTES the run spent before getting
there. Fix 543 first, then re-measure; the load time is the same laziness
question the JVM side has.

## Directions

- The name tables are the bulk and only `unicode-name` / `character-named` read
  them. `Uax15Tables`' answer to the same problem was to emit bulk numbers as
  decimal runs inside STRING literals scanned by a generated helper (one
  constant pool entry per chunk instead of two per integer) and to defer the
  build to first read. Both apply here directly: the range trees could be runs,
  and every table could be built on first read.
- A multi-class JVM output would lift the ceiling for everyone, not just this
  library (`.todo/017`).

## Definition of done

`java -jar rontolisp.jar str-demo.lisp -o StrDemo.class && java StrDemo` prints
what the interpreter prints, and the same program runs on both WASM backends --
or the WASM backends are measured and their own limit is recorded here.
