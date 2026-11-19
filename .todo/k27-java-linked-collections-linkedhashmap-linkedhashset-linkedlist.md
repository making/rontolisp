# k27. Java front end: `LinkedHashMap`, `LinkedHashSet`, `LinkedList`

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first**: written from the API documentation and a
JDK's answers, never from its source.

First of the gaps between the source path (k24) and YAVI compiled to wasm (`.todo/k33`): the
classic example reaches `new LinkedHashMap<>()` in `ValidatorBuilder.PredicatesList` and
prints its violations in that map's order. Measured 2026-11-19: with a `HashMap` standing in
the program runs on four backends and prints the JDK's lines in another order
(`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`).

- `LinkedHashMap` (insertion order; the access order of the three-argument constructor and
  `removeEldestEntry` decided by what a probe of everyday code needs), `LinkedHashSet`,
  `LinkedList` (a `List` and a `Deque`). YAVI names them 15, 9 and 2 times.
- `.todo/k26` lists the two maps beside `TreeMap` / `TreeSet` for the collectors
  (`toMap(k, v, merge, LinkedHashMap::new)`): this item takes the linked ones, the sorted
  ones stay there.
- Java source in the library, as `HashMap` is (`.kb/java-frontend.md`, "The library"): what
  a program that makes none pays must stay nothing. Decide by measuring whether the order
  is a second chain through `HashMap`'s entries or a list beside the table.
- Later, not for the milestone: `IdentityHashMap` (2), `EnumSet` (1), `UUID` (2).
- Done when the corpus pins the orders against a JDK on four backends and the `shim/`
  classes of the three are gone from the k24 artefacts with their line of `patch.sh`.
