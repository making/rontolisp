# Clojure: an `Object[]` a Java method returns arrives as a list, so `aset` on it fails

Difficulty: Medium

specter 1.1.6 allocates its scratch arrays through its own Java helper
(`com/rpl/specter/impl.cljc:89`):

```clojure
(defmacro fast-object-array [i] `(com.rpl.specter.Util/makeObjectArray ~i))
;; Util.java: public static Object[] makeObjectArray(int size) { return new Object[size]; }
```

then fills it with `aset` and hands it to `PersistentArrayMap/createAsIfByAssoc`
(`navs.cljc:112-143`, `MAP-VALS`/`MAP-KEYS` on a small map). Here every such transform stops:
`(SETF AREF): The value (NIL NIL) is not of type ARRAY`.

## Repro (2026-11-08, develop `a3c68a63f`) -- no library class needed

```clojure
(let [a (java.util.Arrays/copyOf (object-array [1 2]) 3)] (aset a 2 :x) (prn (vec a)))
```

- oracle: `[1 2 :x]`
- interpreter and JVM class: `Unhandled condition: (SETF AREF): The value (1 2 0) is not of type
  ARRAY` -- the returned array became a list. Measured beside it:
  `(java.util.Arrays/copyOf (object-array [:a :b]) 3)` prints `(:a :b nil)` and its `class` is
  `:list` (oracle: an `Object[]`, `[Ljava.lang.Object;`). The `0` in `(1 2 0)` says the
  `object-array` of two longs crossed INTO Java as a `long[]` (`copyOf` pads a `long[]` with
  `0`), not as `Object[]` -- the outbound direction loses the element type too.

## Analysis

KB "byte arrays": a `java:` member's `byte[]` crosses both ways as a byte array value ("Java's
own array on every backend ... where the host is"). `Object[]` (and `long[]`, `double[]`,
`int[]`, `String[]`) apparently cross as lists -- fine for a read-only result, wrong when the
program mutates it or passes it back to Java expecting the same array. Direction: a Java array
result becomes the matching Clojure array value (`object-array`'s representation; `long-array`,
`double-array`, `int-array` likewise), and an array value passed to a `java:` member typed
`Object[]`/`long[]`/... crosses as the Java array -- with the same identity questions the
byte-array design answered (is the Clojure value a view over the Java array, or a copy that
writes back?). Read the byte-array section first and reuse its arm family.

## Done when

- The repro prints `[1 2 :x]` on the interpreter and the JVM (ci-spec case, host-only; on wasm
  `java.util.Arrays/copyOf` is a call-time refusal unless made portable), plus a round trip:
  an `object-array` passed to a Java method that writes into it, read back after.
- An `object-array` holding only numbers crosses into Java as `Object[]` (the `(1 2 0)` above).
- specter's `MAP-VALS` transform works without the `fast-object-array` hunk of j07's
  `specter/patches.diff` (also needs j15's `createAsIfByAssoc`).
