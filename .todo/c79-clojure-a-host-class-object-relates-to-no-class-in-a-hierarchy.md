# c79. Clojure: a host class object relates to no class in `isa?`, `parents` or `ancestors`

Difficulty: Medium

Interpreter and JVM, measured 2026-10-04 against clj 1.12.6. `class` of a host object that is
no throwable answers its host `Class` object (`%clojure-host-class`), and the hierarchy
runtime walks only class keywords (`.kb/clojure-frontend.md`, "Class chains"):

```clojure
(isa? (class (java.io.File. "x")) Object)              ; oracle true, here false
(isa? (class (java.util.ArrayList.)) java.util.List)   ; oracle true, here false
(parents (class (java.util.ArrayList.)))                ; oracle #{java.util.AbstractList java.util.List ...}, here nil
```

`Object` lowers to the keyword `:java.lang.Object` in a hierarchy position, and `java.util.List`
to the host class object, so neither meets a `Class` child. A fix needs the host's answer
(`isAssignableFrom`, `getSuperclass`/`getInterfaces`) for a `Class` child -- a host arm like
`%clojure-host-class-rows`, stand-in NIL without `java:` -- and a decision on which spelling a
non-chained class (`java.util.List`) lowers to there, so a class keyword and a `Class` object
compare. Wasm has no host object, so no backend divergence beyond what exists.
