# m28. JVM: a Java array read and store box in integer arithmetic

Difficulty: High

Measured in l73 (`.kb/jvm-int-fusion.md`, "Exact locals", "Packed aref leaves", "Unfinished"):
with the declared locals raw, what still boxes in a Java digest is its arrays. The front end
asserts every array read in arithmetic, `(the (signed-byte 64) (aref w t))`, and the JVM's
fusion takes that as an opaque leaf: a boxed read through `_ivAref1`, and the order plan then
makes the tree's other reads opaque too. Classifying it as the aref leaf the bare read is was
measured and not made: SHA-512 alone on C2 398-639 -> 203-243 ms and its first run 826-863 ->
389-414 ms, but Graal's steady state 113-125 -> 152-177 ms -- the compression's fused method grew
592 -> 1,323 B with three aref dispatches (the `byte[]`, the packed vector, the general header,
each time) and is no longer inlined; classes +0.3-5% (k64's `Zone` 252,823 -> 256,028 B). And
`%aset` takes a boxed index and value (`_ivAset1`): SHA-512's block keeps 30 `Long.valueOf`,
every one a store's.

The JVM reads no array declaration (`.kb/declarations-type-checks.md`, "Declaration-driven array
emission" is wasm's), while the front end declares every array parameter and local `(or null
(simple-array E (*)))`. A declared array's one representation would make a read a cast and a
load inside the fused method, and a store a raw one. Measure SHA-512 on both JITs and the
`java-spec.yaml` corpus's JVM class sizes against it.
