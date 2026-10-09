# f13. Clojure: a Java byte[] comes back as a byte array, and one goes out as a byte[]

Difficulty: High

A Clojure byte array crosses into a `java:` member as a read-only `List` view of its signed
elements (`%clojure-host-member`), which converts where the member takes a `byte[]`; any
array a member answers comes back as a Lisp list (`JavaInterop`'s array rule, the Common
Lisp tier's). Measured 2026-10-09 against clj 1.12.6, on the interpreter:

| program | oracle | here |
|---|---|---|
| `(let [o (^[] java.io.ByteArrayOutputStream/new)] (.write o 65) [(bytes? (.toByteArray o)) (vec (.toByteArray o))])` | `[true [65]]` | `[false [65]]` |
| `(java.util.Objects/toString (byte-array 1))` | `"[B@3b65e559"` | `"[B"` (the view's printer) |
| `(java.util.Arrays/hashCode (byte-array [-61]))` | `-30` | `-30` |

So a host API answering bytes (`MessageDigest.digest`, `Files/readAllBytes`, a host
`ByteArrayOutputStream`) answers a list, and `clojure.java.shell` turns `.toByteArray`'s list
back into a byte array by hand.

## What decides the design

- The answer: the conversion runs in `java:` before the Clojure program sees the value, so a
  `byte[]` is told from a `List<Byte>` only there -- a marker on the Clojure tier's calls (as
  `:java-false` is) under which a `byte[]` answers a packed `(unsigned-byte 8)` vector the
  call wraps as a byte array, on the interpreter, the compiled JVM sites and the bridge.
- The argument: a parameter of `Object` takes the view, not a `byte[]`; whether a view shape
  that marshals as a `byte[]` wherever its class fits belongs in `java:view`.
- The byte-array family's producers then include every host call that may answer one.

## Plan

1. `ClojureInteropTest` cases for the table above and `MessageDigest`/`Files` answers, the
   JVM-compiled sites too (`JavaInteropPrograms`).
2. The marker through `JavaInterop`, `JvmJavaDirectSites` and `JavaBridgeTemplate`.
3. `doc/*/clojure/reference/interop.md` and `deviations.md`'s Java-member bullet.
