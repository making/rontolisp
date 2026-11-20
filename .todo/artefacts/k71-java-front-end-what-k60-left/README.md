# k71: what was measured (2026-11-21)

Results are in `.kb/java-frontend.md`, "A raw argument, `Stream.toList()`, the digits and the
members met".

- `sz.sh A.jar B.jar File.java...` -- the wasm bytes of each program under two exec jars
  (`SZ_OUT` names the directory the modules go to). The size programs are those of
  `.todo/artefacts/k12-..`, `k26-..`, `k47-..`, `k55-..`, `k60-..` and `k62-..` (`sizes/`)
  and the four of `sizes/` here. `four.sh` and `fn.sh` of
  `.todo/artefacts/k60-java-front-end-what-k55-left/` ran each probe on the four backends and
  named the functions whose bytes moved.
- `extract.py YAML CASE DIR` writes a case of `java-spec.yaml` (the corpus's imports and its
  source) and its expected lines into `DIR`, to run under a JDK; `update.py YAML CASE FILE`
  puts a JDK's lines in as the case's expected ones. A case naming a class of another case
  (`the-boxes-parse-print-and-compare-and-math-is-strict`) does not compile alone: its lines
  were written by hand.
- `probes/` -- each asked of JDK 25 and, but where said, run on the four backends.
  - `Types.java`, `Types2.java` (javac alone, which refuses them on purpose): each error
    names the type a call given a raw argument has -- the erasure of what it returns, a type
    parameter nothing found at its first bound that is not `Object` (`Comparable` for
    `Collections.max`), whatever the target. `Thrown.java`: a parameter of a `throws` clause
    is no checked exception there.
  - `Warnings.java`, `Diamonds.java`, `DiamondArgs.java`, `Bounded.java`, `RawClass.java`,
    `ExtendsObject.java`: javac's unchecked warnings of such calls -- the class named raw,
    the parameter as declared, no conversion where the parameter with its type parameters at
    their bounds is `? extends Object`, which is `?` everywhere (a cast, an array, a variable
    arity parameter). All a JDK's lines on the interpreter but `Warnings.java`'s last, the
    `ClassCastException` a polluted heap gets at a read (`deviations.md`).
  - `ToList.java`, `Windows.java`: the list of `Stream.toList()` and of a gatherer's window
    with a null in it. One line differs, `CopyOf.java`'s: `List.copyOf` of a list of
    `List.of` is that list under a JDK and a copy here (`.todo/k82`).
  - `Digits.java` (57 lines): `digit` of three radixes, `getNumericValue`, the parses of a
    text of digits outside ASCII. 41 lines differed on wasm and 17 elsewhere; now 13 on
    every backend, `getNumericValue` of numbers that are no digit. `FormatDigits.java`: a
    format's control takes ASCII digits only. `NumericValues.java` counts what a table of
    those numbers would hold (521 runs, some 4 KB as text).
  - `Sweep.java`: `digit` of radix 10 and 36 and `toTitleCase` of every code point, hashed:
    the JDK's numbers on the JVM and wasm.
  - `Strip.java` (33 lines): `stripIndent` and `translateEscapes`; `Title.java`: where
    `toTitleCase` is not `toUpperCase` (58 code points), `codePointAt` and `codePointCount`
    of a sequence and their refusals.
- Sizes (wasm bytes, the jar of 142e581f9 against this one): `SzParse` 58,309 -> 59,847,
  `FoBig` 189,254 -> 190,252, `Fo` 154,102 -> 153,512, `SzFormat` 153,996 -> 153,406,
  `SzDouble` 75,696 -> 75,259, `SzToList` 146,606 -> 145,705, `MapOnly` 138,136 -> 137,427,
  `MultiOnly` 144,849 -> 144,142, `GatherOnly` 163,080 -> 162,576, `SzUnmod` 171,900 ->
  172,024, `SzUnmodSet` 102,346 -> 102,558, `SzRemoveAll` 154,176 -> 154,295, `SzSub`
  142,452 -> 142,548, `SzUnmodMap` 160,850 -> 160,855; the 31 others unchanged. YAVI
  (`examples/java/yavi`): `Member.java` 643,806 -> 645,561, `Booking.java` 1,118,067 ->
  1,119,947, the same output. Drafts of the digit table measured in a small Lisp program
  (`(print (dig (char-code (char (read-line) 0))))`, 4,123 B with `digit-char-p`): a
  quoted list of the zeros 6,207, a vector literal 7,957, a string of three base-64
  characters a bound 7,753, a string of the zeros as characters 6,820.
