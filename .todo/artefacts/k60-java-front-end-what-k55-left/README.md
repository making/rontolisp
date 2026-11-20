# k60: what was measured (2026-11-20)

Results are in `.kb/java-frontend.md`, "The collections of Collections, a bridge's cast and
PriorityQueue" (and "Inference" for a waiting argument tried against the target).

- `four.sh JAR File.java` -- the program on the interpreter, the JVM, wasm and the
  component, each counted against `File.jdk.txt` beside it (`java File.java > File.jdk.txt`
  first) and against the interpreter.
- `sz.sh A.jar B.jar File.java...` -- the wasm bytes of each program under two exec jars;
  `fn.sh A.jar B.jar File.java` -- the functions whose bytes differ
  (`-Drontolisp.wasm.debug-func-sizes`). The size programs are those of
  `.todo/artefacts/k12-..`, `k26-..`, `k47-..` and `k55-..` (`sizes/`).
- `probes/` -- each prints a JDK 25's lines on the four backends but where said.
  - `Imm` (1,406 lines): every member of 22 immutable and unmodifiable collections
    (`Collections`' empty, single and copied ones, `List.of`, `Set.of`, `Map.of`,
    `Stream.toList()`, the unmodifiable wrappers, `Arrays.asList`). 125 lines differ: a
    `NullPointerException`'s message (a JDK words it), a class's name, the deviations
    `deviations.md` states (`List.of` of one or two, its iterator's `remove()`, the nulls of
    `Set.of` and `Map.of`, `Map.of`'s views, `Arrays.asList`'s words, `Stream.toList()`, an
    `AbstractList` iterator's cause). The jar of bdf402ac8 differed in 346, and refused
    `singletonMap`, `emptyIterator`, `emptyListIterator` and `emptyEnumeration`.
  - `Imm2` (117 lines): the empty iterators, `singletonMap`'s views and entry, the
    spliterators of `nCopies` by size, the parts of `List.of`, the null queries of `Set.of`
    and `Map.of`, `Arrays.asList`'s iterator. 28 lines differ: names, a message, a stream's
    spliterator, `List.copyOf` of two, and the deviations above.
  - `SubSp`: the parts of `List.of` (a JDK alone).
  - `Rev` (120 lines): the bulk changes of the reversed views of 12 lists; all a JDK's
    (`retainAll` and `removeAll` walk the view, `CopyOnWriteArrayList`'s view hands them on)
    but `Arrays.asList`'s words.
  - `Raw` (20 lines): a raw `Comparable` given another class, a mixed `TreeMap`, `TreeSet`,
    sort and search. Every line a JDK's but a `ClassCastException`'s module text, a
    `NullPointerException`'s message and the pair a sort compares first (`SortCalls`: the
    comparator calls of a sort differ from a JDK's).
  - `RawMax`: `Collections.max` of a raw `List` is refused (`inferred type does not conform
    to upper bound(s)`), where javac takes it unchecked: `.todo/k71`.
  - `PQOrder` (a JDK alone): a binary heap written beside `java.util.PriorityQueue`, compared
    after every step of 300 seeded runs (offer, poll, remove, removeIf, addAll; 60,000
    steps) and 300 queues made of a list: the same text each time with a bulk removal that
    keeps the survivors in order and makes them a heap again (an iterator's removals one by
    one differ in all 300). `PQWalk` (a JDK alone): 2,000 walks that remove, beside a walk
    giving an element a removal moved behind it after the array, first moved first: all the
    same.
  - `PQSeeded` (48,400 lines), `PQMembers` (66), `PQMore` (20), `Dijkstra`: the queue under
    every change, its members and refusals, what a walk notices. All a JDK's but three lines
    of `PQMembers`: a spliterator's `trySplit` and two comparators' identities.
  - `Infer2`: a `TreeSet` and a `TreeMap` made with `<>` of a comparator whose lambda needs
    the target; refused before this change (`Object.age() is not supported`).
  - `SzPriority` 138,238 B of wasm; `Bench` (1,000,000 adds and polls): JDK 25 570 ms, the
    JVM class 3.7 s, wasm 5.0 s.
- Sizes (wasm bytes, `sz.sh` against the jar of bdf402ac8): unchanged but a program over
  `List.of` smaller (`MapOnly` 137,639 -> 137,302, `FlatOnly` 127,939 -> 126,933, `SzQueue`
  66,243 -> 65,535, `SzRemoveAll` 157,547 -> 156,534, `SzDequeReversed` 133,784 -> 132,583,
  `SzUnmodSet` 104,824 -> 104,124), and the bridged arms of `Comparable.compareTo`
  (`SzSorted` 157,884 -> 158,398, `SzTreeSet` 142,377 -> 142,876, `SzNav` 140,531 ->
  141,065). YAVI (`examples/java/yavi`): `Member.java` 663,174 -> 662,353, `Booking.java`
  1,204,473 -> 1,208,802. Drafts measured and not made: the words of `List.of` of one or two
  (+440 B in every `List.of` program), a dispatcher for a bridged one body (`SzEnumMap`
  +2,628 B), each arm raising its own exception (+200 B an arm), `Arrays.asList`'s own
  iterator (`SzAsList` +898 B), `Set.of`'s and `Map.of`'s null checks and `Map.of`'s views
  as `AbstractMap`'s (YAVI's `Booking` 1,216,752 with them and the asList iterator), the
  five classes' members each their own (YAVI's `Booking` 1,217,552), `AbstractList`'s
  iterator for an empty list (`SzSingleton` 131,589 -> 132,936).
