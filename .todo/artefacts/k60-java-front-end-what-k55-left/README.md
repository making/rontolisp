# k60: what was measured (2026-11-20)

Results are in `.kb/java-frontend.md`, "The collections of Collections and a bridge's cast".

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
    `Stream.toList()`, the unmodifiable wrappers, `Arrays.asList`). 110 lines differ: a
    `NullPointerException`'s message (a JDK words it), a class's name, the deviations
    `deviations.md` states (`List.of` of one or two, its iterator's `remove()`,
    `Stream.toList()`, the views of a map, an `AbstractList` iterator's cause). The jar of
    bdf402ac8 differed in 346, and refused `singletonMap`, `emptyIterator`,
    `emptyListIterator` and `emptyEnumeration`.
  - `Imm2` (117 lines): the empty iterators, `singletonMap`'s views and entry, the
    spliterators of `nCopies` by size, the parts of `List.of`, the null queries of `Set.of`
    and `Map.of`, `Arrays.asList`'s iterator. 19 lines differ: names, a message, a stream's
    spliterator, `List.copyOf` of two, `Map.of`'s entries.
  - `SubSp`: the parts of `List.of` (a JDK alone).
  - `Rev` (120 lines): the bulk changes of the reversed views of 12 lists; all a JDK's
    (`retainAll` and `removeAll` walk the view, `CopyOnWriteArrayList`'s view hands them on).
  - `Raw` (20 lines): a raw `Comparable` given another class, a mixed `TreeMap`, `TreeSet`,
    sort and search. Every line a JDK's but a `ClassCastException`'s module text, a
    `NullPointerException`'s message and the pair a sort compares first (`SortCalls`: the
    comparator calls of a sort differ from a JDK's).
  - `RawMax`: `Collections.max` of a raw `List` is refused (`inferred type does not conform
    to upper bound(s)`), where javac takes it unchecked: `.todo/k71`.
- Sizes (wasm bytes, `sz.sh` against the jar of bdf402ac8): unchanged but a program over
  `List.of` smaller (`MapOnly` 137,639 -> 137,302, `FlatOnly` 127,939 -> 126,933, `SzQueue`
  66,243 -> 65,535, `SzRemoveAll` 157,547 -> 156,534, `SzDequeReversed` 133,784 -> 132,583),
  `SzUnmodMap` 163,606 -> 163,795, and the bridged arms of `Comparable.compareTo`
  (`SzSorted` 157,884 -> 158,398, `SzTreeSet` 142,377 -> 142,876, `SzNav` 140,531 ->
  141,065). Drafts measured and not made: the words of `List.of` of one or two (+440 B in
  every `List.of` program), a dispatcher for a bridged one body (`SzEnumMap` +2,628 B),
  each arm raising its own exception (+200 B an arm).
