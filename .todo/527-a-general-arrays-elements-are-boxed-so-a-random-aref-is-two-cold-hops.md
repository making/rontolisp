# 527. A general array's element is a boxed `Long` behind an `ArrayList`, so a random `aref` is two cold hops

Difficulty: High (the fix is the general array's REPRESENTATION on the JVM
backend -- a packed backing store chosen per element type, with the
fill-pointer / adjustable / displaced surface and `_rmGet`/`_rmSet` moving with
it; `.kb/core-representation.md` and `.kb/adjustable-arrays.md` both change)

Child of `.todo/517`, filed from its "measured, understood, not yet filed"
section once `.todo/518`/`519`/`520`/`521`/`522`/`412` had all landed and the
four rows were re-taken on a fixed baseline (2026-08-26). This is one of the two
rows still outside the parent's 2x target -- the worse one, at **4.7x**.

**`.todo/517` filed this as "`_aref1` is a generic helper call". Re-measuring
says that diagnosis is wrong.** The helper call is free; the representation is
not.

## The defect

A general array (`(make-array n)`, element type `t`) is a `java.util.ArrayList`
whose slot 0 holds an `Object[]` header and whose slots `1..n` hold the elements
as boxed objects (`JvmArrayRuntimeBuilder`). One `(aref a i)` therefore walks:

```
ArrayList  ->  its Object[] elementData  ->  elementData[0]  (the header, hot)
           ->  elementData[1+i]          ->  a java.lang.Long  ->  .longValue()
```

Two of those hops are COLD and dependent: `elementData[1+i]` is a random probe
into an 8 MB pointer array, and the `Long` it answers is a separate 16-byte heap
object in a 16 MB scatter. SBCL's `(make-array n)` is a simple-vector holding
IMMEDIATE fixnums: one probe into 8 MB, no second object, no dereference.

`Long.valueOf` caches only `-128..127`, so a million-element array of counts is
a million distinct boxes. There is no fixnum-immediate encoding and no packed
backing store for element type `t`; `(unsigned-byte 8|16|32)` and the floats
pack (`.kb/packed-integer-vectors.md`), 64-bit integers and `t` do not.

## What it costs (2026-08-26, this machine, fixed baseline)

10^7 random reads, `aref` loop minus the identical loop without the `aref`,
inside a `defun`. **The cost is linear in the array size on the JVM and flat on
SBCL** -- which is the whole proof that this is the representation and not the
dispatch:

| array size | JVM ns/access | SBCL ns/access |
| --- | --- | --- |
| 1,000 | **1.7** | 3.0 |
| 10,000 | 15.6 | 1.6 |
| 100,000 | 34.9 | 7.4 |
| 1,000,000 | **55.5** | 10.8 |

At 1,000 elements -- everything in L1 -- the JVM is FASTER than SBCL. `_aref1`
inlines, the `instanceof String` test folds, `_rmGet`'s displacement loop never
iterates. The generic helper costs 1.7 ns, not 50. What costs 55 ns is walking
24 MB of scattered heap with a dependent load chain.

`perf stat` on the 10^6 row against its own `draw-1000000` baseline:

```
                    aref run        draw-only run
cycles              4,079,558,445   1,870,569,860
insn per cycle               0.57            0.77
dTLB-load-misses       13,483,299         779,069      <-- 1.35 per access
```

Stalled, not busy. `-XX:+UseTransparentHugePages` takes the row from 0.947 s to
0.845 s (55.5 -> 45.2 ns/access), so roughly a fifth of it is page-walk alone.

The two representations that already pack are the measured ceiling of a fix, on
the same array size and the same access pattern:

| representation of a 10^6-element array | JVM ns/access |
| --- | --- |
| general (`ArrayList` of boxed `Long`) | 55.5 |
| `:element-type '(unsigned-byte 32)` (packed `int[]`) | **19.6** |
| `:element-type 'double-float` (packed `double[]`) | 22.1 |
| SBCL `(make-array n)`, immediate fixnums | 10.8 |

## What to build

Give the general array a packed backing store when its elements permit one, so
the common case -- a vector of integers -- is one probe into one flat array.
Two candidate shapes; measure before choosing:

1. **Specialize on first store.** The array starts packed as `long[]` and stays
   packed while every store is an in-range integer; the first non-integer store
   widens it to today's `ArrayList`. This gets SBCL's layout for the case that
   matters and costs a branch on the store path. The widening must be invisible:
   `_rmGet`/`_rmSet`, the displacement chain, `array-become`, the fill-pointer
   surface and `_strv` all read the header, so the representation tag belongs
   there and every one of those helpers has to test it.
2. **A fixnum-immediate encoding** in an `Object[]`, i.e. stop allocating a
   `Long` per element. This is the deeper fix and reaches every boxed integer,
   not just array elements -- but it is a change to `.kb/core-representation.md`
   itself and interacts with `.kb/jvm-int-fusion.md`'s just-landed unboxed
   expression trees. Do not start here without measuring (1) first.

Whichever lands, the header-tag test is the risk: `_rmGet` is on the read path of
EVERY array, string and character-vector access on the backend, and it already
walks a displacement chain per call. Adding an unconditional branch there must
not cost the 1.7 ns case anything.

Do this on the JVM first: it is the backend that carries the row (1.22 s
top-level against SBCL's 0.26). The wasm backend's general `aref` has a
DIFFERENT defect and is not this item -- its per-access cost is ~61 ns at 1,000
elements and ~76 ns at 1,000,000, i.e. size-INdependent dispatch overhead, so it
wants a call-shape fix, not a layout fix. Recorded as a residual under
`.todo/517`.

## Acceptance

- `.todo/517`'s `aref` row, TOP-LEVEL spelling, within 2x of SBCL's 0.26 s on at
  least one compiled backend (i.e. <= 0.52 s where the JVM is 1.22 s today).
- The size sweep above is FLAT in the array size to within 2x between 1,000 and
  1,000,000 elements, the way SBCL's is -- the size-linearity is the defect, and
  a number that only improves at 10^6 has not fixed it.
- A general array that receives a non-integer element still answers every
  element correctly, including one stored after a million integers, and
  `array-element-type` still answers `t`.
- The fill-pointer, adjustable, displaced and character-vector surfaces
  (`.kb/adjustable-arrays.md`) are byte-identical in behaviour -- `copy-array`,
  `adjust-array`, `array-displacement`, `vector-push-extend` all pinned.
- `ci-spec.yaml` and `ExamplesE2eTest` byte-identical on all four backends.
