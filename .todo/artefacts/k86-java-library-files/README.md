# k86: what was asked of a JDK 25 about files (2026-11-21)

Results are in `.kb/java-frontend.md`, "Files", and the provenance in
`.kb/java-frontend-provenance.md`.

- `four.sh JAR File.(java|lisp) [abs]` -- the program under the JDK (a `.java`), the
  interpreter, the JVM, wasm and the component, each leg in a directory of its own holding
  `locked.txt` (mode 000) and `ro/` (mode 555); a wasm leg preopens its directory as `.`, or
  with `abs` under its absolute name. Each leg's lines against the first leg's.
- `probes/` -- each with the JDK's lines in `*.jdk.txt` (run in such a directory); on the four
  backends all are a JDK's but where said.
  - `host.lisp`, `eof.lisp`, `seek.lisp`, `fp.lisp`, `probe.lisp`: what the Common Lisp layer
    answers on each backend before anything was written -- the refusals of `open`, a read at
    the end and a file that grew, a seek and a write past the end, `file-position` after reads
    at the end (the interpreter counted them), `%probe-file` of a file no one may read (nil on
    wasm), `%list-directory`'s order (reversed on wasm).
  - `FilePaths.java`: a `File`'s path arithmetic over 30 texts and 77 parent and child pairs,
    equality, order, the canonical paths (all a JDK's under `abs`).
  - `NioPaths.java`: a `Path`'s names, parts, starts and ends, resolutions, relativizations,
    order (differs: a JDK's helpful `NullPointerException` text). `toUri` and `getFileSystem`
    were taken out, as was `File.toURI` from `FilePaths.java`: not in the subset.
  - `FileOps.java`: `File`'s questions and changes, the streams, readers and writers, the
    print streams, formatters and scanners of a file, `RandomAccessFile`, the words of each
    refusal (differs: the helpful `NullPointerException`).
  - `NioFiles.java` (a JDK's only), `NioFiles2.java` (it without `find`,
    `newDirectoryStream`, `getLastModifiedTime` and `isExecutable`): `Files`' members, options,
    strict codecs and refusals (differs: the absolute path a refusal of `createDirectories`
    names, a JDK's `String.length()` of a character past the BMP).
  - `Malformed.java` (4,238 lines), `Malformed2.java`, `Malformed3.java`: how many bytes
    `Files.readString` and `Files.readAllLines` count of bytes that are no text, every lead
    byte followed by each kind of byte, in UTF-8, UTF-16 and US-ASCII; what the strict
    encoders refuse (differs: the marks column of `Malformed.java`, a string's length past
    the BMP).
  - `Odds.java` / `OddsB.java`, `Odds2.java`, `Odds3.java` / `Odds3B.java`: a NUL in a path, a
    scanner of a file with bytes that are no text at 17 offsets (differs past 1024
    characters: the reads' size), the encoders of each charset over each kind of character,
    the empty path through `Files`, the texts of `FileSystemException` and
    `InvalidPathException`. The `B` files leave out what is not in the subset.
  - `FilesOfJavaIo.java`, `FilesOfNio.java`: the two cases of `java-spec.yaml`.
- `sizes/` -- the programs and `sizes.txt`, the wasm bytes by `sz.sh` of
  `.todo/artefacts/k71-java-front-end-what-k60-left/` of the base (`aac4d12d9`) and this.
