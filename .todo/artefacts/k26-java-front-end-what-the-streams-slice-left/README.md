# k26: what was measured (2026-11-20)

Results are in `.kb/java-frontend.md`: "java.util.stream" (the stages that stop asking,
`mapMulti`, the gatherers, the spliterators, the streams of a `Random`, the collectors of
a concurrent map) and "java.math" (`String.format` of the two numbers). Each probe is run
by `compare.sh` of
`.todo/artefacts/k12-java-front-end-what-the-time-limits-and-duration-slice-left/`.

- `probes/` -- each prints a JDK 25's lines but where said.
  - `Multi` (50 lines): `mapMulti` and its kin, and when a stream made inside `flatMap`
    is closed. Nine lines differ: what `mapMulti` gives for one element is held (4), an
    inner stream is not closed where a later stage throws (1), `iterator()` and `concat`
    take an inner stream an element at a time where a JDK takes it whole (4).
  - `Gather` (91 lines), `Gather2` (39): `Gatherers`, a gatherer written by hand, `andThen`, the
    defaults, when the state is made and the finisher runs. Eight lines differ, all a
    `push` or an `isRejecting` asked in the middle of an element once the stages after
    have enough; and the time at which `mapConcurrent` hands a finished result on, which
    moves from run to run under a JDK too.
  - `Conc`, `Conc2`: `mapConcurrent` -- how many at once, a mapping that fails, a stream
    that stops asking, a reader that is interrupted.
  - `Split` (580 lines), `Split2`, `Prim`: what a JDK's spliterator of a stream reports
    for 29 sources by 19 stages, the sizes while it is walked, the spliterators of
    numbers. The stream's own differ here by decision: see "Spliterators" in the notes.
  - `Rand`: which draws make an element of a `Random`'s stream between two bounds -- two
    readings of the documentation counted against a JDK over 2,891 seeded ranges (2,414
    and 2,891) -- and the refusals.
  - `Conmap`: the collectors of a concurrent map, their marks and refusals.
  - `Fmt` (1,006 lines): `String.format` of 10 `BigInteger`s by 35 controls and 20
    `BigDecimal`s by 32. What differs is the flags and precisions a conversion does not
    take (`%,x`, `%.2d`, `%,e`: a JDK's three exception classes, left with every other
    argument's) and the class a mismatch names for an object of the program.
  - `Npe`: the words of the refusals of `null`.
- `sizes/` -- `BEFORE=<exec jar> size.sh *.java`, wasm bytes before -> after, with the
  programs of k12's `sizes/`; `functions.sh` names the functions that moved.

  | | before | marks kept through the stages | as committed |
  |---|---|---|---|
  | `SzCount` | 61,728 | 62,102 | 61,792 |
  | `SzMapList` | 144,046 | 145,347 | 143,986 |
  | `SzToList` | 147,040 | 148,409 | 147,375 |
  | `SzSorted` | 157,605 | 160,061 | 157,884 |
  | `SzGroup` | 167,934 | 169,192 | 168,271 |
  | `SzInts` | 61,457 | 62,161 | 61,493 |
  | `FlatOnly` | 126,815 | 128,987 | 127,939 |
  | `Fo` (a format, no stream) | 154,286 | 154,147 | 154,147 |
  | `ListOnly` (no stream) | 156,124 | 156,124 | 156,124 |

  The middle column is the design that reported what a JDK reports of a stream's
  spliterator (the 580 lines of `Split` but the 22 of `lines()`' estimate and a parallel
  `sorted()`): the source's marks and a mask in one `int` of the pipeline, cleared by
  every stage that changes the elements. In `SzMapList` that stage's `link` was 221 B
  (`Flow.changing()`: one `&=` of an `int` field), the field's accessor 46, the source
  recorded by the constructor 101 and its accessor 45, the constructor of the pipeline's
  record 57 + 98, `_dispatch_1` 103, the rest outside the code section. A boolean in
  place of the `&=` moved 124 B from the method into `_dispatch_1`.
