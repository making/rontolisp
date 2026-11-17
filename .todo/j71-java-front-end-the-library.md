# j71. Java front end: `java.lang` and `java.util`, as far as programs reach

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".
This item is where the rule bites: every class of the library is embedded in the
user's program. State a ported file's origin in its header.

**First target (j68): the section "Inside the first target" below.**

After j68 and j70. The spike (`.todo/artefacts/j68-java-front-end-spike/`) maps a dozen
methods of `String`, `StringBuilder`, `ArrayList`, `HashMap`, `Math` and `Integer` by
name, and ships the `Throwable` family as Java source lowered with the program. No JDK is
there on wasm, so what a program imports has to be ours on every backend.

- **Decide the split and write it down**: a class whose representation is a Lisp value
  (`String`, a boxed number, an array) is intrinsics in `eval/java.lisp`; everything else
  is Java source in the jar, lowered behind the seam and pruned when unreachable, over a
  small set of intrinsics. Measure one collection both ways (`ArrayList` as an adjustable
  vector against `ArrayList` in Java over `Object[]`) before choosing for the rest.
## Inside the first target (j68)

What Java 25's own constructs name, and a core the size of a small-device profile in
today's classes. Each class with the members programs reach, not its whole Javadoc.

- **The language's own**: `Object` (`equals`, `hashCode`, `toString` as `Name@hash`,
  `getClass().getName()`/`getSimpleName()` and nothing else of `Class`), `String`,
  `CharSequence`, `StringBuilder`, the eight wrappers and `Number`, `Enum`, `Record`,
  `Iterable`/`Iterator` (the enhanced `for`), `Comparable`, `AutoCloseable`, `Runnable`,
  `java.util.function` (lambdas and method references), the `Throwable` family the
  language and the run time raise (`MatchException` included), `Math`, `System.out`/
  `err`/`currentTimeMillis`/`nanoTime`/`exit`, `Objects`, `IO` (`println`, `print`,
  `readln`).
- **`String`**: the everyday surface (`length`, `charAt`, `substring`, `indexOf`,
  `equals`, `compareTo`, `contains`, `startsWith`, `split`, `join`, `trim`/`strip`,
  `toUpperCase`, `valueOf`, `chars`, `isEmpty`/`isBlank`, `repeat`, text-block
  indentation), and `String.format`/`formatted`/`printf` over the conversions programs
  write (`%d %s %f %x %c %b %n %%` with width, precision and flags); the rest of
  `Formatter` is refused by the conversion's name. The string model: the host CHARACTER is a code point
  (`.kb/characters-code-points.md`), Java's `char` a UTF-16 unit; state the deviation
  outside the BMP or pay for it, with a measurement. `Double.toString`'s exact text.
- **Collections**: `List`/`ArrayList`, `Map`/`HashMap`, `Set`/`HashSet`,
  `Deque`/`ArrayDeque`, `Collection`, `List.of`/`Map.of`/`Set.of`, `Arrays`, `Collections`,
  `Comparator`, `Optional`; `HashMap`/`HashSet` keyed by `equals`/`hashCode` of a user
  class (the spike's `equal` table knows strings and numbers only); iteration order as the
  JDK's where a program can see it. The older `Vector`, `Stack`, `Hashtable`,
  `Enumeration`, `StringBuffer` over the same code where they cost a few lines.
- **`java.io`, the byte and character streams only**: `InputStream`/`OutputStream`,
  the `ByteArray` pair, `DataInput`/`DataOutput` and their streams, `Reader`/`Writer`,
  `InputStreamReader`/`OutputStreamWriter`, `PrintStream`, `System.in`, `IOException`.
- `Random` with the JDK's sequence.

## After the first target

The rest of `Formatter`, streams, `LinkedList`, `LinkedHashMap`,
`TreeMap`/`TreeSet`, `EnumMap`/`EnumSet`, `Scanner`, files, `java.time` (and the older
`Date`/`Calendar`/`TimeZone`), `BigInteger`/`BigDecimal`, `Class` beyond a name,
reflection, weak references, `Thread` and the concurrent classes (j76).

## To decide

- Open question to answer with a measurement, not to assume: on the interpreter and the
  JVM a class we do not have could fall through to the host's (`.kb/java-interop.md`);
  the same program would then not compile to wasm. Decide whether that is offered at all.

A class or member we do not have is refused at the `import` or the call, by name.
