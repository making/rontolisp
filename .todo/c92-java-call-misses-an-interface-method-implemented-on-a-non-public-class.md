# c92. `java:call` misses an interface method implemented on a non-public class

Difficulty: Medium

`(java:call (java:call (java:call m "entrySet") "iterator") "hasNext")` on a `HashMap` is
`No matching method java.util.HashMap$EntryIterator.hasNext with 0 argument(s)` on the
interpreter and the JVM (measured 2026-10-04); Clojure's `(.hasNext (.iterator (.entrySet
m)))` answers `true` in the oracle. `hasNext` is declared on the package-private
`HashMap$HashIterator`, and `ReflectiveJavaClasses.accessibleMethod` (and its copy in
`JavaBridgeTemplate`, pinned by `JavaBridgeTemplateParityTest`) walks the DECLARING class's
superclasses and their interfaces, never the receiver class's own interfaces
(`EntryIterator implements Iterator`). The search should start at the receiver class.
Every non-public iterator/view of the JDK collections is affected.
