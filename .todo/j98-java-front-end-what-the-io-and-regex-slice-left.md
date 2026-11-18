# j98. Java front end: what the io and regex slice (j87) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** The library is Java source embedded in every compiled program.

State after j87 (2026-11-18, `.kb/java-frontend.md`, "The library" and "java.io";
`.kb/regex-engine.md`, "Regions", "hitEnd and requireEnd"): the null streams, the
`CharArray` and `Pushback` pairs, `SequenceInputStream`, `LineNumberReader`, `Appendable`,
`Locale` (the root and the English ones) with the locale forms of `format`, a `Matcher`'s
region, `hitEnd` / `requireEnd`, `appendReplacement`, `toMatchResult`, and the line a
`BufferedReader` on `System.in` takes whole from the host are in. The tree a long
`HashMap` chain becomes was decided: not derived (numbers in the `.kb`).

**How a member's answer is learned**: a Java program printing a transcript, run under a
JDK and under the interpreter and compared -- a grid where the cases can be listed, a
seeded generator where they cannot (j87 used both for `LineNumberReader`, the regions and
the two flags). The JDK leg of `JavaSpecE2eTest` compiles the corpus with javac: a case's
`main` may declare no checked exception.

## Inside the first target, not done

- **UTF-16 (`UTF_16`, `UTF_16BE`, `UTF_16LE`): written in j87, taken out for its size.**
  The codec reached through `Support.encode` / `decode` and the two bridges cost every
  program with a reader 18 KB of wasm (143,022 -> 161,075 B). First make the charsets pay
  only where named: the three as objects of a class of their own, the codec its method
  (a dispatcher holds only the classes a program makes), and `StandardCharsets`' fields
  read through a holder as `System.out` is (`JavaClass.Field.accessor`; a source class's
  field has no such road yet, its initializer runs with the class's). A charset looked
  up by name (`Charset.forName`, `getBytes("UTF-8")`, `new InputStreamReader(in,
  "UTF-8")`) reaches all of them: fold a constant name where the call is lowered. The
  rules measured against JDK 25 are in `.kb/java-frontend.md`, "java.io"; the code was
  not pushed: write it from those rules.
- **What needs `java.util.stream`**: `String.chars` / `lines`, `BufferedReader.lines`,
  `Matcher.results`, `Pattern.splitAsStream`, `Optional.stream`, `List.stream`,
  `Arrays.stream`.
- **`Readable`**: its one method reads into a `CharBuffer`; it comes with `Scanner`.
- **`java.util.regex`**: `Matcher.usePattern`, `namedGroups`; `\N{name}` (no name table
  is shipped) and a class under `CANON_EQ`. Two differences from JDK 25 the random
  searches found (`.kb/regex-engine.md`, "Deviations"): a loop that must run twice whose
  first iteration took nothing (`(?:\G|\w){2,}$` over `b`), and which iteration's capture
  a group keeps in one pattern of 6,000.
- **A surrogate with no partner in a string**: its UTF-8 is its three bytes where a JDK
  writes `?` (`%java-utf8-bytes` is the host's `string-to-octets`; a scan of every
  `getBytes` would cost the print streams), and a string CONSTANT holding one is `?` on
  wasm (`"\ud83d"`; made at run time by `new String(char[])` it is kept).
- **`"ß".toUpperCase()` is `ß`**; a JDK answers `SS` (the host's upcase is one character
  to one).
- **A locale that is not English** needs the number symbols of its language; `Locale`
  has no constructor.
- **`System.in` on wasm** reads a bulk read to the line's end because `listen` of the
  standard input traps there (wasmtime 49). A `listen` the wasm backends answer (WASI
  `poll_oneoff` on preview 1, a pollable on the component; `WasmIoRuntimeBuilder` has
  the injected `poll_oneoff` of `%sleep-ms`) would make the two variants of
  `%java-input-more` one.
- **Members a probe of everyday code met refused** (2026-11-18, each refused by name; add
  where one is met): `StringBuilder.lastIndexOf` / `indexOf(String, int)` / `compareTo` /
  `appendCodePoint` / `repeat`; `String.codePointAt` / `lastIndexOf(char, int)` /
  `regionMatches` / `contentEquals` / `copyValueOf` / `getChars` / `indent` /
  `CASE_INSENSITIVE_ORDER`; `Character.isJavaIdentifierStart` / `getType` / `toChars` /
  `MIN_RADIX` / `isHighSurrogate` and the `int` forms of `isLetter` ..; `Integer` /
  `Long.highestOneBit`, `numberOfLeadingZeros`, `numberOfTrailingZeros`, `reverse`,
  `rotateLeft`, `divideUnsigned`, `compareUnsigned`, `decode`, `SIZE`, `BYTES`,
  `Long.parseUnsignedLong`, `toUnsignedString`, `toOctalString`; `Double.MIN_NORMAL`,
  `toHexString`; `Math.log1p`, `expm1`, `clamp`, `ceilDiv`, `absExact`,
  `incrementExact`, `IEEEremainder`, `nextAfter`, `getExponent`, `copySign`,
  `multiplyHigh`; `StrictMath`; `Objects.requireNonNullElseGet`, `requireNonNull` of a
  supplier; `Collections.rotate`, `indexOfSubList`, `replaceAll`; `List.toArray` of a
  generator; `Arrays.setAll`, `deepEquals`, `hashCode` of a `long[]` and a `double[]`;
  `AbstractMap.SimpleEntry`; `Comparator.thenComparingDouble`; `System.getProperty`,
  `Runtime`; `getStackTrace`; `AssertionError`, `ArrayStoreException`,
  `StackOverflowError`, `OutOfMemoryError`, `SecurityException`,
  `FileNotFoundException`, `InputMismatchException`; the `%h` conversion; a class
  literal as a value; `IntToDoubleFunction`, `ObjIntConsumer` and their kind.
- `MatchException` and the run time's own errors as the library's classes: `.todo/j88`.

## Measured costs, each a lead

- **An iterator costs 16 -> 50 to 65 ns an element on wasm and 6 -> 16 ns on the JVM** against
  the counter over an adjustable vector it replaced; `add` 11 -> 46 ns on wasm. The same shapes
  written by hand in Lisp cost the same, so the cost is the backends' on structure slots and
  the `int` mask, not the library's. Where the wasm time goes is not known.
- **A program adding to a list and reading it is 3.7 -> 25.6 KB of wasm**; one printing a
  list 44 -> 57 KB, one over a `HashMap` 38 -> 58 KB. `%string`, `%equals` and `%hash` take
  any value, so they hold every arm (a double's text, the identity table) whatever the
  program stores. A type flow over what a program puts into its collections would drop them.
- **A line of Java source in the library is some 200 B of wasm** (the UTF-16 codec, 90
  lines: 18 KB; the host's line in `BufferedReader`, 20 lines: 4.6 KB): what a class
  every program of a kind reaches may hold is small.
- **A program that throws or catches at all is ~60 KB of wasm**; what the 60 KB are is
  `.todo/j88`'s.
- **A `BufferedReader` over `System.in` is 0.17 s over `IO.readln` in 200,000 lines on
  wasm** (1.06 against 0.89 s) now that the line comes whole: the rest is the reader's
  own test and the `indexOf` of a `\r`.
- **Regions and the two flags cost a program with a pattern 6 to 7 KB of wasm**
  (416,483 -> 423,744 B); where they are was not found (no name section to read the
  functions by).

## After the first target (from j71)

The rest of `Formatter`, streams, `LinkedList`, `LinkedHashMap` / `LinkedHashSet`,
`TreeMap` / `TreeSet`, `PriorityQueue`, `EnumMap` / `EnumSet`, `Scanner`,
`StringTokenizer`, `StringJoiner`, `BitSet`, files, `java.time` (and the older `Date` /
`Calendar` / `TimeZone`), `java.text`, `BigInteger` / `BigDecimal`, `java.nio` buffers,
`Class` beyond a name, reflection, weak references, `Thread` and the concurrent classes
(`.todo/j89`).
