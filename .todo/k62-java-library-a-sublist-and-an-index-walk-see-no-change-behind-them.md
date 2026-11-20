# k62. Java library: a sublist and an index walk see no change made behind them

Difficulty: Low

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** Behaviour is learned by running a program under a JDK, never from its sources.

Found by k55 (2026-11-20, `SeqList.java` of its probes): `AbstractList.SubList` -- the
sublist of every list of the library -- and `AbstractList`'s own walks (`IndexIterator`,
`IndexWalk`) keep no count of changes, so a loop that adds to a sublist while it walks it
never ends where a JDK's walk throws `ConcurrentModificationException` at the next element:

```java
List<Integer> sub = new ArrayList<>(List.of(0, 1, 2, 3)).subList(1, 3);
for (Integer x : sub) {
    sub.add(0, x);          // a JDK: ConcurrentModificationException; here: no end
}
```

A JDK's sublist of an `ArrayList` also refuses once the list itself changed behind it
(any member of the sublist, not only its walk); a program's own `AbstractList` keeps
`modCount`, which the library's has not got (`ArrayList`, `LinkedList` and `HashMap` keep
`changes` of their own): a program's class under `AbstractList` reading `modCount` is
`cannot find symbol: variable modCount`, where a missing member of a library class is to be
refused by name (`.kb/java-frontend.md`, "The library"). What to measure first: what a count
in `SubList` costs a program that makes a sublist and does not change it (k47's
`sizes/size.sh`), and whether `modCount` comes with it.
