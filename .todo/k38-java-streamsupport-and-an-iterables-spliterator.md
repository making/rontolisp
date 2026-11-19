# k38. Java front end: `StreamSupport` and an `Iterable`'s `spliterator()`

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first.**

Met by YAVI's schema example (`Yavi.schema()` .. `fromMap`) behind k30, measured
2026-11-20: `the class java.util.stream.StreamSupport` in `input/Projection`,
`StreamSupport.stream(properties.spliterator(), false).filter(..).toList()` over an
`Iterable` (`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`, `schema/`, edit 5 of `patch.sh`). On the milestone's path
(`.todo/k33`). Taken out of `.todo/k26`, which lists `spliterator()` /
`Spliterator` / `StreamSupport` among what the streams left.

- A stream here is a closure answering the next element (`.kb/java-frontend.md`,
  "java.util.stream"), so a `Spliterator` can be what `tryAdvance` and
  `forEachRemaining` are over an iterator, and `StreamSupport.stream(s, false)` the
  flow of that. `trySplit` answering `null` is within the specification; the
  characteristics and `estimateSize` of each collection are what a probe under a JDK
  prints.
- `Iterable.spliterator()` is a default method: adding it to `library/java/lang/Iterable.java`
  makes every collection name `Spliterator`, and a library class's member types are
  read with the class (`.todo/k26`: 97 files where 46). Measure a program of one list
  before and after; `Spliterators.spliteratorUnknownSize` is the other way in.
- `parallel == true` is a flag, as `parallel()` is.
