# j79. Java front end: what the library slice (j71) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** The library is Java source embedded in every compiled program.

State after j71 (2026-11-17, `.kb/java-frontend.md`, "The library"): the collections,
`Arrays`, `Collections`, `Objects`, `Optional`, `Random` and the older classes are Java
source under `src/main/resources/am/ik/rontolisp/java/library/`, lowered as far as a
program reaches them; `String`, `StringBuilder`, the boxes, `Math`, `System`, `IO` are
`JavaIntrinsics`; a `native` member of the source is a form (`JavaIntrinsics.NATIVES`).

## Inside the first target, not done

- **`java.io`, the byte and character streams** (`InputStream`/`OutputStream`, the
  `ByteArray` pair, `DataInput`/`DataOutput` and their streams, `Reader`/`Writer`,
  `InputStreamReader`/`OutputStreamWriter`, `PrintStream` as a class a program can make,
  `System.in`, `IOException`). Blocked on `byte` (`.todo/j72`): the subset has no `byte`,
  `short` or `float`.
- **Regular expressions**: `String.split` reads a text or one character class
  (`%java-split-pattern`); `matches`, `replaceAll`, `replaceFirst`, `Pattern` are refused.
  The Clojure front end has an engine in Lisp (`.kb/clojure-frontend.md`): check its
  provenance against rule 2 before sharing it.
- `String.chars`/`lines` (streams), `ListIterator`, `Arrays.deepToString` (an array does
  not tell its element type), `Collections.binarySearch`, `Integer.parseUnsignedInt` and any
  other member a program reaches: each is refused by name today, add where one is met.
- **A chain of more than eight keys in a table of 64 or more** is a tree in the JDK and is
  walked in another order there (`doc/*/java/deviations.md`). Measure how a program gets
  there before writing the tree.
- `MatchException` and the run time's own errors as the library's classes: `.todo/j73`.

## Measured costs (2026-11-17), each a lead

- **An iterator costs 16 -> 50 to 65 ns an element on wasm and 6 -> 16 ns on the JVM** against
  the counter over an adjustable vector it replaced; `add` 11 -> 46 ns on wasm. The same shapes
  written by hand in Lisp cost the same, so the cost is the backends' on structure slots and
  the `int` mask, not the library's. Where the wasm time goes is not known.
- **A program adding to a list and reading it is 3.7 -> 25.6 KB of wasm**; one printing a
  list 44 -> 57 KB, one over a `HashMap` 38 -> 58 KB. `%string`, `%equals` and `%hash` take
  any value, so they hold every arm (a double's text, the identity table) whatever the
  program stores. A type flow over what a program puts into its collections would drop them.
- **Any `try` with a `catch` is 175 KB of wasm**, before and after j71 (`C1.java`: one
  class, one `catch`): the host's `format` and condition report are held. With `.todo/j73`.

## After the first target (from j71)

The rest of `Formatter`, streams, `LinkedList`, `LinkedHashMap`, `TreeMap`/`TreeSet`,
`EnumMap`/`EnumSet`, `Scanner`, files, `java.time` (and the older
`Date`/`Calendar`/`TimeZone`), `BigInteger`/`BigDecimal`, `Class` beyond a name,
reflection, weak references, `Thread` and the concurrent classes (`.todo/j76`).

## To decide

- The library's source is read by this front end only. A test leg compiling it with `javac`
  (`--patch-module java.base=..`) would report what the subset accepts and the language does
  not; weigh it against the `native` members and the raw types the source uses.
- A program's own class named `Iterable`, `CharSequence` or `Comparable` is refused once the
  program names a library class whose signatures name the library's (`JavaClasses.register`).
  Count how often before changing the Lisp names of `java.lang` source classes.
