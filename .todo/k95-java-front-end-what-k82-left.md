# k95. Java front end: what k82 left (the streams, the members met, the collections)

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** The library is Java source embedded in every compiled program.

State after k82 (2026-11-21, `.kb/java-frontend.md`, "The copies of `copyOf` and the halves
of a surrogate pair"): `List.copyOf`, `Set.copyOf` and `Map.copyOf` answer a collection of
their own kind as it is, the two halves of a surrogate pair are the one character they
spell wherever text is made on every backend (`.kb/characters-code-points.md`, "The two
halves of a surrogate pair meeting"), and `chars()` answers the UTF-16 units. What follows
is what j98, k03, k05, k12, k26, k47, k55, k60, k71 and k82 left, most valuable first. The
classes j71 left are `.todo/k85` .. `k91`.

**How a member's answer is learned**: a Java program printing a transcript, run under a
JDK and under the four backends and compared -- a grid where the cases can be listed, a
seeded generator where they cannot. The JDK
leg of `JavaSpecE2eTest` compiles the corpus with javac: a case's `main` may declare no
checked exception, and a line that differs from a JDK by decision can be in no case.

## The collections

- **What k60 measured and left** (`deviations.md`): `List.of` of one or two refuses an
  index with the array's words (a check in `ListN.get` was 440 B in every `List.of`
  program); `List.of`'s iterator's `remove()` before `next()` is `IllegalStateException`
  (an iterator class of its own); `Set.of` and `Map.of` answer a null asked for (some 300 B
  in a program asking them) and `Map.of`'s views refuse a change that changes nothing
  (`AbstractMap`'s views were 500 B); `Arrays.asList` refuses a removal with no message, a
  JDK's says `remove` (its own iterator was 0.9 to 1.4 KB);
  `List.of()`, `Set.of()`, `Map.of()` are new objects (k82 made them one object each as
  static fields of `ListN` and two holders: +277 B in a `List.of` program, +812 B in
  `SzAsList`, the global's default keeping a cast in every `toArray` a `List.of` list
  reaches; a form the fold can read past is the lead); an `AbstractList` iterator past its
  end has no cause (a `catch` would be in every program walking such a list); a map's
  views are new at each call, so `m.values().equals(m.values())` is `false` (a field a
  view in every `HashMap` is some 275 B each).
- **A sort and a `TreeMap` ask their comparator about other pairs than a JDK's**
  (`SortCalls.java`; `deviations.md`): `Arrays.sortObjects` is a merge sort of our own; a
  JDK's call sequence is its documented adaptive merge sort's. Matching it is writing that
  sort from its published description and comparing call sequences on seeded runs. What
  the sort a program pays for weighs: `Arrays.merge` is 2,976 B of wasm for some 45 lines
  and `sortObjects` 296 B (`SzSortCmp` of the k82 work, 140,240 B); at that rate a sort with
  runs and galloping is some 15 KB, in every program that sorts objects.
- **A bridge's cast is made in a dispatcher's arms only**: a call going straight to its one
  body and a lambda take a value of another class unchecked (`deviations.md`). A dispatcher
  made for the one body was 2.6 KB in a program putting into an `EnumMap` through `Map`.
- **A `ConcurrentSkipListMap`'s iterator reads the key above the one it gave**, where a
  JDK's follows a removed node's link (`deviations.md`): the tree would keep a removed
  node's successor, and the skip list reaches the tree only through public members.
- **A `compareTo` through `Comparable` costs what the heap costs** (k82 measured k60's
  "2.5 us an operation on wasm" again: `Bench2.java` of the k82 artefacts, the queue alone
  1.41 to 1.47 s on wasm, 1.49 to 1.71 s on the JVM, 0.32 s under a JDK -- 0.7 us an add and
  a poll, the same on both backends). A guest profile (`wasmtime --profile=guest`) puts a
  third of the samples in one small function, a 64-bit subtraction with an overflow test;
  the module has no names, so what calls it is the first thing to find (a candidate:
  `%java-compare`, whose `<` and `>` take any number, under every `Integer.compareTo`).

## The streams (each runs; `doc/*/java/deviations.md` states them)

- **A stream's spliterator reports `ORDERED` and its size, not what a JDK keeps track of
  through the stages** (`DISTINCT`, `SORTED`, a source's `NONNULL` / `IMMUTABLE` /
  `CONCURRENT`, the source's own spliterator of a stream with no stage, its estimate behind
  a `filter`), and `sorted()` over a sorted stream takes every element ahead. k26 wrote it
  (one `int` of the pipeline: the source's marks and a mask, cleared by every stage that
  changes the elements; 558 of the probe's 580 lines) and took it out: 0.4 to 2.5 KB of wasm
  in every stream program (`.kb/java-frontend.md`, "java.util.stream"; the table in the k26
  artefacts). Lead: keep the marks only in a program that asks a stream for its spliterator
  -- a member of the library lowered by what the program reaches, as
  `JavaLowering.exactFunction` hands a format its third function only where a `BigInteger`
  is made. A `native` member whose form is `nil` in other programs leaves the dead branch to
  the backends; whether they drop it is not measured. A sorted set's stream needs the same
  question for `sorted()`.
- **`mapMulti` and a gatherer hold what they give for one element**: a `push` answers `true`
  in the middle of an element where a JDK's answers `false` (a gatherer looping until
  `false` does not return), a `peek` after `mapMulti` sees an element's outputs after the
  function returned. Stages that are pushed into would give a JDK's answers and are the
  library a second time (k12 rejected them for `iterator()` and a lazy `flatMap`); the same
  question as fusing a chain the lowering sees whole, below.
- A stream inside `flatMap` is not closed where a later stage throws (a `try` / `finally` in
  every operation that ends a stream puts the exception machinery, 47 KB of wasm, in every
  stream program); `iterator()` and `Stream.concat` take it an element at a time, where a
  JDK takes it whole.
- `parallel()` only sets a flag. Running a pipeline's source in halves on the common pool
  needs the stages to split, which a closure answering the next element does not; a JDK's
  parallel `sorted().skip(n).count()` runs its stages where a sequential one does not, the
  one line of the count grid that differs. `trySplit` answers `null` everywhere (a JDK's
  `Random.ints()`, `AbstractIntSpliterator` and `PriorityQueue`'s split).
- A collector's `finisher` is always called and the marks of a collector of the program's
  own class are not read by the collectors wrapping it (`Collected.marks`).
- `unordered()` of a source in no order is a new stream here and the stream itself under a
  JDK; `String.lines()`' spliterator estimates `Long.MAX_VALUE`, a JDK's the characters left
  plus one.

## What a program pays

- **A stage costs a closure call**: 33 ns an element on wasm and 16 to 40 on the JVM against
  0.4 to 6 under a JDK (`bench/` of the k12 artefacts: an `int[]` summed by a loop 16.0 ns on
  wasm, by `Arrays.stream(a).sum()` 56.0, with one `map` 88.5, three 156.4). The library's part
  is one `funcall`, a slot read and a cell a stage; the rest is what a `funcall` of a lambda
  costs on the two compiled backends (j70: 20,000,000 calls 1.35 s on wasm), and the plain
  loop's own 16 ns over an `int[]` there. Leads: where the wasm time of a closure call goes;
  stages fused where the lowering sees the whole chain.
- **An `int` field changed in place is 221 B of wasm** (k26: `origin.marks &= MASK` in a method
  that also calls one other; a boolean stored instead 97 B, and 108 B more in `_dispatch_1`).
  Where those bytes go was not looked at; it is what made the marks above too dear.
- **A second iterator class dispatches every walk** (k55): iterating a
  `ConcurrentLinkedQueue` is 63,954 -> 66,243 B of wasm since its iterator removes from the
  queue (`java.util.concurrent.Copied`), most of it the dispatchers of `hasNext` and `next`
  the program's loop takes once two iterator classes are made.
- **A library class's member types are read with the class**: `Collection` names `Stream`,
  so a program of one list reads 97 files of the library where it read 46 (1.89 -> 1.95 s on
  the interpreter). `JavaClasses.qualified` declares a class's members at once; members
  declared when first looked for would read only what a program names. k12 made the one
  visible effect go away (an intrinsic class counts in a dispatcher only where `held`), not
  the reading. Since k26 `Stream` names `Gatherer` and `Random` the three number streams;
  since k55 `List` names `SequencedCollection`.
- **A program that formats and makes a `BigInteger` pays 8.1 KB for the format of one**
  whatever it formats (184,122 -> 192,233 B): the arguments are an `Object[]`, so the
  lowering cannot see that none is such a number. A call whose arguments are written in
  place could be asked for their static types.
- **An iterator costs 16 -> 50 to 65 ns an element on wasm and 6 -> 16 ns on the JVM** against
  the counter over an adjustable vector it replaced; `add` 11 -> 46 ns on wasm. The same shapes
  written by hand in Lisp cost the same, so the cost is the backends' on structure slots and
  the `int` mask, not the library's. Where the wasm time goes is not known.
- **A program adding to a list and reading it is 3.7 -> 25.6 KB of wasm**; one printing a list
  44 -> 57 KB, one over a `HashMap` 38 -> 58 KB. `%string`, `%equals` and `%hash` take any
  value, so they hold every arm (a double's text, the identity table) whatever the program
  stores. A type flow over what a program puts into its collections would drop them.
- **The halves of a surrogate pair joined where text is made cost 611 to 688 B of wasm** in
  every Java program that concatenates (k82: `_string_concat` +261, `_str_cat` +249,
  `_charvec_to_str` +101; a module of another language pays nothing): the seam's test and
  the pair's four bytes in each.
- **A line of Java source in the library is some 200 B of wasm**: what a class every program
  of a kind reaches may hold is small. **A slot of a class every program of a kind makes is
  some 275 B** (j98: one more field in `Throwable`), which is why `getStackTrace()` keeps
  nothing and the mark of UTF-16 is a swapped charset, not a flag of the reader, the writer
  and the `PrintStream`.
- **A program with an HTTP request and no time limit pays 2.3 KB for the time limits** (k05:
  470,546 -> 472,868 B as a component). What a time limit itself brings (17 KB) is only in a
  program that gives a builder one.
- **A `Character` test is 29 KB of wasm** (12,538 -> 42,124 B for a program counting letters)
  and 0.68 -> 0.99 s for 10,500,000 calls. A table of one test is some 5 KB (`.todo/269`'s
  sketch, the Scheme front end's `SchemeCharacters`); `.todo/269` done on the host would make
  `isLetter` and `isDigit` the host's again.
- **A program that parses an int pays 1.5 KB for Unicode's digits** (k71: `SzParse` 58,309 ->
  59,847; YAVI's examples 1.8 KB): a quoted list of 75 zeros (670 B) and its scan (590 B, most
  of it generic arithmetic). Leads: the scan with the integer operations its declared types
  prove; the list only where a program reads text it did not write.
- **A program that throws or catches at all is ~47 KB of wasm** since j88; what is left is the
  pipeline's print seam, `.todo/k17`'s lead.
- **A `BufferedReader` over `System.in` is 0.17 s over `IO.readln` in 200,000 lines on wasm**
  (1.06 against 0.89 s): the rest is the reader's own test and the `indexOf` of a `\r`.
- **Regions and the two flags cost a program with a pattern 6 to 7 KB of wasm** (416,483 ->
  423,744 B); where they are was not found.
- **`%h` is 401 B in every program that formats** (the arm of `%java-format-one`).
- **A stream adds 4 to 30 KB of wasm** (k12, `sizes/`; after k26: 61,792, 143,986, 168,271 and
  61,493 B for the four size programs; a stage of `flatMap` 1.1 KB more).

## The members met and left

- **A surrogate written alone on wasm** (`deviations.md` names it beside the threads of a
  `.wasm`): written to the standard output, a surrogate with no partner is `?` and two
  halves printed one after the other are their character on the interpreter and the JVM
  (the host's encoder holds a high surrogate for the next write), as under a JDK, and three
  bytes each on wasm (`PrintHalves.java`, `Seams.java` of the k82 artefacts;
  `s.chars().forEach(c -> System.out.print((char) c))` meets it since `chars()` answers the
  units). The write of `_write_str` (P1) and of the component's output stream would hold a
  high surrogate's three bytes and turn a lone one into `?` -- a scan of every write, in
  every program that prints; measure that first. And `getBytes` of such a string is its
  three bytes on every backend where a JDK writes `?` (`%java-utf8-bytes` is the host's
  `string-to-octets`).
- **`getNumericValue` of a number that is no digit** (`'Ⅷ'`, `'²'`, `'½'`) is -1 where a JDK
  answers 8, 2 and -2: 1,159 code points in some 520 runs (`NumericValues.java` of the k71
  probes), 4 KB as text, which would be in every program calling `getNumericValue` -- and
  programs call it for ASCII digits. A form of the table a program pays for only where it
  can meet such a character is the lead; the digits' list costs 1.5 KB (k71).
- **`translateEscapes` of a character outside the first plane after a backslash** names its
  code point (`\\u1F600`), where a JDK names its high surrogate: a string's elements are code
  points.
- **`Duration.dividedBy`** of a long and of a `Duration` (a long by a long of nanoseconds
  needs 128 bits: `multipliedBy` shows the digits-of-a-thousand-million way; a `native` over
  the host's integers is the other way). A JDK's answers are `Div.java` / `Div.jdk.txt` of
  the k82 artefacts: toward zero; `Cannot divide by zero`, `Exceeds capacity of Duration: `
  and the nanoseconds; of a `Duration` `Division by zero` / `Division undefined` (0 by 0),
  `Overflow`, a null `divisor`. Met beside it: `CompletableFuture.orTimeout` /
  `completeOnTimeout`, the older `Calendar`. What is left of `java.time` is `.todo/k92` ..
  `k94`.
- **`"ß".toUpperCase()` is `ß`**; a JDK answers `SS` (the host's upcase is one character to
  one).
- **`java.util.regex`**: `Matcher.usePattern`, `namedGroups`; `\N{name}` (no name table is
  shipped) and a class under `CANON_EQ`. Two differences from JDK 25 the random searches found
  (`.kb/regex-engine.md`, "Deviations").
- **A locale that is not English** needs the number symbols of its language; `Locale` has no
  constructor. Once it has such locales, `ResourceBundle.Control.getCandidateLocales` needs
  the candidates of a script and a variant and the Norwegian and Chinese cases its
  documentation states (k75 wrote `Control` for the locales `Locale` has).
- **`System.in` on wasm** reads a bulk read to the line's end because `listen` of the standard
  input traps there (wasmtime 49). A `listen` the wasm backends answer (WASI `poll_oneoff` on
  preview 1, a pollable on the component) would make the two variants of `%java-input-more`
  one.
- **Members met and left refused** (each refused by name; add where one is met):
  `Runtime.totalMemory` / `freeMemory` / `maxMemory`;
  `System.getenv()` of every variable; a `StringBuilder` as a `Comparable` (k82: +385 B in
  every program that dispatches `compareTo` and makes a builder, `SzTreeSet`: its arm and
  `%java-sb-p`, 297 B); the charsets'
  `aliases()`, `UTF-32` and the other names a JDK has; `Random.nextGaussian(mean, stddev)` and
  `nextExponential`, which a JDK draws by an algorithm of its own (k47's `Rand.java`);
  `Collections.EMPTY_LIST`, `EMPTY_SET`, `EMPTY_MAP` (a static field of `Collections` would
  make its collection in every program naming `Collections`).
- **What a format does not refuse**: the exceptions a JDK throws for a flag or a precision a
  conversion of the run time's does not take (`%,x`, `%,e`, `%#s` of no `Formattable`:
  `FormatFlagsConversionMismatchException`; `%.2d`: `IllegalFormatPrecisionException`; k26's
  `Fmt.java`), written as if the flag were not there -- `%h`, `%g`, `%a` and `%t` refuse as a
  JDK does since k85, in `java.util.Conversions`, whose refusals are the run time's raises
  (`.kb/java-frontend.md`, "java.util.Formatter and the conversions a format lacked"); the
  class the run time's mismatch names for an object of the program (`an object`); every
  refusal made before a `Formatter` appends anything (a JDK reads the whole control first;
  the run time refuses a specifier when it reaches it); the relative index `%<s`.
- **What a compiler reports and this does not**, met by k26: `==` of two parameterized types
  neither of which converts to the other (`BinaryOperator<StringBuilder>` against
  `BinaryOperator<Object>`: javac's `incomparable types`) is lowered.
- **What the HTTP client still lacks** (`.kb/java-frontend.md`, "java.net.http"): a body as it
  arrives (`BodyHandlers.ofInputStream()` reads a body already in; `ofLines`,
  `ofByteArrayConsumer`, a program's own `BodySubscriber` / `BodyPublisher`, which need `Flow`
  and `ByteBuffer`); the methods fetch does not send (`TRACE`, a method in lowercase); the
  `--no-wasi --host-fetch` reactor, which no test runs a Java program on; `java.net.URI`'s
  constructors of several parts, `normalize`, `relativize`, `toASCIIString`, the syntax of an
  address in brackets, and `hashCode`'s number; a time limit in a component that serves, on a
  reactor and in the playground (`.todo/148`); a `sendAsync` whose time limit passes has no
  corpus line. The corpus is `java-http-spec.yaml` (`FetchSpecE2eTest#javaHttpClient`).
