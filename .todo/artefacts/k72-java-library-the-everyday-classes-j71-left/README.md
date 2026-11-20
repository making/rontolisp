# k72: what was asked of a JDK 25 (2026-11-21)

Results are in `.kb/java-frontend.md`, "A joiner, a tokenizer, a bit set, a scanner and a
char buffer", and the provenance in `.kb/java-frontend-provenance.md`.

- `four.sh JAR File.java [stdin]` -- the program under the JDK (English, the United States),
  the interpreter, the JVM, wasm and the component, each leg's lines against the JDK's.
- `probes/` -- each with the JDK's lines in `*.jdk.txt`; on the four backends all are a JDK's
  but where said.
  - `Joiner.java`, `Joiner2.java`: `StringJoiner`'s members, its merges and which null is
    refused first.
  - `Tokens.java`, `Tokens2.java`: `StringTokenizer` over ten texts by five sets of
    delimiters both ways, a set changed, a null set.
  - `BitSize.java`, `BitSize2.java`: `BitSet.size()` after each kind of change, and when a
    clone keeps a size given. `Bits.java`: the members, the scans from every index, the
    refusals (differs: the spliterator's characteristics and split, the words of a
    `NullPointerException`). `BitSplit.java`, `BitSplit2.java`: a JDK's spliterator of
    `stream()`, which the subset does not follow.
  - `Scan1.java` .. `Scan9.java`: `Scanner` over a `String` -- tokens and lines over every kind
    of whitespace and separator, delimiters, numbers of each kind and radix with the refusals'
    words, finds, skips, horizons, matches, its text, what a `hasNext` keeps and what drops it
    (`Scan5.java`, `Scan6.java`: the delimiter changed between), where a refused token leaves
    the position, a `Readable`, a stream in a charset, a failing source, the streams. Differ:
    `Scan2.java` the groups of a number's match, `Scan5.java` the frames of the uncaught
    exception it ends with, `Scan9.java` the streams' characteristics and the buffer a
    `Readable` is handed.
  - `ScanReads.java`: a `Readable` handing scripted pieces, logging each read -- when each
    operation reads more (the javac warnings it prints are not the subset's run).
  - `StdinScan.java` with `StdinScan.in.txt`: a `Scanner` over `System.in`, then closed.
  - `Chars.java`: `CharBuffer`'s members and refusals.
  - `Prims.java`: the parses a `Scanner` hands its tokens to, `Character.isWhitespace` and
    `isDigit` over every code point. `HexGrid.java`: 6,000 seeded hexadecimal floats and 33 at
    the formats' ends through `Double.parseDouble` and `Float.parseFloat`; `DecGrid.java`:
    8,000 seeded decimal texts with leading zeros (the reader before k72 differed).
- `cases/` -- the corpus lines as programs of their own (`Older.java`: `joiners`, `tokens`,
  `bits`; `Scanning.java`).
- `bench/` -- the size and time programs; `ints.py` writes `ints.txt`, a million ints for
  `SumScan.java` and `SumTok.java`. Sizes with `sz.sh` of
  `.todo/artefacts/k71-java-front-end-what-k60-left/`.
