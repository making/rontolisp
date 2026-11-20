# k47. Java front end: what the stream members slice (k26) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** The library is Java source embedded in every compiled program.

State after k26 (2026-11-20, `.kb/java-frontend.md`, "java.util.stream", "java.math"):
`mapMulti`, `Stream.gather` with `Gatherer` and `Gatherers`, a stream's own
`spliterator()` and the spliterators of `int`, `long` and `double`, the streams of a
`Random`, `Collectors.groupingByConcurrent` / `toConcurrentMap`, a stream inside
`flatMap` closed where its stream stops asking, and `String.format` of a `BigInteger`
and a `BigDecimal` are in. What follows is what j98, k03, k05, k12 and k26 left, most
valuable first.

**How a member's answer is learned**: a Java program printing a transcript, run under a
JDK and under the four backends and compared -- a grid where the cases can be listed, a
seeded generator where they cannot. The probes and the scripts that run them:
`.todo/artefacts/k12-java-front-end-what-the-time-limits-and-duration-slice-left/`
(`compare.sh`; `refused.py` lists every line of a probe the front end refuses) and
`.todo/artefacts/k26-java-front-end-what-the-streams-slice-left/` (the probes of the
stream members, `sizes/size.sh` and `functions.sh`: what a change costs a program that
does not use it, by function). The JDK leg of `JavaSpecE2eTest` compiles the corpus with
javac: a case's `main` may declare no checked exception, and a line that differs from a
JDK by decision can be in no case.

## Inside the first target, not done

- **`TreeMap` / `TreeSet`** (`LinkedHashMap` / `LinkedHashSet` are there since k27): moved up
  from "after the first target" by the streams -- `groupingBy(f, TreeMap::new, counting())`
  is how a program asks for a sorted map, and `sorted()` over a map's entries is the only
  way without it. The collectors take any map's supplier already. With them: `SortedMap`,
  `NavigableMap`, `SortedSet`, `NavigableSet`, the views (`subMap`, `headMap`,
  `descendingMap` ..), `Collections.unmodifiableSortedMap` and its kin,
  `ConcurrentSkipListMap` / `Set` (`.todo/k21`), what their spliterators report
  (`SORTED`, with a comparator or none).
- **What the streams left** (each runs; `doc/*/java/deviations.md` states them):
  - **A stream's spliterator reports `ORDERED` and its size, not what a JDK keeps track
    of through the stages** (`DISTINCT`, `SORTED`, a source's `NONNULL` / `IMMUTABLE` /
    `CONCURRENT`, the source's own spliterator of a stream with no stage, its estimate
    behind a `filter`), and `sorted()` over a sorted stream takes every element ahead.
    k26 wrote it (one `int` of the pipeline: the source's marks and a mask, cleared by
    every stage that changes the elements; 558 of the probe's 580 lines) and took it
    out: 0.4 to 2.5 KB of wasm in every stream program
    (`.kb/java-frontend.md`, "java.util.stream"; the table in the k26 artefacts). Lead:
    keep the marks only in a program that asks a stream for its spliterator -- a
    member of the library lowered by what the program reaches, as
    `JavaLowering.exactFunction` hands a format its third function only where a
    `BigInteger` is made. A `native` member whose form is `nil` in other programs
    leaves the dead branch to the backends; whether they drop it is not measured.
    A sorted set's stream needs the same question for `sorted()`.
  - **`mapMulti` and a gatherer hold what they give for one element**: a `push` answers
    `true` in the middle of an element where a JDK's answers `false` (a gatherer
    looping until `false` does not return), a `peek` after `mapMulti` sees an
    element's outputs after the function returned. Stages that are pushed into would
    give a JDK's answers and are the library a second time (k12 rejected them for
    `iterator()` and a lazy `flatMap`); the same question as fusing a chain the
    lowering sees whole, below.
  - A stream inside `flatMap` is not closed where a later stage throws (a `try` /
    `finally` in every operation that ends a stream puts the exception machinery,
    47 KB of wasm, in every stream program); `iterator()` and `Stream.concat` take it
    an element at a time, where a JDK takes it whole.
  - `parallel()` only sets a flag. Running a pipeline's source in halves on the common
    pool needs the stages to split, which a closure answering the next element does
    not; a JDK's parallel `sorted().skip(n).count()` runs its stages where a sequential
    one does not, the one line of the count grid that differs. `trySplit` answers
    `null` everywhere (a JDK's `Random.ints()` and `AbstractIntSpliterator` split).
  - A collector's `finisher` is always called and the marks of a collector of the
    program's own class are not read by the collectors wrapping it (`Collected.marks`).
  - `unordered()` of a source in no order is a new stream here and the stream itself
    under a JDK; `String.lines()`' spliterator estimates `Long.MAX_VALUE`, a JDK's the
    characters left plus one.
- **A stage costs a closure call**: 33 ns an element on wasm and 16 to 40 on the JVM
  against 0.4 to 6 under a JDK (`bench/` of the k12 artefacts: an `int[]` summed by a
  loop 16.0 ns on wasm, by `Arrays.stream(a).sum()` 56.0, with one `map` 88.5, three
  156.4). The library's part is one `funcall`, a slot read and a cell a stage; the rest
  is what a `funcall` of a lambda costs on the two compiled backends (j70: 20,000,000
  calls 1.35 s on wasm), and the plain loop's own 16 ns over an `int[]` there. Leads:
  where the wasm time of a closure call goes; stages fused where the lowering sees the
  whole chain.
- **An `int` field changed in place is 221 B of wasm** (k26: `origin.marks &= MASK` in a
  method that also calls one other; a boolean stored instead 97 B, and 108 B more in
  `_dispatch_1`). Where those bytes go was not looked at; it is what made the marks
  above too dear.
- **A library class's member types are read with the class**: `Collection` names
  `Stream`, so a program of one list reads 97 files of the library where it read 46
  (1.89 -> 1.95 s on the interpreter). `JavaClasses.qualified` declares a class's
  members at once; members declared when first looked for would read only what a
  program names. k12 made the one visible effect go away (an intrinsic class counts in
  a dispatcher only where `held`), not the reading. Since k26 `Stream` names `Gatherer`
  and `Random` the three number streams.
- **A program that formats and makes a `BigInteger` pays 8.1 KB for the format of one**
  whatever it formats (184,122 -> 192,233 B): the arguments are an `Object[]`, so the
  lowering cannot see that none is such a number. A call whose arguments are written
  in place could be asked for their static types.
- **`EnumMap` / `EnumSet`** over a class object (k30 made a `Class` a value:
  `.kb/java-frontend.md`, "A class as a value"); reflection stays out.
- **A surrogate with no partner in a string**: its UTF-8 is its three bytes where a JDK
  writes `?` (`%java-utf8-bytes` is the host's `string-to-octets`; a scan of every
  `getBytes` would cost the print streams), and a string CONSTANT holding one is `?` on
  wasm (`"\ud83d"`; made at run time by `new String(char[])` it is kept). Measured by
  j98 (`Halves.java`): the two halves of a pair appended to a `StringBuilder` one `char`
  after the other are a string of length 2 on the interpreter and 1 on the JVM, and on
  wasm no `equals` of the character they spell. The three backends disagree: what a
  `StringBuilder` holds when a low surrogate follows a high one has to be one rule, and
  a concatenation of two strings meeting at the halves of a pair has the same question
  (the JVM's string joins them, the others do not).
- **`Character.digit`, `getNumericValue` and the parses outside ASCII**: they ask the
  host's `digit-char-p`, which is ASCII-only on wasm (`.todo/269`), so
  `Integer.parseInt("٣٤")` is 34 on the interpreter and the JVM and a
  `NumberFormatException` on wasm; `getNumericValue` of a number that is no digit
  (`'Ⅷ'`, `'²'`) is -1 everywhere where a JDK answers 8 and 2. The regex engine's table
  would cost every program that parses a number 29 KB; the decimal digits alone are 71
  runs of ten (`.kb/scheme-frontend.md`, "(scheme char)": `(mod (- code from) 10)`), a
  list small enough to travel, generated from a running JDK and pinned as
  `RegexUnicodeTablesTest` pins the engine's.
- **`Duration.dividedBy`** (a long by a long of nanoseconds needs 128 bits: `multipliedBy`
  shows the digits-of-a-thousand-million way), `CompletableFuture.orTimeout` /
  `completeOnTimeout`, the older `Calendar`. The rest of `java.time` came with k35
  (2026-11-20: the classes read from their text, `ChronoUnit` and `Duration.of` / `get`
  / `truncatedTo` / `between`; `.kb/java-frontend.md`, "java.time"); a region's rules came
  with k42 (`.kb/java-frontend.md`, "java.time: the regions"), the formatter with k43
  ("java.time: the formatter"), and what is left of `java.time` is `.todo/k52`;
  `java.util.Date` and `java.sql` came with k37 (`.kb/java-frontend.md`, "java.util.Date
  and java.sql").
- **`"ß".toUpperCase()` is `ß`**; a JDK answers `SS` (the host's upcase is one character
  to one).
- **`java.util.regex`**: `Matcher.usePattern`, `namedGroups`; `\N{name}` (no name table
  is shipped) and a class under `CANON_EQ`. Two differences from JDK 25 the random
  searches found (`.kb/regex-engine.md`, "Deviations"): a loop that must run twice whose
  first iteration took nothing (`(?:\G|\w){2,}$` over `b`), and which iteration's capture
  a group keeps in one pattern of 6,000.
- **`Readable`**: its one method reads into a `CharBuffer`; it comes with `Scanner`.
- **A locale that is not English** needs the number symbols of its language; `Locale`
  has no constructor.
- **`System.in` on wasm** reads a bulk read to the line's end because `listen` of the
  standard input traps there (wasmtime 49). A `listen` the wasm backends answer (WASI
  `poll_oneoff` on preview 1, a pollable on the component; `WasmIoRuntimeBuilder` has
  the injected `poll_oneoff` of `%sleep-ms`) would make the two variants of
  `%java-input-more` one.
- **Members met and left refused** (each refused by name; add where one is met):
  `String.stripIndent` / `translateEscapes`; `Character.codePointAt` of a
  `CharSequence` / `toTitleCase` / `codePointCount`;
  `Runtime.totalMemory` / `freeMemory` / `maxMemory`; `System.getenv()` of every
  variable; a `StringBuilder` as a `Comparable`; the charsets' `aliases()`, `UTF-32` and
  the other names a JDK has; `Random.nextBytes` / `nextFloat` and the bounded
  `nextInt(origin, bound)` / `nextLong(bound)` / `nextDouble(bound)` (k26: met by the
  probe of the streams; the first two are algorithms the documentation states).
- **What a format does not refuse**: the exceptions a JDK throws for a flag or a
  precision a conversion does not take (`%#h`, `%,x`, `%,e`:
  `FormatFlagsConversionMismatchException`; `%.2d`: `IllegalFormatPrecisionException`;
  k26's `Fmt.java` lists them for the two big numbers), written as if the flag were not
  there; and the class a mismatch names for an object of the program (`d != an object`,
  a JDK names the class).
- **What a compiler reports and this does not**, met by k26: `==` of two parameterized
  types neither of which converts to the other (`BinaryOperator<StringBuilder>` against
  `BinaryOperator<Object>`: javac's `incomparable types`) is lowered.
- **What the HTTP client still lacks** (`.kb/java-frontend.md`, "java.net.http"): a body
  as it arrives (`BodyHandlers.ofInputStream()` reads a body already in; `ofLines`,
  `ofByteArrayConsumer`, a program's own `BodySubscriber` / `BodyPublisher`, which need
  `Flow` and `ByteBuffer`); the methods fetch does not send (`TRACE`, a method in
  lowercase); the `--no-wasi --host-fetch` reactor, which no test runs a Java program
  on; `java.net.URI`'s constructors of several parts, `normalize`, `relativize`,
  `toASCIIString`, the syntax of an address in brackets, and `hashCode`'s number; a
  time limit in a component that serves, on a reactor and in the playground
  (`.todo/148`); a `sendAsync` whose time limit passes has no corpus line (the `async`
  program is the one that names no timeout: a fourth program would carry it). The
  corpus is `java-http-spec.yaml` (`FetchSpecE2eTest#javaHttpClient`): a new member is a
  line of one of its programs, whose expectation is the JDK leg's.

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
- **A program with an HTTP request and no time limit pays 2.3 KB for the time limits**
  (k05: 470,546 -> 472,868 B as a component: the `Wire` class the timed one replaces, a
  slot in the request and one in the client, `timeout()`). What a time limit itself
  brings (17 KB: `Duration`, the timed wire, two exception classes, wasi:http's
  `request-options`) is only in a program that gives a builder one.
- **A `Character` test is 29 KB of wasm** (12,538 -> 42,124 B for a program counting
  letters: the engine's table of every code point with all 24 tests and the category
  is 11 KB of text, the rest its decoder and the arithmetic it pulls in) and 0.68 ->
  0.99 s for 10,500,000 calls. A table of one test is some 5 KB (`.todo/269`'s sketch,
  the Scheme front end's `SchemeCharacters`); `.todo/269` done on the host would make
  `isLetter` and `isDigit` the host's again.
- **A program that throws or catches at all is ~47 KB of wasm** since j88 (60 KB
  before); what is left is the pipeline's print seam, `.todo/k17`'s lead.
- **A `BufferedReader` over `System.in` is 0.17 s over `IO.readln` in 200,000 lines on
  wasm** (1.06 against 0.89 s) now that the line comes whole: the rest is the reader's
  own test and the `indexOf` of a `\r`.
- **Regions and the two flags cost a program with a pattern 6 to 7 KB of wasm**
  (416,483 -> 423,744 B); where they are was not found (no name section to read the
  functions by).
- **`%h` is 401 B in every program that formats** (the arm of `%java-format-one`); the
  program's `hashCode()` function goes with a format only where the control may ask.
- **A stream adds 4 to 30 KB of wasm** (k12, `sizes/`): a list counted through
  `filter(..).count()` 57,341 -> 61,728 B, mapped and collected 133,052 -> 144,046,
  grouped and counted 167,895; `IntStream.range(..).map(..).sum()` in a program with no
  other library class 32,230 -> 61,457. After k26: 61,792, 143,986, 168,271 and 61,493;
  a stage of `flatMap` 1.1 KB more for the stream it closes where no more is asked.

## After the first target (from j71)

The rest of `Formatter`, `PriorityQueue`, `EnumMap` / `EnumSet`, `Scanner`,
`StringTokenizer`, `StringJoiner`, `BitSet`, files, `java.text`,
`java.nio` buffers, `Class` beyond what is above,
reflection, weak references, `Thread` and the concurrent classes (`.todo/k21`).

Of `java.math` (k31, 2026-11-20; `.kb/java-frontend.md`, "java.math"): the primes
(`isProbablePrime`, `nextProbablePrime`, `probablePrime`, the constructors of random
bits), refused by name.
