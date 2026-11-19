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
- **The schema example** (the README's first): stops at a class literal as a value;
  unmeasured behind it.

## The order

1. k27 (done 2026-11-19) -- `LinkedHashMap`, `LinkedHashSet`, `LinkedList` (the order of the lines).
2. k28 (done 2026-11-19) -- `java.text.MessageFormat` (the text of every message).
3. k29 (done 2026-11-19) -- `java.text.Normalizer` (every string checked).
   The classic example prints the JDK's lines from the unpatched coordinates. The
   example under `examples/java/` and its check in `examples.yaml` are `.todo/k34`.
4. `.todo/k30` -- a `Class` as a value, then measure the schema example again and file
   what it meets behind it.
5. `.todo/k26` -- `java.time`, for a program that validates a date; `.todo/k31` --
   `BigInteger` / `BigDecimal`. Neither is reached by the two examples.
6. `.todo/k32` -- the Maven plugin passing a project's dependencies, so that a YAVI
   project builds with `rontolisp:wasm`.

## Not decided here

- Size: 655 KB for the classic example (575 KB before k27's linked collections, 591 KB
  before k28's `MessageFormat`, 22 KB of which a program formatting one message pays, 617 KB
  before k29's `Normalizer`, whose tables are 23 KB: `.kb/unicode-normalization.md`). Where it
  goes (the 50 `constraint` overloads are not it: only the three called are lowered) is
  worth a `--emit-size-report` once the program runs unpatched.
- YAVI itself was not changed (user, 2026-11-19: it may be, where the change is right for
  YAVI too). None of the gaps is YAVI's doing: each is a JDK class or a rule of the
  language the front end lacked. The one candidate, skipping `Normalizer` for a string
  that cannot change, was not taken (k29): the library's own scan below the form's first
  code point does that, and the call's code and tables travel either way.
