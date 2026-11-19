# k33. YAVI compiles to wasm: the milestone and what stands before it

Difficulty: High

Asked by the user (2026-11-19) with `.todo/k24`, whose mechanism is done
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
- **The schema example** (the README's first): stops at a class literal as a value;
  unmeasured behind it.

## The order

1. `.todo/k27` -- `LinkedHashMap`, `LinkedHashSet`, `LinkedList` (the order of the lines).
2. `.todo/k28` -- `java.text.MessageFormat` (the text of every message).
3. `.todo/k29` -- `java.text.Normalizer` (every string checked).
   After 1 to 3 the classic example is expected to print the JDK's lines from the
   unpatched coordinates: add it then as an opt-in test (network or `~/.m2`, so not in
   `./mvnw test`) and an example under `examples/java/`.
4. `.todo/k30` -- a `Class` as a value, then measure the schema example again and file
   what it meets behind it.
5. `.todo/k26` -- `java.time`, for a program that validates a date; `.todo/k31` --
   `BigInteger` / `BigDecimal`. Neither is reached by the two examples.
6. `.todo/k32` -- the Maven plugin passing a project's dependencies, so that a YAVI
   project builds with `rontolisp:wasm`.

## Not decided here

- Size: 575 KB for the classic example. Where it goes (the 50 `constraint` overloads are
  not it: only the three called are lowered) is worth a `--emit-size-report` once the
  program runs unpatched.
- YAVI itself was not changed (user, 2026-11-19: it may be, where the change is right for
  YAVI too). None of the gaps is YAVI's doing: each is a JDK class or a rule of the
  language the front end lacked. The one candidate, skipping `Normalizer` for a string
  that cannot change, is noted in `.todo/k29`.
