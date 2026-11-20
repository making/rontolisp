# k74. Java library: a CopyOnWriteArrayList's sublist and the answers of a stale part

Difficulty: Low

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** Behaviour is learned by running a program under a JDK, never from its sources.

What k62 left (2026-11-20, `.kb/java-frontend.md`, "The sublist and the walks of a list").
The probes are `.todo/artefacts/k62-java-library-a-sublist-and-an-index-walk-see-no-change-behind-them/probes/`
(`Sub.java`, `Sub2.java`: a JDK's lines against the interpreter's, `diff` of the two).

1. **`CopyOnWriteArrayList.subList`** is `AbstractList`'s part over a list that keeps no
   `modCount`, so a part refuses nothing and its iterator is live: `for (x : part)
   part.add(0, x)` never ends where a JDK's ends after two elements (its part's iterator
   walks the array there was) and `part.get(0)` after `list.add(9)` answers where a JDK's
   throws `ConcurrentModificationException`. A JDK's part compares the list's array with the
   one it was made over. Measure what a class of its own costs a program that makes one.
2. **A stale part answers where a JDK's refuses and refuses where a JDK's answers**, listed
   in `doc/*/java/deviations.md` ("A part of a list ..."): `hasNext` (a walk of a part reads
   the part's own size, not the list's), `estimateSize()` of a part's spliterator, the range
   errors of `subList` and `addAll(int, ..)` before the change is looked at, `addAll` of
   nothing, the part of a `LinkedList` or a program's list making a `subList`. What each
   costs is in the `.kb` section: a walk's `hasNext` that spares a stale part is 800 B in
   every program that walks an `AbstractList` (it names `SubList`).
3. `AbstractList.removeRange` (protected) and `AbstractList.clear()` through it: a program's
   class overriding `removeRange` is `method does not override`; a JDK's `clear()` removes
   from the front, the library's from the end.
4. The class of a part of an `ArrayList` is `java.util.ArrayList$SubList` (and a
   `RandomAccess` one); here `AbstractList$SubList`, `RandomAccess` kept by a flag.
