# k72. Java library: the everyday classes j71 left (Scanner, StringJoiner, StringTokenizer, BitSet ..)

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** The library is Java source embedded in every compiled program; a class is
written from the API documentation and a JDK's answers to probes, never from OpenJDK's
source.

Each class below is refused by name today (`doc/*/java/deviations.md`, "What is refused";
`classes.md` lists what is there). `PriorityQueue` came with k60 (`.kb/java-frontend.md`,
"The collections of Collections, a bridge's cast and PriorityQueue": how a class whose
order a JDK leaves open was matched -- an implementation written beside the JDK's in a
probe and compared on seeded runs). Most valuable first, by how often everyday programs
name them:

- **`StringJoiner`** (`Collectors.joining` and `String.join` are there): delimiter, prefix,
  suffix, `setEmptyValue`, `merge`, `length`, `toString`.
- **`StringTokenizer`**: the delimiters, `returnDelims`, `nextToken` with new delimiters,
  `countTokens`, the `Enumeration` members.
- **`Scanner`** with `Readable`: over a `String`, an `InputStream` (`System.in`) and a
  `Readable`; `next`, `nextLine`, `nextInt` .. `nextDouble`, `hasNext*`, `useDelimiter`,
  `findInLine`, `tokens`, the radix; its patterns run on the engine the front ends share
  (`.kb/regex-engine.md`). A number of a locale that is not English waits for `.todo/k71`'s
  locales. Competitive programs read their input through it.
- **`BitSet`**: words of 64 bits, `set` / `clear` / `flip` of ranges, `nextSetBit` ..,
  `cardinality`, `and` / `or` / `xor` / `andNot`, `stream`, `toLongArray`, `valueOf`,
  `toString`, `hashCode` (stated by the specification).
- **The rest of `Formatter`** (a `Formatter` object over an `Appendable`, `%n` .. what
  `String.format` lacks).
- **Files**: `java.io.File`, `FileReader` / `FileWriter`, `java.nio.file` (`Path`, `Files`):
  what each target can reach (WASI preopens on wasm, nothing in the browser).
- **`java.text`** beyond `MessageFormat` and `Normalizer`; **`java.nio` buffers**
  (`ByteBuffer`, which the HTTP client's `BodySubscriber` needs: `.todo/k71`).
- **`Class` beyond what is there**, reflection, weak references.
- **`Thread` and the concurrent classes**: `.todo/k21` (`PriorityBlockingQueue` and
  `DelayQueue` can now stand on `PriorityQueue`).
- **Of `java.math`** (k31): the primes (`isProbablePrime`, `nextProbablePrime`,
  `probablePrime`, the constructors of random bits), refused by name.

What each class costs a program that does not name it must be measured as k47 and k60 did
(the size programs of the k12, k26, k47 and k55 artefacts, `sz.sh` of the k60 ones): a
class of the library is lowered only where a program reaches it, so the cost should be
nothing, and a change to a shared class (`AbstractCollection`, an iterator) is where it is
not.
