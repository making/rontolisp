# m66. A WASI file key prints a negative inode

Difficulty: Low

On WASI the inode `%file-stat` carries is a 64-bit hash (`.kb/java-frontend.md`, "`%file-stat`
carries the device and the inode"), and `Files.java`'s `Key.toString` writes it with `"ino=" +
inode`: a hash with its top bit set prints negative. Measured 2026-11-26 on `--component`, twelve
fresh files: eight keys `(dev=...,ino=-613239570742741176)` and the like, four positive; Preview
1's hash the same way. So java-spec `a-program-makes-reads-copies-moves-lists-and-is-refused-its-files-through-java-nio-file`'s
`hard link key` line (`key.toString().matches("[(]dev=[0-9a-f]+,ino=[0-9]+[)]")`) answers
`false` on a wasm leg about half the runs (`JavaSpecE2eTest.standalone()[210]` failed on
WASM_COMPONENT once).

What to decide: the inode written unsigned (`Long.toUnsignedString`, or the device's hand-written
digits in base 10) -- a JDK's inode is never negative -- and whether `hashCode` should mix the
halves as it does; then the case on all four backends, several runs on wasm.
