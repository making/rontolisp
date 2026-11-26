# m56. Java library: `fileKey` from the host's dev and ino

Difficulty: Medium

`BasicFileAttributes.fileKey()` is null on every backend (`doc/*/java/deviations.md`,
"Files"), and so is `fileKey` in the `posix:*` and `unix:*` maps, where a JDK on Linux
answers a key. Measured on JDK 25 (`.kb/java-frontend.md`, "The owner, posix and unix
views"): its `toString` is `(dev=<dev in hex>,ino=<ino in decimal>)`, its hash
`(int) (dev + ino)`, equal where dev and ino are; a link followed has its target's key.

The interpreter and the JVM have both numbers (`%file-unix`); the basic stat does not carry
them, and asking `%file-unix` from `fileKey()` would read the file again after the
attributes were taken. Measure first: whether `%file-stat` can carry dev and ino at no cost
to a program that never asks a key; what WASI gives (Preview 1's `filestat` has dev and ino,
the component adapter zero-fills them, `.kb/read-load-streams.md`); whether `isSameFile`
should then compare keys, as its specification allows. Where a `.wasm` cannot answer, null
stays ("a file key is not available"). A program that reaches no file stays
byte-identical.
