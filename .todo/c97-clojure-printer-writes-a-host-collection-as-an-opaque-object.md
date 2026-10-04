# c97. Clojure printer writes a host collection as an opaque object

Difficulty: Medium

`(def al (java.util.ArrayList. [1 2]))`, a `LinkedList`, a `HashMap` holding `"a" 1`, a
`HashSet` holding `"x"`: on the interpreter and the JVM `prn`, `println` and `pr-str` of each
answer `#<java java.util.ArrayList>` (and so on). The oracle (clj 1.12.6, measured
2026-10-04):

- `prn`: `[1 2] (1 2) {"a" 1} #{"x"}` -- `print-method` of a `RandomAccess` list is a vector,
  of another `List`/`Collection` a list, of a `Map` a map, of a `Set` a set, each member
  printed readably, under `*print-readably*`.
- `println`: `#object[java.util.ArrayList 0x.. [1, 2]]` -- not readably, `print-object` (the
  existing `#<java C>` deviation, which drops the hash and the `toString`).
- `pr-str` is `prn`'s `"[1 2]"`; `str` is `toString` (`{a=1}`, already right).

The seq and map verbs already read a host collection (`.kb/clojure-frontend.md`, "Java
interop", host collections and host maps); the printer's host arm (`%clojure-write`) needs a
readable clause behind the same host-object family test, so a program naming no `java:`
operator stays byte-identical. The user-doc printing deviation in
`doc/*/clojure/deviations.md` names `#<java C>` for every host object.
