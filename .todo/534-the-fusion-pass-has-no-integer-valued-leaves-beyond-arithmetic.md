# 534. The JVM fusion pass has no integer-valued leaves beyond arithmetic, so `random` and `aref` break the whole tree

Difficulty: Medium (an extension of `JvmIntFusionCompiler`, whose guard/bail
machinery `.todo/412` already built; the work is deciding which leaves may
produce a raw `long` and what each one's bail shape is)

Spike sources: the directory of the same name. Found while comparing the JVM
backend against hand-written Java on `.todo/517`'s
four rows (2026-08-26, this machine, best of 5, JVM startup 0.087 s and SBCL
startup 0.007 s subtracted so the rows measure the engine, not the launcher).

## The defect

`.todo/412` fuses an integer expression TREE into raw `long` arithmetic, but its
leaves are arithmetic operands only. `random` and `aref` are opaque
`Object`-valued calls to it, so a tree that contains one is not fused at all --
it falls back to the fully generic path, including the `+` that would otherwise
have fused. `javap -c` on the two spikes, compiled from
`.todo/517-sbcl-class-performance-on-the-compiled-backends/`:

```
; (setq s (+ s (random 1000000)))            random-toplevel.lisp, _top$0
  49: invokestatic  Long.valueOf:(J)          <- box the LIMIT
  52: invokestatic  _random:(Object)Object    <- generic, returns boxed
  55: invokestatic  _add:(Object;Object)      <- generic add, boxes the sum

; (setq s (+ s (aref arr (random 1000000)))) aref-toplevel.lisp, _top$0
 327: invokestatic  Long.valueOf:(J)
 330: invokestatic  _random:(Object)Object
 333: invokestatic  _aref1:(Object;Object)    <- takes the index BOXED
 336: invokestatic  _add:(Object;Object)
```

`_aref1` then unboxes the index and re-boxes the element, around a chain of
type tests, for an array whose elements are already a flat `long[]` since
`.todo/527`:

```
_aref1:  instanceof String (is it a string?), checkcast Long + intValue on the
         index, iadd 1, call _rmGet
_rmGet:  checkcast ArrayList, get(0), checkcast Object[], arraylength > 4,
         null-check slot 3 in a displacement loop, arraylength == 6,
         checkcast [J, laload, compare against the MIN_VALUE nil sentinel,
         Long.valueOf                          <- re-box what laload produced
```

So the packed representation reaches `laload` and then throws the result back
into a box, and the index made the reverse trip on the way in.

## The measurement (compute only, seconds)

| row | rontolisp JVM | SBCL | Java, hand-written primitive | Java, boxed/collection |
| --- | --- | --- | --- | --- |
| `loop sum` (fused -- the control) | **0.152** | 0.193 | 0.073 (`long` loop) | 1.083 (`.boxed().reduce(Long::sum)`) |
| 10^7 x `random` | 0.232 | 0.143 | 0.053 (`ThreadLocalRandom`) | 0.113 (`new Random()`, `Long` accumulator) |
| 10^7 x `aref` | 0.412 | 0.263 | 0.083 (`long[]`) | 0.403 (`ArrayList<Long>.get`) |

The control row is the argument: where the tree IS fused, the JVM backend beats
SBCL and is 2.1x hand-written Java. Where one opaque leaf enters the tree, it
falls to 1.6x SBCL and 5x Java -- and lands exactly on Java's `ArrayList<Long>`,
i.e. it performs like a boxed collection read despite holding a `long[]`.

`.todo/517` closed with both rows inside its 2x-of-SBCL target on wall clock,
so this is not a reopened acceptance failure; it is the next factor down.

## What to build

Teach `JvmIntFusionCompiler` two more leaf kinds, each producing a raw `long`
under the guard/bail contract already in `.kb/jvm-int-fusion.md`:

- `(random <integer>)` -- the draw is already a `long` internally since
  `.todo/528` (`.kb/random.md`); the leaf wants the generator's raw result, not
  `_random`'s boxed return.
- `(aref <general array> <integer>)` on the PACKED shape -- the header-length
  tag test stays, but the hit path is `laload` straight into the tree, with the
  nil sentinel and the non-packed tag as bail edges into today's `_aref1`.

Check the same treatment for the other integer-valued built-ins a hot loop
reaches through (`length`, `char-code`, `elt` on a packed vector); each one is
the same shape and the same question -- does it have a raw-`long` hit path with
a cheap bail.

## Acceptance

- No `Long.valueOf` and no `_add` in the hot loop of either spike's `_top$0`
  (`javap -c` on the compiled class is the pin, as `.todo/412` pinned its own).
- Compute-only `random` and `aref` rows within 2x of hand-written primitive Java
  (<= 0.11 s and <= 0.17 s on this machine), which also puts both inside SBCL.
- The generic path stays reachable and bit-identical on bail: BigInteger
  promotion, a nil element, a non-packed or displaced array, a float index.
- `JvmLispCompilerTest`'s fusion/generic equivalence pin extends to both leaves;
  all four backends stay output-identical.
