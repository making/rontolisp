# k47: what was measured (2026-11-20)

Results are in `.kb/java-frontend.md`, "The sorted collections". Each probe is run by
`compare.sh` of `.todo/artefacts/k12-java-front-end-what-the-time-limits-and-duration-slice-left/`
(a JDK 25, then the interpreter; with `all` the JVM, wasm and the component).

- `probes/` -- each prints a JDK 25's lines on the four backends but where said.
  - `Tree` (112 lines): a `TreeMap`'s members, views, refusals, iterators, entries, the
    defaults over a tree and the collectors into one. Eight lines differ: a
    `NullPointerException`'s message (a JDK words it), a `ClassCastException`'s (a JDK names
    the modules), and a raw map comparing an `Integer` with a `String`, a
    `RuntimeException` with the run time's words here (`deviations.md`, "A type argument is
    not checked where the program runs").
  - `TSet` (97 lines): `TreeSet`, the spliterators of the views, the unmodifiable,
    synchronized and empty wrappers, the skip list. Four lines differ: an NPE's message, the
    class names of the wrappers, and the two messages of `Uoe` below.
  - `SplitGrid` (251 lines): the characteristics, size and comparator of the spliterator of
    every part of eight views of a tree, a tree of a comparator, a skip list of each, the
    sets and the wrappers. One line differs: a JDK keeps one `keySet`, `entrySet` and
    `descendingMap` of a map (`==`).
  - `Bounds` (89 lines): every bound of 13 views of a tree and of a skip list by both
    flags, and the neighbours of every key. One line differs: NPE messages.
  - `Calls`: which keys a comparator is asked about. Identical on these small trees; a
    larger tree is balanced another way.
  - `Names`: the classes of the views. Three differ: one class here for every view.
  - `Skip`: a skip list's members, views, functions, refusals, an iterator under changes and
    four threads putting. All lines a JDK's.
  - `Stress` (seeded, 24,000 operations over maps, views, sets and iterators), `Grow`
    (160,000 over a tree of up to 54,415 keys): all lines a JDK's.
  - `RemoveAll`: `AbstractSet.removeAll` walks the smaller of the two (equal sizes: the set).
  - `Uoe`: the messages of an unmodifiable view's entry and iterator. Four differ, kept
    (`.kb/java-frontend.md`, "The sorted collections": measured and not made): an entry of
    an unmodifiable map says `not supported`, the iterator of an unmodifiable set, of a key
    set and of a singleton `remove`, where a JDK's say nothing.
  - `TreeOrder`, `SplitRows`: the corpus case and the spliterator rows, as files.
  - `Rand`, `Rand2`: `Random`'s bytes, floats and draws between bounds against the streams'
    draws of the same seed (2,500 ints, 1,500 longs, 1,250 longs of a bound, 2,000 doubles,
    all the same) and the refusals; `nextGaussian(mean, stddev)` and `nextExponential`, a
    JDK's own algorithm, stay refused: with their two lines taken out, one line differs (an
    NPE's message).
  - `Wide`: bounds too far apart for a `double` or a `float`, against five candidate
    formulas over 2,000 seeds: halving the bounds and doubling the draw gives a JDK's bits
    on 14,000 doubles and 10,000 floats, scaling the draw by each bound 7,417 doubles.
- `sizes/` -- wasm bytes by `size.sh` of `.todo/artefacts/k26-java-front-end-what-the-streams-slice-left/sizes/`
  (`BEFORE=<exec jar of 17ddb5e21>`): `SzTree` 136,456, `SzHash` 143,443, `SzTreeSet`
  142,364, `SzNav` 140,539, `SzSkip` 164,996, `SzRemoveAll` 156,880 -> 157,547, `SzUnmod`
  175,139 (175,139 -> 177,338 with the JDK's messages, `SzUnmodMap` 163,860 -> 166,142,
  `SzUnmodSet` 104,850 -> 105,151). The size programs of k12 and k26: unchanged.
- `bench/TreeBench.java` -- 200,000 puts and gets, a walk and 100,000 removes, best of three:
  JDK 25 196 ms (`HashMap` 46), the JVM class 369 (209), precompiled wasm 739 (270).
