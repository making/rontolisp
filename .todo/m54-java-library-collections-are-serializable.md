# m54. Java library: the collections and other Java-source classes are `java.io.Serializable`

Difficulty: Low

Found by m23 (2026-11-26). Under JDK 25 `new ArrayList<String>() instanceof Serializable` is true
(likewise `HashMap`, `LinkedList`, `HashSet`, `TreeMap`, `BigDecimal`,
`Throwable`, the `java.time` values, ...); here the library's Java-source classes
(`src/main/resources/am/ik/rontolisp/java/library/**`) do not declare it, so the test is false and
`Serializable s = new ArrayList<String>();` is refused.

- Survey which library classes a JDK 25 makes `Serializable` (`getInterfaces()` up the
  hierarchy, measured by a probe) and add the interface to each.
- Measure what a program naming no `Serializable` pays (expected: nothing) and what one that does
  pays; regenerate `jdk-members.txt` if a member list changes.
- Pin in java-spec beside `an-object-is-copied-by-clone-where-its-class-is-cloneable`.
