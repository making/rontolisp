# Testing a Java program

A program's tests pass on a JDK, and the program rontolisp compiles is not the JDK's: the
[subset](index.md) lowers the language itself and has its own classes, so a suite that is
green under surefire says nothing certain of the compiled program. `rontolisp test` runs
the same test sources on the program rontolisp makes:

```console
$ cat src/test/java/shop/CartTest.java
package shop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartTest {

    @Test
    void anEmptyCartCostsNothing() {
        assertThat(new Cart().total()).isZero();
    }

    @ParameterizedTest
    @CsvSource({ "tea, 2, 900", "cup, 1, 1200" })
    void aLineCostsItsPriceTimesItsCount(String item, int count, int total) {
        assertThat(new Cart().add(item, count).total()).isEqualTo(total);
    }

    @Test
    void anUnknownItemIsRefused() {
        assertThatThrownBy(() -> new Cart().add("spoon", 1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spoon");
    }

}
$ rontolisp test --java-source-path src/main/java \
    --java-source-dep org.junit.jupiter:junit-jupiter-params:6.0.3 src/test/java/shop/CartTest.java
Running shop.CartTest
Tests run: 4, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in shop.CartTest
shop.CartTest.aLineCostsItsPriceTimesItsCount[2] <<< FAILURE!

expected: 1200
 but was: 1000

Results:

Tests run: 4, Failures: 1, Errors: 0, Skipped: 0
$ echo $?
1
```

The target is a test file, or several files of one source tree, run as one program, the
classes of the first file first, then the next's. The tests are found while the files are
compiled, as a compiled program has no class file to scan: every class of a file with a
test, and the `@Nested` classes in them. A further file is of the first file's tree, at the
path its package and class name spell:

```bash
rontolisp test --java-source-path src/main/java \
    --java-source-dep org.junit.jupiter:junit-jupiter-params:6.0.3 \
    src/test/java/shop/CartTest.java src/test/java/shop/stock/StockTest.java
```

A directory stands for the `.java` files below it, in the order of their paths, so
`rontolisp test ... src/test/java` runs every test of the tree. Which of its classes have
tests is found as for a file: a class with no test annotation of its own may inherit tests.
 The code under test is read from the
[source path](index.md#using-a-third-party-library) (`--java-source-path`,
`--java-source-dep`) and compiled with the tests, so a test calls the package-private
members of its package as it does under javac.

JUnit Jupiter is a dependency of the program, as it is of the project, and is compiled from
its sources like any library: `--java-source-dep
org.junit.jupiter:junit-jupiter-params:VERSION`, the version the project tests with,
brings its parameterized tests, its API and what they depend on (`junit-jupiter-api` is
enough for a file with no parameterized test). Its licence (EPL-2.0) is the program's
then, as for a run on a JDK ([Licenses](../compiling/licenses.md)). A run without it says
so:

```console
$ rontolisp test --java-source-path src/main/java src/test/java/shop/CartTest.java
error: src/test/java/shop/CartTest.java:12:5: the class org.junit.jupiter.api.Test is not on the source path: JUnit Jupiter is a dependency of the program, compiled from its sources (--java-source-dep org.junit.jupiter:junit-jupiter-params:VERSION)
```

AssertJ is not read from the source path, even where its sources jar is there (as a Maven
project's test dependencies put it): the subset has a part of it of its own, under its names.

The status is **0** when every test passed, **1** when one failed, when what is compiled
is refused or when no test ran, and **2** for a wrong command line. A test that throws an
`AssertionError` is a failure and any other exception an error, as surefire counts them;
a run of a parameterized test is a test, named by its place among the runs. A test whose
assumption does not hold (`assumeTrue`, `abort`) is skipped; one in a `@BeforeAll` skips
every test of the class, each test and each `@Nested` class counted one, as surefire
counts them.

## On the four backends

With no `-o` the tests run on the interpreter. With `-o` the run is compiled, and the
artifact has the same report and the same status:

```bash
T="rontolisp test --java-source-path src/main/java
   --java-source-dep org.junit.jupiter:junit-jupiter-params:6.0.3
   src/test/java/shop/CartTest.java"
$T                                                          # interpreter
$T -o CartTests.class && java CartTests                     # JVM
$T -o cart-tests.wasm && wasmtime run cart-tests.wasm       # WASM preview 1
$T -o cart-tests-comp.wasm --component && \
  wasmtime run cart-tests-comp.wasm                         # WASM component
```

A `@TempDir` is made in `/tmp`, which a `.wasm` reaches only where it is preopened:
`wasmtime run --dir /tmp cart-tests.wasm`.

## A Maven project

The `test` goal of the [Maven plugin](../compiling/maven-plugin.md#junit-jupiter-tests-srctestjava)
runs a project's tests this way at `test`, after surefire, on the backends `<backends>`
names, and fails the build with the name of a failed test.

## JUnit Jupiter

| Annotation | What runs |
| --- | --- |
| `@Test` | A method of no parameter but a `@TempDir` and the `TestInfo` |
| `@RepeatedTest` | The method `value` times, each run given its `RepetitionInfo` and named by `name` (`{displayName}`, `{currentRepetition}`, `{totalRepetitions}`); once `failureThreshold` runs failed, the rest are skipped, an instance still made for each as JUnit does. A `value` that is not positive, a threshold out of range or a blank name is an error of the test |
| `@ParameterizedTest` | The method once for each row of its sources, then each argument that is `AutoCloseable` closed, the last first (not with `autoCloseArguments = false`): `@ValueSource` (every element but `classes`), `@CsvSource` (`value`, `textBlock`, `delimiter`, `delimiterString`, `quoteCharacter`, `emptyValue`, `nullValues`, `ignoreLeadingAndTrailingWhitespace`), `@MethodSource` (static methods of the class named by string literals, or methods of its shared instance under `@TestInstance(PER_CLASS)`, or the one named as the test: a `Stream`, an `Iterable`, an array, an `IntStream`, a `LongStream` or a `DoubleStream`, of `Arguments`, of arrays or of single arguments), `@EnumSource` (`value`, `names`, `mode`), `@NullSource`, `@EmptySource`, `@NullAndEmptySource`. A text is converted to a parameter of a primitive type, of its box or of an enum, and a number widens |
| `@BeforeEach`, `@AfterEach` | Around each test, on its instance, given its `TestInfo`, and its `RepetitionInfo` where every test they run around is repeated: those of an interface and of a class above first before a test and last after it, those of the class around a `@Nested` class outside its own |
| `@BeforeAll`, `@AfterAll` | Static methods, or any method under `@TestInstance(PER_CLASS)`, given the class's `TestInfo`, around the tests of the class and of its `@Nested` classes. One that throws is an error of the class, whose tests then do not run |
| `@TestInstance` | `PER_CLASS`: one instance for all the tests of the class, made before its `@BeforeAll`; where it cannot be made, nothing of the class runs |
| `@TestMethodOrder`, `@Order` | The tests of the class in the order of `MethodOrderer.OrderAnnotation` (by `@Order`, a test without one `Order.DEFAULT`), `MethodName` or `DisplayName` (equal names by their parameters' types) |
| `@Tag`, `@Tags` | The tags `TestInfo.getTags()` answers: the test's, then its class's (the class above's, each interface's, its own), then the class around's, trimmed, an invalid one left out. No run selects tests by tag |
| `@Nested` | The tests of an inner class, each on an instance made in an instance of the class around it |
| `@Disabled` | A test counted as skipped, or a class: each of its tests and `@Nested` classes |
| `@TempDir` | A field or a parameter of type `Path` or `File`: a directory of its own in `/tmp` for each, a static field's around the tests of its class, the others' around each test, deleted with what it holds after it |
| `@DisplayName` | Of a test: written after the test's name where it fails; of a test or a class: the `TestInfo`'s display name |

A source written more than once gives its rows as JUnit gives them: the sources of one kind
together, where the first of them is written. A `Named` argument is its payload.

The assertions, the assumptions and the rest of JUnit Jupiter's API are JUnit's own,
compiled from its sources, so a failed assertion says what that version of JUnit says. Of
JUnit Jupiter 6.0.3, the subset refuses `assertTimeoutPreemptively` where a test calls it
(it reads a thread's stack).

A class inherits the tests of the classes of the program above it and of the interfaces it
implements (their default methods, and an interface's static `@BeforeAll` and `@AfterAll`);
a method overridden with no annotation is no test. `@TestInstance` and `@TestMethodOrder`
are found on the class, then on its interfaces, then on the classes above, and a `@Nested`
class takes the orderer of the class around it. With no orderer the tests of a class run in
the order declared, where JUnit runs them in an order of its own.

## What there is of AssertJ

`org.assertj.core.api.Assertions` has `assertThat` of the kinds below, `assertThatThrownBy`,
`assertThatCode`, `assertThatExceptionOfType` (with `assertThatIllegalArgumentException`,
`assertThatIllegalStateException`, `assertThatNullPointerException`,
`assertThatIOException`), `assertThatNoException`, `catchThrowable`, `fail`, `entry`,
`tuple`, the offsets `within`, `offset`, `withPrecision` and `byLessThan` (of a `double`,
`float`, `int` and `long`), and the factories of `InstanceOfAssertFactories` that
`asInstanceOf` takes (one for each kind below, `type`, `list`, `iterable`, `map`, `optional`,
`throwable`, `array`, `comparable`, `future`, `completableFuture`).

| `assertThat` of | Checks |
| --- | --- |
| any value | `isEqualTo` (two arrays by their elements), `isNotEqualTo`, `isNull`, `isNotNull`, `isSameAs`, `isNotSameAs`, `isInstanceOf`, `isNotInstanceOf`, `isExactlyInstanceOf`, `isInstanceOfSatisfying`, `isIn`, `isNotIn` (of values or an `Iterable`), `hasToString`, `doesNotHaveToString`, `matches`, `satisfies`, `asInstanceOf`, and `as`, `describedAs`, `withFailMessage`, `overridingErrorMessage` |
| an object | `extracting` with a function or by the names of properties or fields (several make a list), `hasFieldOrProperty`, `hasFieldOrPropertyWithValue`, `usingRecursiveComparison` (also of a `Map`, an `Iterable`, an array of objects, an `Optional`) and on it `isEqualTo`, `isNotEqualTo`, `ignoringFields`, `comparingOnlyFields`, `ignoringActualNullFields`, `ignoringExpectedNullFields`, `ignoringCollectionOrder`, `withStrictTypeChecking` |
| a `Comparable` of the program's (an enum too) | `isGreaterThan`, `isGreaterThanOrEqualTo`, `isLessThan`, `isLessThanOrEqualTo`, `isBetween`, `isStrictlyBetween`, `isEqualByComparingTo`, `isNotEqualByComparingTo` |
| a `String` | `isEmpty`, `isNotEmpty`, `isNullOrEmpty`, `isBlank`, `isNotBlank`, `hasSize` and its four comparisons, `contains`, `doesNotContain`, `containsIgnoringCase`, `containsSubsequence`, `startsWith`, `doesNotStartWith`, `endsWith`, `doesNotEndWith`, `isEqualToIgnoringCase`, `matches`, `doesNotMatch` |
| an `int`, `long`, `float`, `double` or its box | `isZero`, `isNotZero`, `isOne`, `isPositive`, `isNegative`, `isNotNegative`, `isNotPositive`, `isGreaterThan`, `isGreaterThanOrEqualTo`, `isLessThan`, `isLessThanOrEqualTo`, `isBetween`, `isStrictlyBetween`, `isEqualByComparingTo`, `isCloseTo`, `isNotCloseTo`; of a `double` and a `float` `isEqualTo` with an offset; of a `double` `isNaN`, `isNotNaN` |
| a `char`, a `boolean` | `isGreaterThan`, `isLessThan`; `isTrue`, `isFalse` |
| an `Iterable`, a `List` | `isEmpty`, `isNotEmpty`, `isNullOrEmpty`, `hasSize` and its four comparisons, `contains`, `containsAll`, `doesNotContain`, `containsExactly`, `containsExactlyElementsOf`, `containsExactlyInAnyOrder`, `containsExactlyInAnyOrderElementsOf`, `containsOnly`, `containsSequence`, `containsSubsequence`, `anyMatch`, `allMatch`, `noneMatch`, `allSatisfy`, `anySatisfy`, `extracting` (by names, a `Tuple` an element), `map`, `filteredOn` with a predicate or a name and a value, `first`, `last`, `element`, `singleElement` |
| an array of objects, of `byte`, `int`, `long`, `char` or `double` | `isEmpty`, `isNotEmpty`, `isNullOrEmpty`, `hasSize`, `contains`, `doesNotContain`, `containsExactly`, `containsExactlyInAnyOrder`; of objects `containsOnly`, of a primitive type `startsWith` |
| a `Map` | `isEmpty`, `isNotEmpty`, `isNullOrEmpty`, `hasSize`, `containsKey`, `containsKeys`, `doesNotContainKey`, `doesNotContainKeys`, `containsValue`, `doesNotContainValue`, `containsEntry`, `doesNotContainEntry`, `contains`, `containsAllEntriesOf`, `containsOnly`, `containsOnlyKeys`, `containsExactly` |
| an `Optional` | `isPresent`, `isNotEmpty`, `isEmpty`, `isNotPresent`, `contains`, `hasValue`, `hasValueSatisfying`, `get` |
| a `Class` | `isInterface`, `isNotInterface`, `isAnnotation`, `isNotAnnotation`, `isRecord`, `isNotRecord`, `isAbstract`, `isFinal`, `isNotFinal`, `isPublic`, `isProtected`, `isPrivate`, `isPackagePrivate`, `isStatic`, `isNotStatic`, `isAssignableFrom`, `isAssignableTo`, `hasSuperclass`, `hasNoSuperclass`, `isPrimitive`, `isNotPrimitive`, `hasPackage` |
| a `Path` | `exists`, `doesNotExist`, `isRegularFile`, `isDirectory`, `isAbsolute`, `isRelative`, `hasFileName`, `hasExtension`, `hasNoExtension`, `hasContent`, `hasBinaryContent`, `hasSize`, `isEmptyFile`, `isNotEmptyFile`, `isEmptyDirectory`, `isNotEmptyDirectory`, `content`, `binaryContent`, and those of a `Comparable` |
| a `File` | `exists`, `doesNotExist`, `isFile`, `isDirectory`, `isAbsolute`, `isRelative`, `canRead`, `hasName`, `hasFileName`, `hasParent`, `hasNoParent`, `hasExtension`, `hasNoExtension`, `hasContent`, `hasBinaryContent`, `hasSize`, `isEmpty`, `isNotEmpty`, `isEmptyDirectory`, `isNotEmptyDirectory`, `content`, `binaryContent` |
| a `LocalDate`, `LocalDateTime`, `LocalTime`, `Instant` | `isBefore`, `isBeforeOrEqualTo`, `isAfter`, `isAfterOrEqualTo`, `isBetween`, `isStrictlyBetween`, and `isEqualTo`, `isNotEqualTo`, `isIn`, `isNotIn` of the text the class parses; their fields (`hasYear`, `hasMonth`, `hasMonthValue`, `hasDayOfMonth`, `hasHour`, `hasMinute`, `hasSecond`, `hasNano`) |
| a `Duration` | `isZero`, `isPositive`, `isNegative`, `hasNanos`, `hasMillis`, `hasSeconds`, `hasMinutes`, `hasHours`, `hasDays`, `isCloseTo`, and those of a `Comparable` |
| a `Future`, a `CompletableFuture` | `isDone`, `isNotDone`, `isCancelled`, `isNotCancelled`, `succeedsWithin`; of a `CompletableFuture` `isCompleted`, `isNotCompleted`, `isCompletedWithValue`, `isCompletedWithValueMatching`, `isCompletedExceptionally`, `isNotCompletedExceptionally`, `hasFailed` |
| a `Throwable` | `hasMessage`, `hasMessageContaining`, `hasMessageNotContaining`, `hasMessageStartingWith`, `hasMessageEndingWith`, `hasMessageMatching`, `hasNoCause`, `hasCauseInstanceOf`, `hasRootCauseInstanceOf`, `hasRootCauseMessage`, `cause`, `rootCause`, `doesNotThrowAnyException`, and after `isThrownBy` the same under `withMessage` .. `withRootCauseInstanceOf`, `havingCause`, `havingRootCause` |

A failed check says what AssertJ says, and throws the class AssertJ throws beside JUnit
(`AssertionFailedError` where two values are compared, `AssertionError` otherwise). Where
AssertJ adds the frames of a stack trace or the name of a JDK's own class, the message has
none.

A property is read as AssertJ reads it: by its getter (`getName`, else `isName`), else by a
field of the class or of a class above it. The recursive comparison walks the fields of the
program's classes; a JDK's type is compared with `equals` (a `double` and a `float` within
AssertJ's precisions), a collection, an array, a map and an `Optional` by what they hold. A
class of a library whose members the program does not keep is refused where it is compared
field by field.

## What is not there

An annotation of JUnit's that a run does not run, an element of one that it does not read,
and a class or a check of AssertJ that the subset lacks are refused by name when the file is
compiled, never skipped:

```console
$ rontolisp test --java-source-dep org.junit.jupiter:junit-jupiter-api:6.0.3 \
    src/test/java/shop/SlowTest.java
error: src/test/java/shop/SlowTest.java:13:5: the annotation org.junit.jupiter.api.Timeout is not supported by the experimental Java subset yet
```

Among them: `@TestFactory`, `@Timeout`, `@TestClassOrder`, `MethodOrderer.Random` and an
orderer of one's own, extensions, an annotation of JUnit's on an annotation type (a composed
annotation), the elements the table above does not name (`classes` of
`@ValueSource`, `from` and `to` of `@EnumSource`, `cleanup` and `factory` of `@TempDir`), a
parameter of a `@Test` method that is no `@TempDir` or `TestInfo`, a parameter of a
parameterized test whose type is JUnit's (`ArgumentsAccessor`, `TestInfo`, whose display
name would show the arguments), a `RepetitionInfo` given around a test that is not repeated;
of AssertJ, soft assertions and the checks the table above does not name (`hasParent` of a
`Path`, the recursive comparison's own comparators and introspection strategy, the checks
of its own of a `ZonedDateTime`, a `URI` or a `BigDecimal`, which are a `Comparable`'s). A
test class has one constructor, whose parameters may be the `TestInfo` and `@TempDir`
directories, and a `static` class in a test class is run only as a file's own class is.

`rontolisp format` refuses a `.java` file: it re-indents S-expressions.
