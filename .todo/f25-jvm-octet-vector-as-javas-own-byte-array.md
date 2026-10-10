# f25. JVM: an octet vector Java holds is the program's own array

Difficulty: High

The JVM represents an `(unsigned-byte 8)` vector as `byte[]{8, e0, ...}` -- the width in slot 0
tells it from a quantized matrix, the other `byte[]` (`.kb/packed-integer-vectors.md`, "Trap").
So a `:bytes` view hands Java a copy of the octets, written back when the call returns, and a
`byte[]` coming back at `:octets` is copied in; the interpreter's `LispIntVector` is Java's array
itself both ways (`.kb/java-interop.md`, "Handles and views"). What a call does with the array
while it runs reaches the vector on both backends; an array a Java object keeps past the call
diverges on the JVM only. Measured 2026-10-10 against clj 1.12.6:

| program | oracle | interpreter | JVM |
|---|---|---|---|
| `(let [b (byte-array 4) bb (java.nio.ByteBuffer/wrap b)] (.putInt bb 42) (vec b))` | `[0 0 0 42]` | `[0 0 0 42]` | `[0 0 0 0]` |
| `(let [bb (java.nio.ByteBuffer/allocate 2) a (.array bb)] (.put bb (byte 7)) (vec a))` | `[7 0]` | `[7 0]` | `[0 0]` |

Pinned as the deviation by
`ClojureInteropTest#anArrayJavaKeepsIsTheByteArrayItselfOnlyOnTheInterpreter`.

## What decides the design

- A bare `byte[]` octet vector needs the quantized matrix told apart another way (a carrier of
  its own, or a header the octets lack). `JvmIntArrayRuntimeBuilder.OCTET_TAG` is read in 20
  files (31 references on 2026-10-10: the `_iv*` runtime, SIMD, GPU, the travelling fetch,
  inflate and HTTP runtimes, `_jseq`, the bridge), each indexing the octets from 1.
- The table above is the whole behavioral gain; weigh it against that reach before starting.

## Plan

1. The quantized matrix's own carrier, then every `OCTET_TAG` reader indexing from 0.
2. Drop the copy, the sharing and the write-back on the JVM (`RontoJavaBytesView` offset 0,
   `_jview`'s `:bytes` arm, the `:octets` unmarshal's copy in `_juno` and the bridge).
3. Flip the pin above to the oracle's answer on both backends.
