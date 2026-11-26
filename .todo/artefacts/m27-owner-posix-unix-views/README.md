# m27: the owner, posix and unix views

What `.kb/java-frontend.md`, "The owner, posix and unix views", was taken with (2026-11-26,
JDK 25.0.5.1, wasmtime 51.0.0, Linux, umask 022). Nothing of a JDK is here; the user's name in
the outputs is `<user>`.

- `probes/`: programs written here and what a JDK printed for them (`*.jdk.txt`); for the four
  the subset compiles, the interpreter's and the JVM's lines too (`*.interp.txt`,
  `*.jvm.txt`), which differ from the JDK's only in the helpful `NullPointerException` and
  `ClassCastException` texts, `fileKey` and a raw set holding a `String` (`Order`'s
  `perm set wrong element`). `Eperm.java` runs as a user who does not own `/etc/hostname`. `Order.java`'s `proc ns key` (a large inode's `fileKey` hash) is
  the JDK's alone. `Zip.java` is a JDK's file system without the views; `MapOrder.java`
  compares 3000 name sets of a JDK's maps with a `HashMap` filled in one order.
- `tools/probes.sh JAR OUT Probe..`: a probe on the JDK, the interpreter and the JVM, diffed.
  `tools/four.py WORKTREE CASE OUT [legs]` (`python3 -I`): a `standalone:` case of
  `java-spec.yaml` staged with its files, links and modes and run on the JDK and the four
  backends (the wasm legs preopen the directory as `.`, so their path lines differ from the
  test's).
- `sizes/`: `measure.sh JAR OUT` compiles `progs/` and `examples/java/shop` (copied beside
  it as `shop/`) to wasm, a component and a class; `before.txt` is origin/develop's,
  `after.txt` this item's.
