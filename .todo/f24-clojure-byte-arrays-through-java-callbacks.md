# f24. Clojure: a byte[] Java hands a proxy, reify or fn is a byte array, and one it answers a byte[]

Difficulty: High

A member's `byte[]` answer is a byte array and a byte array argument is the `byte[]` Java stores
into (`.kb/java-interop.md`, "Markers" `:octets`, "Handles and views" `:bytes`). A function
called back from Java is still handed a list of signed bytes -- `:octets` reads only what a form
answers (`JavaMarkers.callbacks`) -- and a `proxy` body's answer crosses unconverted. Measured
2026-10-10 against clj 1.12.6, the interpreter and the JVM alike:

| program | oracle | here |
|---|---|---|
| `(.write (proxy [java.io.OutputStream] [] (write [bs off len] (prn (bytes? bs) (vec bs)))) (byte-array [1 2 3]) 0 3)` | `true [1 2 3]` | `false [1 2 3]` |
| `(.read (proxy [java.io.InputStream] [] (read [buf off len] (aset buf off (byte 7)) 1)) b 0 2)`, then `(vec b)` | `[7 0]` | `(SETF AREF): The value (0 0) is not of type ARRAY` |
| `(.get (proxy [java.util.function.Supplier] [] (get [] (byte-array [1 2]))))` | a byte array `[1 2]` | `java:proxy: cannot return (:C%BYTES #(1 2)) as class java.lang.Object` |
| `(.forEach (java.util.List/of (byte-array [5])) (fn [b] (prn (bytes? b))))` | `true` | `false` |

## What decides the design

- In: `:octets` on an implementation form (`java:proxy`, `java:subclass`, `java:reify`) handing
  its functions octet vectors -- the interpreter's handlers (`wrapOctets`: Java's array itself),
  `JvmJavaImplementations`' generated methods and the bridge's `callback` -- and the Clojure
  `proxy` / face lowering wrapping each argument in `%clojure-host-answer`. A compiled program
  hands the body a copy: what the body stores (`InputStream.read(byte[],int,int)`) must reach
  Java's array when the body returns; the parameter types are known there, unlike at a call.
- A fn converted at a call is the program's own closure, which no lowering can wrap without
  losing its identity (Java hands it back as itself); decide whether such a fn gets byte arrays
  at all, measured against the oracle's cases above.
- Out: a `proxy` body's answer goes to Java as `%clojure-host-value` makes it (a byte array the
  `byte[]`, a vector the oracle's `List`), as a face's methods already do.

## Plan

1. `ClojureInteropTest` cases for the table, interpreter and JVM; `JavaImplementationPrograms`
   for `:octets` on the implementation forms.
2. The java: layer's three copies, then the Clojure `proxy` lowering and `ClojureJavaFaces`.
3. `doc/*/clojure/deviations.md`'s Java-member bullet and the guide's `:octets` section.
