# k55: what was measured (2026-11-20)

Results are in `.kb/java-frontend.md`, "The sequenced collections". Each probe is run by
`compare.sh` of `.todo/artefacts/k12-java-front-end-what-the-time-limits-and-duration-slice-left/`
(a JDK 25, then the interpreter; with `all` the JVM, wasm and the component). The four
backends print the same lines on every probe; where they differ from a JDK is said below.

- `probes/` -- each prints a JDK 25's lines but where said.
  - `SeqList` (681 lines): every member of the reversed view of 16 lists (`ArrayList`,
    `LinkedList`, `Vector`, `CopyOnWriteArrayList`, `List.of`, `Arrays.asList`, the
    unmodifiable and synchronized wrappers, a sublist, the immutable lists of `Collections`,
    the lists of a stream, `Stack`), a walk while adding, a `LinkedList`'s view as a deque.
    Twenty lines differ, none the view's own: the immutable lists of `Collections` refuse a
    change that changes nothing (`deviations.md`), `CopyOnWriteArrayList`'s view checks an
    index against itself, the reversed view of `Stream.toList()` checks an index before it
    refuses, `Arrays.asList(..).removeIf` says nothing where a JDK says `remove`, a sublist's
    walk sees no change behind it (`.todo/k62`; the probe bounds its loops, which ran for
    ever).
  - `SeqDeque` (164 lines): every member of the view of `ArrayDeque`, `LinkedList`,
    `ConcurrentLinkedDeque`, `LinkedBlockingDeque`, a bound deque's refusals, a deque of the
    program's. All lines a JDK's once the concurrent deques' iterators removed from the deque
    (they removed from their copy).
  - `SeqSet` (187 lines): `LinkedHashSet`, `TreeSet`, `unmodifiableSequencedSet`,
    `newSequencedSetFromMap`, `newHashSet`'s tables, the defaults over a set and a collection
    of the program's, the view of a sorted set that is not navigable. One line differs: an
    unmodifiable iterator's `remove` says `remove` (k47 measured and did not make the JDK's).
  - `SeqMap` (190 lines): `LinkedHashMap`, `TreeMap`, `unmodifiableSequencedMap`, an access
    order map's eldest entry under `putFirst`, `newHashMap`'s tables, the view of a sorted map
    that is not navigable, the defaults over a map of the program's. Three lines differ: an
    unmodifiable map's entry and iterator (k47's), and `values()` equal to itself (a new view
    at each call, `deviations.md`).
  - `Seq2` (92 lines): the ranges of a sorted view, a live walk (`3210`), how often the
    defaults ask a map for its `keySet()`, `newSetFromMap`, access order through the views,
    `newHashMap(n)` at 6 to 97 mappings, `RandomAccess`, the unmodifiable sequenced wrappers.
    Five lines differ: a comparator printed, the immutable lists of `Collections`, an entry's
    class name and refusal (k47's).
  - `Seq3` (261 lines): every bound of three ranges of the view of a sorted set and of a sorted
    map, the entries of a `LinkedHashMap`'s sequenced entry set, the defaults' views over an
    empty map of the program's. One line differs: an identity hash code.
  - `Ends`: the corpus case `a-sequenced-collection-has-two-ends-and-a-reversed-view` as a
    file. `Fixes`: its lines of what the probes found beside the views; the jar of 5303feb71
    prints the first five otherwise (the fifth the queues' removals that removed nothing)
    and stops at `CopyOnWriteArrayList.removeAll`'s `UnsupportedOperationException`.
- `sizes/` -- wasm bytes by `size.sh` of `.todo/artefacts/k26-java-front-end-what-the-streams-slice-left/sizes/`
  (`BEFORE=<exec jar of 5303feb71>`): `SzLinked` 147,107 unchanged, `SzReversed` 133,376 ->
  143,015, `SzQueue` 63,954 -> 66,243; `SzPutFirst` 153,334 and `SzDequeReversed` 133,784
  (no `putFirst` or `Deque.reversed()` before). The size programs of k12, k26 and k47:
  unchanged but `SzUnmod` 175,139 -> 175,170, `SzUnmodMap` 163,860 -> 163,606, `SzUnmodSet`
  104,850 -> 104,824 (the equality of an unmodifiable view).
- The JVM: the reversed view's `next` and `previous` made `ListIterator`'s dispatchers and
  `List.get`'s one tail group of members of one and two parameters, whose rooted method
  failed the verifier in the corpus program (`Bad local variable type`); reproduced in Lisp by
  the defuns DR, DA and DB of `JvmLispCompilerTest#aCycleTheRootDoesNotCloseDispatchesOnAMemberIndex`.
