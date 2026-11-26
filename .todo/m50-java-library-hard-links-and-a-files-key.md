# m50. Java library: hard links (Files.createLink) and a file's key

Difficulty: Medium

Split from m19. **Read `.kb/java-frontend-provenance.md` before writing anything.** What is
there: `.kb/java-frontend.md`, "Files", "Links made and read, and the basic view".

`Files.createLink` is refused by name. Making one is one more primitive as `%make-link` is:
`Files.createLink` on the interpreter and the JVM, `path_link` on Preview 1, the adapter's
over `wasi:filesystem`'s `link-at` (one member of `core.wat`).

- A hard link is the same file under two names, so `Files.isSameFile` of the two must be
  true; today it compares real paths, and `fileKey()` is null on every target. Measure what
  WASI's `stat` gives to tell two names of one file apart (Preview 1 has `dev`/`ino`; the
  component adapter writes both as 0) before deciding how `isSameFile` and `fileKey` answer.
- Measure each answer against a JDK first (exists, under a read-only directory, a link to a
  directory, across preopens); a program that reaches no file stays byte-identical.
