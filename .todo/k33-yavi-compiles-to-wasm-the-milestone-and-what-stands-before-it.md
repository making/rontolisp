# k33. YAVI compiles to wasm: the milestone and what stands before it

Difficulty: High

Asked by the user (2026-11-19) with k24, whose mechanism is done
(`.kb/java-frontend.md`, "A library from the source path"): **a Java program that uses YAVI
(`am.ik.yavi:yavi`, Apache-2.0) compiles to wasm and prints what `java` prints.** This
item holds the measurement and the order; each gap is an item of its own.

## State, measured 2026-11-19 against `am.ik.yavi:yavi:0.18.0`

`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`
has the two programs, the oracle's lines, the refusals met and how to run it again.

- **The classic example** (`ValidatorBuilder`, `constraint`, `validate`, the messages):
  `rontolisp --java-source-dep am.ik.yavi:yavi:0.18.0 shelf/Book.java -o book.wasm` stops at
  `java.text.Normalizer`. With three stand-ins it runs on the interpreter, the JVM, wasm
  (575,271 B, 7.9 s) and the component and prints the JDK's lines in another order.
  Since k27 (the linked collections, 2026-11-19) two stand-ins are left and the four
  print the JDK's lines in its order (wasm 590,666 B). Since k28 (`MessageFormat`,
  2026-11-19) one stand-in is left, `Normalizer` (wasm 616,785 B). **Since k29
  (`Normalizer`, 2026-11-19) the command above compiles it from the unpatched coordinates
  and the four print the JDK's lines** (wasm 655,275 B, the component 659,541 B, the jar
  255,602 B); YAVI checking decomposed text prints the JDK's lines too
  (`.todo/artefacts/k29-java-text-normalizer/shelf/Note.java`).
- **The schema example** (the README's first): stopped at a class literal as a value
  until k30 (2026-11-20). **From the unpatched coordinates it now stops at
  `java.time.LocalDate`**, in `core/UntypedInput`'s table of the types read from their
  string form. **With four edits to YAVI's source, each around a class the library
  lacks** (`patch.sh` of the artefact: `java.time`, `java.util.Date` and
  `java.sql`, `BigDecimal` / `BigInteger`, `StreamSupport`; the fifth, `UUID`, went with
  k36, 2026-11-20), **it compiles and the
  interpreter, the JVM, wasm and the component print the JDK's eleven lines**: wasm
  1,019,971 B (13.0 s), the component 1,024,234 B, the jar 385,127 B (with `UUID` kept
  in the table; 1,009,805 / 1,014,068 / 381,860 before). Nothing else
  stands between: the four rules of the language it met behind the class literals
  (a generic method calling itself, a creation with `<>` of the class it stands in,
  `Name.this` in an anonymous class of `Name`, two captures given for one type
  parameter) were fixed with k30.

## The order

1. k27 (done 2026-11-19) -- `LinkedHashMap`, `LinkedHashSet`, `LinkedList` (the order of the lines).
2. k28 (done 2026-11-19) -- `java.text.MessageFormat` (the text of every message).
3. k29 (done 2026-11-19) -- `java.text.Normalizer` (every string checked).
   The classic example prints the JDK's lines from the unpatched coordinates. The
   example under `examples/java/` and its check in `examples.yaml` are `.todo/k34`
   (done 2026-11-20).
4. k30 (done 2026-11-20) -- a `Class` as a value, and the four rules above.
5. What the schema example reaches of a JDK that the library lacks, each one edit of
   `patch.sh` and independent of the others but as said; the milestone is the day the
   last edit goes:
   - `.todo/k35` -- `java.time`: the nine classes read from their text (the largest;
     `ZonedDateTime` asks for the rules of a region).
   - `.todo/k31` -- `BigInteger` / `BigDecimal`: the conversion of a number
     (`UntypedInput.convertNumber`, `decimal`, `typeName`). It was thought behind the
     examples; the schema example reaches it.
   - `.todo/k36` -- `java.util.UUID` (done 2026-11-20: the schema example prints the JDK's
     lines with four edits left, wasm 1,019,971 B).
   - `.todo/k37` -- `java.util.Date` and `java.sql.Date` / `Time` / `Timestamp`, tested
     by `instanceof` (after `.todo/k35`, for what they convert to).
   - `.todo/k38` -- `StreamSupport` and `Iterable.spliterator()`.
   What those bodies meet once they are lowered is not measured: the edits cut them out.
6. `.todo/k34` (done 2026-11-20) -- `examples/java/yavi/Member.java`, a record checked
   with `ValidatorBuilder`, compiled with `--java-source-dep am.ik.yavi:yavi:0.18.0` and
   checked in `examples.yaml` (`javaSourceDep`) on the four backends: wasm 662,539 B.
   **Left here: when step 5 is done, the README's schema example (`Yavi.schema()`)
   joins it as a second program of `examples/java/yavi/`, with its `examples.yaml`
   entry and README lines.**
7. `.todo/k32` -- the Maven plugin passing a project's dependencies, so that a YAVI
   project builds with `rontolisp:wasm`.

Found on the way and on nobody's path: `.todo/k39` (two shapes of inference at a
variable arity parameter), `.todo/k40` (a session's `values()`), `.todo/k41` (the bytes
of two compiles differ). `.todo/k26` keeps the rest of `java.time` and of the streams.

## Not decided here

- Size: 1,010 KB for the schema example as patched, the classes of step 5 not in it.
  655 KB for the classic example (575 KB before k27's linked collections, 591 KB
  before k28's `MessageFormat`, 22 KB of which a program formatting one message pays, 617 KB
  before k29's `Normalizer`, whose tables are 23 KB: `.kb/unicode-normalization.md`). Where it
  goes (the 50 `constraint` overloads are not it: only the three called are lowered) is
  worth a `--emit-size-report` once the program runs unpatched.
- YAVI itself was not changed (user, 2026-11-19: it may be, where the change is right for
  YAVI too). None of the gaps is YAVI's doing: each is a JDK class or a rule of the
  language the front end lacked. The one candidate, skipping `Normalizer` for a string
  that cannot change, was not taken (k29): the library's own scan below the form's first
  code point does that, and the call's code and tables travel either way. One for the
  user to weigh (k30, not taken): `core/UntypedInput` holds every type an input is read
  as in one table and one method, so a schema of a string and an integer reaches -- and
  on wasm pays for -- `java.time`, `BigDecimal`, `UUID` and the `java.sql` tests of step
  5. A reader split by the type asked for would not; that is a change to YAVI's own
  design, and the library classes are owed either way.
