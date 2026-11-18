# k05. Java front end: what the everyday members and UTF-16 slice (j98) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** The library is Java source embedded in every compiled program.

State after j98 (2026-11-18, `.kb/java-frontend.md`, "The library", "java.io",
"Exceptions", and the `Object`'s methods decision): the members a probe of everyday code
met refused are in (`String`, `StringBuilder`, the boxes over their bits, `Math` /
`StrictMath`, `Character` over the regex engine's Unicode tables on every backend,
`Objects`, `Collections`, `Arrays`, `AbstractMap.SimpleEntry`, `Comparator`,
`System.getProperty`, `Runtime`, `getStackTrace`, seven errors and exceptions, `%h`, nine
functional interfaces); a class is compared with a class (`getClass() != o.getClass()`,
a class literal) with no `Class` object; UTF-16, UTF-16BE and UTF-16LE are in, their
codec only in a program that names one, and a charset named by a constant is folded
where the call is lowered.

**How a member's answer is learned**: a Java program printing a transcript, run under a
JDK and under the four backends and compared -- a grid where the cases can be listed, a
seeded generator where they cannot. j98's probes and the script that runs them:
`.todo/artefacts/j98-java-front-end-what-the-io-and-regex-slice-left/`. The JDK leg of
`JavaSpecE2eTest` compiles the corpus with javac: a case's `main` may declare no checked
exception.

## Inside the first target, not done

- **What needs `java.util.stream`**: `String.chars` / `lines`, `BufferedReader.lines`,
  `Matcher.results`, `Pattern.splitAsStream`, `Optional.stream`, `List.stream`,
  `Arrays.stream`.
- **`Readable`**: its one method reads into a `CharBuffer`; it comes with `Scanner`.
- **A `Class` as a value**: a field, an argument, `isInstance`, `cast`, `getSuperclass`,
  `Enum.valueOf(Color.class, ..)`, `EnumMap`, a class literal of a primitive or an array
  type. j98 reads a class only where it is compared with a class and before `getName()`
  / `getSimpleName()`; what a `Class` is as a value is not decided (one object a class,
  found by name, so `==` holds; `%string` / `%equals` / `%hash` would each gain an arm).
- **`java.util.regex`**: `Matcher.usePattern`, `namedGroups`; `\N{name}` (no name table
  is shipped) and a class under `CANON_EQ`. Two differences from JDK 25 the random
  searches found (`.kb/regex-engine.md`, "Deviations"): a loop that must run twice whose
  first iteration took nothing (`(?:\G|\w){2,}$` over `b`), and which iteration's capture
  a group keeps in one pattern of 6,000.
- **A surrogate with no partner in a string**: its UTF-8 is its three bytes where a JDK
  writes `?` (`%java-utf8-bytes` is the host's `string-to-octets`; a scan of every
  `getBytes` would cost the print streams), and a string CONSTANT holding one is `?` on
  wasm (`"\ud83d"`; made at run time by `new String(char[])` it is kept). Measured by
  j98 (`Halves.java`): the two halves of a pair appended to a `StringBuilder` one `char`
  after the other are a string of length 2 on the interpreter and 1 on the JVM, and on
  wasm no `equals` of the character they spell. The three backends disagree: what a
  `StringBuilder` holds when a low surrogate follows a high one has to be one rule.
- **`Character.digit`, `getNumericValue` and the parses outside ASCII**: they ask the
  host's `digit-char-p`, which is ASCII-only on wasm (`.todo/269`), so
  `Integer.parseInt("٣٤")` is 34 on the interpreter and the JVM and a
  `NumberFormatException` on wasm; `getNumericValue` of a number that is no digit
  (`'Ⅷ'`, `'²'`) is -1 everywhere where a JDK answers 8 and 2. The regex engine's table
  would cost every program that parses a number 29 KB; the decimal digits alone are 71
  runs of ten (`.kb/scheme-frontend.md`, "(scheme char)": `(mod (- code from) 10)`), a
  list small enough to travel, generated from a running JDK and pinned as
  `RegexUnicodeTablesTest` pins the engine's.
- **`"ß".toUpperCase()` is `ß`**; a JDK answers `SS` (the host's upcase is one character
  to one).
- **A locale that is not English** needs the number symbols of its language; `Locale`
  has no constructor.
- **`System.in` on wasm** reads a bulk read to the line's end because `listen` of the
  standard input traps there (wasmtime 49). A `listen` the wasm backends answer (WASI
  `poll_oneoff` on preview 1, a pollable on the component; `WasmIoRuntimeBuilder` has
  the injected `poll_oneoff` of `%sleep-ms`) would make the two variants of
  `%java-input-more` one.
- **Members j98 met and left refused** (each refused by name; add where one is met):
  `String.codePointCount` / `offsetByCodePoints` / `stripIndent` / `translateEscapes` /
  `indexOf` of a range; `Character.toChars(int, char[], int)` / `codePointAt` /
  `reverseBytes` / `toTitleCase`; `Math.floorDivExact` / `ceilDivExact` / `divideExact`;
  `Runtime.totalMemory` / `freeMemory` / `maxMemory`; `System.getenv`; the format
  exceptions a JDK throws for a flag a conversion does not take (`%#h`); a
  `StringBuilder` as a `Comparable`; the charsets' `aliases()`, `UTF-32` and the other
  names a JDK has.
- `MatchException` and the run time's own errors as the library's classes: probed and
  closed by j88 (`.kb/java-frontend.md`, "Exceptions": at every place a program can see
  one).

## Measured costs, each a lead

- **An iterator costs 16 -> 50 to 65 ns an element on wasm and 6 -> 16 ns on the JVM** against
  the counter over an adjustable vector it replaced; `add` 11 -> 46 ns on wasm. The same shapes
  written by hand in Lisp cost the same, so the cost is the backends' on structure slots and
  the `int` mask, not the library's. Where the wasm time goes is not known.
- **A program adding to a list and reading it is 3.7 -> 25.6 KB of wasm**; one printing a
  list 44 -> 57 KB, one over a `HashMap` 38 -> 58 KB. `%string`, `%equals` and `%hash` take
  any value, so they hold every arm (a double's text, the identity table) whatever the
  program stores. A type flow over what a program puts into its collections would drop them.
- **A line of Java source in the library is some 200 B of wasm**: what a class every
  program of a kind reaches may hold is small. **A slot of a class every program of a
  kind makes is some 275 B** (j98: one more field in `Throwable`), which is why
  `getStackTrace()` keeps nothing and the mark of UTF-16 is a swapped charset, not a
  flag of the reader, the writer and the `PrintStream`.
- **A `Character` test is 29 KB of wasm** (12,538 -> 42,124 B for a program counting
  letters: the engine's table of every code point with all 24 tests and the category
  is 11 KB of text, the rest its decoder and the arithmetic it pulls in) and 0.68 ->
  0.99 s for 10,500,000 calls. A table of one test is some 5 KB (`.todo/269`'s sketch,
  the Scheme front end's `SchemeCharacters`); `.todo/269` done on the host would make
  `isLetter` and `isDigit` the host's again.
- **A program that throws or catches at all is ~47 KB of wasm** since j88 (60 KB
  before); what is left is the pipeline's print seam, `.todo/k06`'s lead.
- **A `BufferedReader` over `System.in` is 0.17 s over `IO.readln` in 200,000 lines on
  wasm** (1.06 against 0.89 s) now that the line comes whole: the rest is the reader's
  own test and the `indexOf` of a `\r`.
- **Regions and the two flags cost a program with a pattern 6 to 7 KB of wasm**
  (416,483 -> 423,744 B); where they are was not found (no name section to read the
  functions by).
- **`%h` is 401 B in every program that formats** (the arm of `%java-format-one`); the
  program's `hashCode()` function goes with a format only where the control may ask.

## After the first target (from j71)

The rest of `Formatter`, streams, `LinkedList`, `LinkedHashMap` / `LinkedHashSet`,
`TreeMap` / `TreeSet`, `PriorityQueue`, `EnumMap` / `EnumSet`, `Scanner`,
`StringTokenizer`, `StringJoiner`, `BitSet`, files, `java.time` (and the older `Date` /
`Calendar` / `TimeZone`), `java.text`, `BigInteger` / `BigDecimal`, `java.nio` buffers,
`Class` beyond what is above, reflection, weak references, `Thread` and the concurrent
classes (`.todo/k01`).
