# k24: YAVI through the source path, measured 2026-11-19

`am.ik.yavi:yavi:0.18.0` (Apache-2.0), its sources jar from Maven Central: 380 Java files,
183,636 lines. Nothing of YAVI is in this directory: the two programs are written here over
its public API, `patch.sh` holds `sed` lines and `shim/` classes written here (the
classic example needs none since 2026-11-19; the schema example has five edits since
2026-11-20 (two since k36, k38 and k31), each around a class the library lacks).

## How to run it again

```bash
R="java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
# the real thing: the first refusal the program reaches, by name
(cd classic && $R --java-source-dep am.ik.yavi:yavi:0.18.0 shelf/Book.java -o /tmp/book.wasm)
# past the gaps, one at a time: a patched copy of the sources, each edit one gap
unzip -q yavi-0.18.0-sources.jar -d /tmp/yavi && ./patch.sh /tmp/yavi /tmp/yavi-patched
(cd classic && $R --java-source-path /tmp/yavi-patched shelf/Book.java -o /tmp/book.wasm)
# the oracle
(cd classic && java -cp yavi-0.18.0.jar shelf/Book.java)
```

A gap that closes loses its line of `patch.sh` and its class of `shim/`; the milestone is
both programs printing a JDK's lines from the unpatched coordinates.

## `classic/shelf/Book.java` (`ValidatorBuilder`, `constraint`, `validate`, the messages)

The refusals reached, in the order met, and what was done with each:

| # | Refusal | Where | Done |
|---|---------|-------|------|
| 1 | all 380 files refused by the parser: 213 a type annotation (`Function<A, @Nullable B>`), 74 `@Nullable` / `@NullMarked` of no type, 3 an annotation type | everywhere | fixed here (annotations, `@interface`) |
| 2 | `reference to constraint is ambiguous` in effect: the 50 overloads of `constraint` taking a function, told apart by what `Book::title` returns | `ValidatorBuilder` | fixed here (an exact method reference counts by its return type) |
| 3 | `the class java.math.BigInteger` named by an overload not taken | `ValidatorBuilder.ToBigInteger` | fixed here (a class nobody has is a name until reached) |
| 4 | `the class java.text.Normalizer` | `CharSequenceConstraint.<init>` | fixed by k29 (the normalization forms the front ends share) |
| 5 | `the class java.util.LinkedHashMap` (`LinkedHashSet`, `LinkedList` after it) | `ValidatorBuilder.PredicatesList` | fixed by k27 (library source) |
| 6 | `the class java.io.Serializable` | `fn/Validation` | fixed here (library source) |
| 7 | `no suitable constructor found for String(int[],int,int)` | `IdeographicVariationSequence` | fixed here |
| 8 | `incompatible types: Object cannot be converted to Collection` (the capture of `CollectionValidator<T, ?, ?>` lost the bound of `N extends Collection<E>`) | `Validator.validate` | fixed here |
| 9 | `java.text.MessageFormat` | `SimpleMessageFormatter` | fixed by k28 (library source) |

With the three shims the program lowers and runs on the interpreter, the JVM, wasm and the
component, and prints the JDK's lines but for their order, which is the shim's (`HashMap`
where the library keeps insertion order). Since k27 (2026-11-19) two shims are left
(`MessageFormat`, `Normalizer`) and the four print the JDK's lines in its order: wasm
590,666 B, the component 594,785 B, the jar 232,947 B. Since k28 (2026-11-19) one is left
(`Normalizer`): wasm 616,785 B, the component 620,879 B, the jar 240,951 B. Since k29
(2026-11-19) none is left: the first command above compiles the program from the unpatched
coordinates, and the interpreter, the JVM, wasm and the component print the JDK's seven
lines (wasm 655,275 B, the component 659,541 B, the jar 255,602 B). The k24 numbers:

- 171 of the 380 files read and declared (24,858 lines), 713 top-level forms.
- `-o book.wasm`: 7.9 s wall (JVM start included), 575,271 B; `--component` 579,253 B;
  `-o book.jar` 228,710 B; the interpreter 2.5 s to its last line.
- Lowering every class whole could not be measured as an alternative: `ValidatorBuilder`
  names `java.time` in its `constraint` overloads, so the first class refuses.

## `schema/shelf/Book.java` (the README's first example: `Yavi.schema()` .. `fromMap`)

| # | Refusal | Where | Done |
|---|---------|-------|------|
| 1 | `class is not allowed to extend sealed class` (a permitted class in a file of its own, read while the `permits` clause naming it was) | `NullAcceptingValueValidator` | fixed here |
| 2 | `a class literal but compared with a class or before getName()` (`String.class` as an argument) | `YaviSchema._string` | fixed by k30 (a `Class` is a value) |
| 3 | `the class java.time.LocalDate` (`LocalTime` .. `Period` after it: `X::parse` in the table of the types read from their text; `Year.of` and `DateTimeException` in the conversion of a number, met once row 9 went) | `core/UntypedInput` | `.todo/k35`; `patch.sh` 1 |
| 4 | `the class java.util.UUID` (the same table) | `core/UntypedInput` | fixed by k36 (library source); `patch.sh` 2 is gone |
| 5 | `lambda expression not expected here` (`X.this.lazy().andThen(.. -> .. .map(x2 -> () -> x2))`: a generic method called from its own body, its type argument made of its own type parameter) | `arguments/Arguments1Validator.andThen` | fixed with k30 |
| 6 | a program that never ended: `Arguments1Validator.this` in an anonymous `Arguments1Validator` named the anonymous instance | the same | fixed with k30 |
| 7 | `no suitable constructor found for StringValidator(..)` (`new StringValidator<>(.., lazy, ..)` in `StringValidator<T>` making a `StringValidator<Supplier<T>>`) | `arguments/StringValidator.lazy` | fixed with k30 |
| 8 | `the class java.util.Date`, then `java.sql.Date` / `Time` / `Timestamp` (`instanceof`) | `core/UntypedInput.convert`, `SqlTypes` | `.todo/k37`; `patch.sh` 3 |
| 9 | `the class java.math.BigInteger` (`type == BigInteger.class`), `BigDecimal` in the conversion of a number | `core/UntypedInput.typeName`, `convertNumber` | fixed by k31 (library source); `patch.sh` 4 is gone |
| 10 | `the class java.util.stream.StreamSupport` (`StreamSupport.stream(properties.spliterator(), false)`) | `input/Projection` | fixed by k38 (library source); `patch.sh` 5 is gone |
| 11 | `method composed .. cannot be applied to given types` (two `Function<? super A1, ? extends A1>` given for one type parameter) | `arguments/DefaultArguments1Validator.preprocess` | fixed with k30 |

327 files (175,141 lines) are read and declared before the second refusal, in 1.7 s: the
schema names nearly the whole library in its declarations. Since k30 (2026-11-20) the
unpatched coordinates stop at row 3, and with `patch.sh`'s edits (rows 3, 8, 9; row 4 went
with k36, 2026-11-20: wasm 1,019,971 B, the component 1,024,234 B, the jar 385,127 B; row 10
with k38, 2026-11-20: wasm 1,032,990 B (12.6 s), the component 1,037,253 B, the jar 390,454 B,
the interpreter 5.4 s; row 9 with k31, 2026-11-20: wasm 1,095,209 B, the component
1,099,574 B, the jar 407,992 B, and the cut of `Year` in the conversion moved to edit 1)
the program compiles and the interpreter, the JVM, wasm and the component print the JDK's
lines. They were eleven until k31, which gave the program five inputs that run the
conversion of a number (a `long`, two doubles, a `BigDecimal` and a `BigInteger` given
for an `Integer`): 23 lines, wasm 1,095,450 B (13.6 s), the component 1,099,815 B, the
jar 408,486 B, the interpreter 5.7 s.

```bash
unzip -q yavi-0.18.0-sources.jar -d /tmp/yavi && ./patch.sh /tmp/yavi /tmp/yavi-patched
(cd schema && $R --java-source-path /tmp/yavi-patched shelf/Book.java -o /tmp/book.wasm)
```

wasm 1,009,805 B (12.8 s of wall time, the interpreter 5.4 s to its last line), the
component 1,014,068 B, the jar 381,860 B. The edits cut bodies out, so what those bodies
meet once their classes are there is not measured. Found beside the way: one entry left
in the table (`Map.ofEntries(Map.entry(.., X::parse))`) is `cannot infer type-variable(s)
V` (`.todo/k39`), and two compiles of the classic example differ in their bytes
(`.todo/k41`).

## What YAVI names of a JDK that the library lacks (by import, all 380 files)

`java.math.BigDecimal` 46, `BigInteger` 44; `java.time` 9 classes about 42 each, `Clock` 9,
`Period` 2 and seven of `java.time.temporal` / `.format` / `.chrono` once each;
`java.util.LinkedHashMap` 15, `LinkedHashSet` 9, `LinkedList` 2, `IdentityHashMap` 2, `UUID` 2,
`EnumSet`, `ResourceBundle`, `MissingResourceException`; `java.text.MessageFormat` 4,
`Normalizer` 3; `java.net.URL`, `URLEncoder`, `MalformedURLException`;
`java.lang.reflect.Array`; `java.util.stream.StreamSupport`; `javax.*` in the annotation
processor only, which no program names.
