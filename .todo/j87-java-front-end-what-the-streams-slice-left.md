# j87. Java front end: what the streams slice (j79) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** The library is Java source embedded in every compiled program.

State after j79 (2026-11-17, `.kb/java-frontend.md`, "java.io" and "The library"): `java.io`'s
byte and character streams, `PrintStream` as a class, `System.in`, the three charsets,
`String.getBytes` / `new String(byte[])`, `ListIterator`, `Collections.binarySearch`,
`Integer.parseUnsignedInt`, `Double.longBitsToDouble` / `Float.intBitsToFloat` are in; javac
compiles the library (`JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles`).

## Inside the first target, not done

- **Regular expressions**: in since j90 (`.kb/regex-engine.md`, `.kb/java-frontend.md`, "The
  library"). Left of `java.util.regex`: `Matcher` regions, `hitEnd`, `appendReplacement` /
  `appendTail`, `results`, `toMatchResult`, `Pattern.asPredicate` / `splitAsStream`, a
  `\N{name}` (no name table is shipped) and a class under `CANON_EQ`.
- **The tree a long `HashMap` chain becomes.** Measured in j79 (`.kb/java-frontend.md`, "The
  library"): 64 `Double` keys of few digits and a 16 x 16 grid under a weak `hashCode` get
  there; strings and ints do not. The order inside the JDK's tree is no part of the API; it
  can only be derived from outputs (which node heads the chain after each put, remove and
  resize, for comparable and for incomparable keys). Decide whether that derivation is worth
  an unspecified order before starting it.
- `String.chars` / `lines`, `BufferedReader.lines` (streams), `Reader.nullReader`,
  `InputStream.nullInputStream`, `CharArrayReader` / `CharArrayWriter`, `PushbackReader`,
  `SequenceInputStream`, `Appendable` / `Readable`, the UTF-16 charsets, `Locale` forms of
  `printf`, and any other member a program reaches: each is refused by name, add where one
  is met.
- **`System.in` on wasm** reads a bulk read to the line's end because `listen` of the
  standard input traps there (wasmtime 49). A `listen` the wasm backends answer (WASI
  `poll_oneoff` on preview 1, a pollable on the component) would make the two variants of
  `%java-input-more` one.
- `MatchException` and the run time's own errors as the library's classes: `.todo/j88`.

## Measured costs (2026-11-17), each a lead

- **An iterator costs 16 -> 50 to 65 ns an element on wasm and 6 -> 16 ns on the JVM** against
  the counter over an adjustable vector it replaced; `add` 11 -> 46 ns on wasm. The same shapes
  written by hand in Lisp cost the same, so the cost is the backends' on structure slots and
  the `int` mask, not the library's. Where the wasm time goes is not known.
- **A program adding to a list and reading it is 3.7 -> 25.6 KB of wasm**; one printing a
  list 44 -> 57 KB, one over a `HashMap` 38 -> 58 KB. `%string`, `%equals` and `%hash` take
  any value, so they hold every arm (a double's text, the identity table) whatever the
  program stores. A type flow over what a program puts into its collections would drop them.
- **A program that throws or catches at all is ~60 KB of wasm** (a loop calling a method that
  throws: 60,901 B; with a `try`/`catch` around the call 64,019 B, which was 183,852 B until
  j82 made a `catch` signal nothing again). Since j79 a program that makes a `PrintStream` or
  reads through `InputStream.read(byte[], int, int)` pays it through the library's own
  `catch` of an `IOException`. What the 60 KB are is `.todo/j88`'s.
- **A `BufferedReader` over `System.in` costs 1.3 us a line on wasm over `IO.readln`**
  (200,000 lines: 1.04 against 0.78 s), 1.1 us on the JVM: every character goes through
  `StandardInput.units`, the reader's buffer and `new String(char[], ..)`. A `readLine` that
  asks the host for a line when the reader stands directly over the standard input would
  take it back.

## After the first target (from j71)

The rest of `Formatter`, streams, `LinkedList`, `LinkedHashMap`, `TreeMap`/`TreeSet`,
`EnumMap`/`EnumSet`, `Scanner`, files, `java.time` (and the older
`Date`/`Calendar`/`TimeZone`), `BigInteger`/`BigDecimal`, `Class` beyond a name,
reflection, weak references, `Thread` and the concurrent classes (`.todo/j89`).

## To decide

- A program's own class named `Iterable`, `CharSequence` or `Comparable` is refused once the
  program names a library class whose signatures name the library's (`JavaClasses.register`).
  Count how often before changing the Lisp names of `java.lang` source classes.
