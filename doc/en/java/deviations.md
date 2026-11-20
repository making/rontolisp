# Deviations

The subset is partial by design. Where a program that the subset accepts behaves
differently from the language specification is listed here, and it is the same on every
backend but for the [threads](#threads) of a `.wasm`.

## What differs

**A string's elements are code points.** `length`, `charAt` and `substring` count Unicode
code points where the language counts UTF-16 units, so they differ for a character outside
the Basic Multilingual Plane. `hashCode` is the specified number, and `toCharArray` answers
the UTF-16 units, which `new String(char[])` reads back. `toUpperCase` and `toLowerCase`
change one character into one: `"ß".toUpperCase()` is `ß`, where a JDK answers `SS`. A
surrogate with no partner in a string is its three bytes in UTF-8, where a JDK writes `?`.
`Character.digit`, `getNumericValue`, `Integer.parseInt`, `UUID.fromString`, `valueOf` of
`java.sql` and the texts a `BigInteger` and a `BigDecimal` are made of read a digit outside ASCII
(`'٣'`) on the interpreter and the JVM and not in a `.wasm`.

```java
void main() {
    String s = "a𝄞b";
    IO.println(s.length() + " " + s.indexOf("b") + " " + s.hashCode());
}
```

```
3 2 57834039
```

A JDK prints `4 3 57834039`.

**`==` of two boxes compares their values.** A box is its value, so two boxes of one value
are one object. The language promises that of the values from -128 to 127 and allows it of
every other.

```java
void main() {
    Integer a = 1000, b = 1000;
    Long big = 1000L;
    Object boxed = big;
    IO.println((a == b) + " " + boxed.equals(a) + " " + (boxed instanceof Long));
}
```

```
true false true
```

A JDK prints `false false true`.

**A type argument is not checked where the program runs.** The language checks a value read
through a type argument where it is used; rontolisp trusts the type. A value of another
class gets there only through a raw type or a cast a compiler warns of as unchecked, and it
is then used as it is, where the language throws `ClassCastException`. A cast to a type
variable tests its bound, a cast to a parameterized type its class. A method taking a
narrower type than the one a call names tests its argument as a JDK's bridge method does when
the call can go to more than one class: `((Comparable) 1).compareTo("a")` throws
`ClassCastException`. A lambda tests nothing, nor does the one method a call can go to.

**A store into an array is not checked.** A value stored through an array type above the
array's own (`Object[] a = new String[1]; a[0] = 1;`) is stored, where the language throws
`ArrayStoreException`.

**The class of an array of `long`, of `short` or of references is the one a widening
recorded.** In a program that casts or tests (`instanceof`) a value to such an array type,
or asks the class name (`getClass()`) of a value that can be one, the class is recorded
where the array's type is widened: assigned to `Object` or to an array type above its own,
or passed where a type parameter stands. In any other program, `toString()` of such an
array read as an `Object` names `Object[]` for an array of `long` or of references and
`char[]` for an array of `short`.

```java
void main() {
    Object names = new String[] {"a"};
    String text = names.toString();
    IO.println(text.substring(0, text.indexOf('@')));
}
```

```
[Ljava.lang.Object;
```

A JDK prints `[Ljava.lang.String;`.

**A class is initialized at its first use only where a program can tell.** The language
initializes a class when it is first used, and so does rontolisp for a class whose static
initialization prints, throws, writes a static field of another class, or reads from
another class anything but a `final` field holding a value nothing can change. Every other
class is initialized before `main`: nothing it does can be told from an initialization at
first use.

```java
class Config {
    static int level = 1;
}

class Table {
    static final int[] SQUARES = new int[4];

    static {
        for (int i = 0; i < SQUARES.length; i++) {
            SQUARES[i] = i * i;
        }
    }
}

class Report {
    static final int LEVEL = Config.level;

    static {
        IO.println("Report is initialized");
    }
}

void main() {
    IO.println("main starts");
    Config.level = 3;
    IO.println(Table.SQUARES[3] + " " + Report.LEVEL);
}
```

```
main starts
Report is initialized
9 3
```

An exception a `throw` statement throws out of a static initialization, there or in what it
calls, ends it as the language says: the exception is the cause of an
`ExceptionInInitializerError`, an `Error` passes as it is, and every later use of the class
throws `NoClassDefFoundError`. An error the run time raises there differs. It reaches the
program as itself, the class stays usable as far as its initialization got, and inside an
initialization that runs before `main` it is raised before `main` starts.

```java
class Table {
    static final int[] CELLS = new int[2];

    static {
        IO.println("filling");
        CELLS[2] = 1;
    }
}

void main() {
    try {
        IO.println(Table.CELLS.length);
    } catch (Throwable thrown) {
        IO.println(thrown);
    }
    IO.println(Table.CELLS.length);
}
```

```
filling
java.lang.ArrayIndexOutOfBoundsException: Index 2 out of bounds for length 2
2
```

A JDK prints `java.lang.ExceptionInInitializerError` for the first read and ends the
program at the second with a `NoClassDefFoundError`.

**Two messages of the run time's exceptions differ.** An error of the run time is the
exception the language names, and a `catch` takes it as that class: a member, an element or
the length read through `null`, a `null` box read as its primitive and a `switch` over
`null` are a `NullPointerException`, a division by zero an `ArithmeticException`, an index
outside an array, a string or a `StringBuilder` an `ArrayIndexOutOfBoundsException` or a
`StringIndexOutOfBoundsException`, a negative array length a `NegativeArraySizeException`,
a mismatched `String.format` an `IllegalFormatException`. The messages are a JDK's, but
two. A `NullPointerException` the run time raises has none, where a JDK's describes the
expression that was `null` by names that depend on how the class was compiled. A
`ClassCastException`'s message names the two classes and leaves out what a JDK adds of
their modules and class loaders. A stack overflow is no `StackOverflowError` a program can catch.

**An uncaught exception is reported without a stack trace.** Standard error gets what a JDK
writes but the frames: `Exception in thread "main"` and the exception's `toString()`, then
the exceptions it suppressed and its causes, and the program exits with the status 1. One
line stands for the frames, after the causes: the file and the line of the statement the
exception was raised in, and the method that statement is written in. The interpreter and
a JVM output write it; a `.wasm` output writes it when compiled with
[`--report-locations`](../compiling/wasm.md#uncaught-condition-locations---report-locations).
`printStackTrace()` writes the same lines but that one. An exception keeps no frames:
`getStackTrace()` answers an empty array, after `setStackTrace` too.

```java
public class Uncaught {
    static void check(int balance, int amount) {
        if (amount > balance) {
            throw new IllegalStateException("insufficient funds: " + (amount - balance));
        }
    }

    static void withdraw(int balance, int amount) {
        try {
            check(balance, amount);
        } catch (IllegalStateException e) {
            throw new RuntimeException("withdrawal refused", e);
        }
    }

    public static void main(String[] args) {
        withdraw(70, 100);
    }
}
```

```console
$ rontolisp Uncaught.java
Exception in thread "main" java.lang.RuntimeException: withdrawal refused
Caused by: java.lang.IllegalStateException: insufficient funds: 30
  at Uncaught.java:12 in Uncaught.withdraw(int,int)
```

**Some errors a compiler reports are not reported**, and such a program runs. A
`protected` member is reached from every class under its class, where the language allows
an instance member only through a reference of that class's own type and a constructor
only through `super(..)`. Of the warnings a compiler prints, only the
[unchecked ones](index.md#unchecked-warnings) of a call, a conversion, a cast and a
variable arity parameter and the one of a `catch` clause nothing reaches are.

**A record's `hashCode`** is computed from its components in order, `31 * h + hash`; the
language leaves the number unspecified.

**`hashCode` and `toString` of an object declaring neither** are an identity number of
rontolisp's own, `Name@` and that number in hexadecimal. A `HashMap` keyed by such objects,
enum constants included, walks them in another order than a JDK, whose own order changes
from run to run.

**The order of a `HashMap` differs from a JDK's in two cases.** More than eight keys whose
hash codes fall into one bucket of a table of 64 buckets or more are walked in the order
they were put. Keys of a `Float` with few digits get there from 25 keys on (`0.0`, `0.5`, `1.0` ..: their
hash codes differ in the high bits only), keys of a `Double` with few digits from 64, and so
do the keys of a class whose `hashCode` has few values; strings and integers do not. `Set.of` and `Map.of` have
one fixed order, where a JDK picks another at every start.

**A `TreeMap` and a sort ask their comparator about other pairs than a JDK's.** The tree is
balanced another way and a sort of objects is a merge sort of rontolisp's own, so a comparator
that counts or prints its calls sees other pairs and another number of them; the order and
what the map answers are a JDK's. A map's `keySet()`, `values()`, `entrySet()` and
`descendingMap()` are a new view at each call, where a JDK's map answers the same object every
time: `m.values().equals(m.values())` is `false`.

**A few answers of the unmodifiable collections differ.** A list of `List.of` of one or two
elements refuses an index outside it with `ArrayIndexOutOfBoundsException: Index 2 out of
bounds for length 2`, a JDK's with `IndexOutOfBoundsException: Index: 2 Size: 2`, and its
iterator's `remove()` before `next()` throws `IllegalStateException`, a JDK's
`UnsupportedOperationException`. A set of `Set.of` and a map of `Map.of` answer a `null` asked
for (`contains(null)` is `false`), where a JDK's throw `NullPointerException`, and the views of
such a map refuse even a change that changes nothing. A list of `Arrays.asList` refuses a
removal with no message, a JDK's with `remove`. The list of `Stream.toList()` is
`Collections.unmodifiableList`'s, where a JDK's is a list of `List.of`'s kind that holds nulls:
an index outside it is an `IndexOutOfBoundsException`, and its reversed view checks an index
before it refuses a change. `List.of()`, `Set.of()` and `Map.of()` are a new object at each
call. An iterator of a list of `AbstractList`'s past its end throws `NoSuchElementException`
with no cause, where a JDK's has the list's `IndexOutOfBoundsException` as its cause.

**A part of a list (`subList`) and an iterator of a list refuse a change made behind them**,
as a JDK's do, through the count `AbstractList.modCount` which `ArrayList`, `LinkedList` and
`Vector` keep and a class of the program under `AbstractList` keeps up if it wants them to.
A part of a list that was changed some other way refuses its members with
`ConcurrentModificationException`, and so does an iterator of a list after a change made
behind it. A few answers of such a part differ from a JDK's: `hasNext`, `estimateSize()` and the
spliterator's `forEachRemaining` of a part whose list changed refuse where a JDK's answer; a
range or an index outside such a part (`subList`, `addAll` at an index) is refused as the
change is; the part of a `LinkedList` or of a program's list refuses `subList` where a JDK's
makes one; the iterator of such a part throws `ConcurrentModificationException` for a `next()`
past the end where a JDK's throws `NoSuchElementException`, and that of a `LinkedList`'s part
`IllegalStateException` for a `remove()` before `next()` where a JDK's throws
`ConcurrentModificationException`; the class of a part is `java.util.AbstractList$SubList`. A part of a
`CopyOnWriteArrayList` refuses nothing and its iterator sees what is added, where a JDK's
refuses a change made behind it and walks the elements it had.

**The functions of `Math` are those of `StrictMath`**, so a result is the same on every
backend and processor. A JDK's `Math.sin`, `Math.exp` and the like may differ from it in
the last digit.

**A NaN has no payload.** Every NaN is one value: `floatToRawIntBits` and
`doubleToRawLongBits` give the bits `floatToIntBits` and `doubleToLongBits` give, and
`intBitsToFloat` and `longBitsToDouble` of any NaN's bits give that NaN.

**Regular expressions count characters, not UTF-16 units.** `java.util.regex` and the
`String` members that take a pattern run on an engine written for this project from the
`Pattern` documentation; a match's `start` and `end` count the characters of the input, as
`length` and `charAt` do here. Where a JDK's `find` steps between the halves of a surrogate
pair the matches differ. A character class or a property under `CANON_EQ` and `\N{name}`
are an error when the pattern is compiled. `Pattern` has `compile`, `matches`, `quote`,
`matcher`, `split`, `pattern`, `flags`, `asPredicate`, `asMatchPredicate` and the flag
constants; `Matcher` has `find`, `lookingAt`, `matches`, `group`, `start`, `end`,
`groupCount`, `hasMatch`, `reset`, `region`, `regionStart`, `regionEnd`, the anchoring and
transparent bounds, `hitEnd`, `requireEnd`, `replaceAll` and `replaceFirst` of a text or a
function, `appendReplacement`, `appendTail`, `toMatchResult` and `quoteReplacement`;
`results` and `splitAsStream`; `usePattern` and `namedGroups` are not there. After `lookingAt` or
`matches` in a region with transparent bounds, a JDK's `start()` is 0 when a look-behind
read before the region; here it is the region's start.

**`String.format` has the conversions [The JDK classes](classes.md) lists**; any other is an
error naming it. The only locales are the root and the English ones, which format as
`String.format` without a locale does; the default locale is `Locale.US`.

**A `MessageFormat` has no format types.** A pattern naming one (`{0,number}`, `{0,date}`,
`{0,choice,...}`) is refused by name when it is read, with an
`UnsupportedOperationException`. A `Double` of 2^63 or more prints the shortest digits
that read back as it, where a JDK sometimes prints others: `1e23` prints
`100,000,000,000,000,000,000,000`, a JDK `99,999,999,999,999,990,000,000`. A pattern
`applyPattern` refuses leaves the format as it was; a JDK's keeps a mix of the two.

**A `BigInteger` and a `BigDecimal` have no primes.** `isProbablePrime`,
`nextProbablePrime`, `probablePrime` and the constructors of random bits are refused by
name.

**A program holds the regions of the tz database it names.** Every program knows the zones
whose offset never changes: an offset (`+09:00`, `Z`), `UTC`, `GMT` or `UT` alone or with an
offset (`UTC+09:00`), and the fixed zones of the tz database (`Etc/GMT+5`, `Etc/UTC`,
`Zulu`). A region whose offset changes, such as `Europe/Paris`, is compiled into the program
with its rules, and only where the program names it. `ZoneId.of("Europe/Paris")`, the name
a constant, brings that one region, and so do `ZoneId.of("ECT", ZoneId.SHORT_IDS)` and
`ZoneRulesProvider.getRules("Europe/Paris", false)`; `ZoneId.of` of a name computed where
the program runs, `ZoneId.of` with a map of aliases other than `SHORT_IDS`, and
`getAvailableZoneIds()` bring all of them. `ZonedDateTime.parse` written in the program's
own files brings the regions a constant text names (`"2026-07-01T10:15+02:00[Europe/Paris]"`
brings Paris) and all of them for a text computed where the program runs; written in a
library of its source path, it brings none. A region the program does not hold is unknown
to it: `ZoneId.of` throws a `ZoneRulesException`, and `ZonedDateTime.parse` refuses a text
that names it in its brackets. One call of `ZoneId.getAvailableZoneIds()` anywhere in a
program makes it hold them all. The first region costs a `.wasm` about 57 KB, each further
one a few hundred bytes, all of them about 48 KB more. The rules are the tz database's as
rontolisp was built with them, not the host's: a later change of the database reaches a
program when a later rontolisp compiles it. `ZoneRulesProvider` is the provider of those
rules alone: `registerProvider` and a provider's own methods are not there.

`ZoneId.systemDefault()` in a program that holds a region is the zone the host names where
the program knows it: the one the environment variable `TZ` names (a leading colon
dropped), or where `TZ` is unset or empty the one the link `/etc/localtime` leads to, which
a `.wasm` reads where the host grants it `/etc`. Otherwise it is a `ZoneOffset`, the offset
the host's zone has when it is called: `LocalDate.now()` is the host's date, and a date in
another season is then read at today's offset. A `.wasm` learns the host's offset as
[`decode-universal-time`](../guides/clock-and-random.md) does, and answers UTC where it
cannot.

**A `java.util.Date` names its zone by the offset.** `toString` writes `UTC` where the host
is at UTC and `GMT+09:00` elsewhere, where a JDK writes the zone's abbreviation (`JST`,
`EST`). Before a region's first rule the offset is the host's local mean time
(`GMT-04:56` in New York before 1883), where a JDK reads the region's first standard
offset. `clone` and the members deprecated since JDK 1.1 (`getYear`, `getHours`) are
refused by name.

**A formatter writes the words and the weeks of the locales `Locale` has.** The months,
the days, the eras, the quarters, the halves and the periods of a day, the localized forms
(`ofLocalizedDate` and its kind) and the names of zones in words are those a JDK writes for
the root locale, `ENGLISH`, `US`, `UK` and `CANADA`; a week starts on Monday and its first
holds four days of the year in `UK`, on Sunday and one day in the others (`WeekFields.of`);
`ofPattern` without a locale, the localized forms and the ISO forms take `US`, the default
locale, and `localizedBy` takes its locale and the ISO calendar, as none of these locales
names another calendar, a zone or other digits. A zone's name in words, like its id, is
read among the zones the program has (above): a name a JDK reads as a region the program
does not hold is read as the region it holds that writes the name, or not at all. A JDK
that has written a zone's names for `ENGLISH` writes some of that zone's names for `UK`
and `CANADA` as `ENGLISH` has them from then on (`Abidjan Daylight Time` where it wrote
`Abidjan Daylight Saving Time` for `CANADA`); the names here are the ones it writes before
that. The `DecimalStyle` of each of these locales is the standard one, and
`DecimalStyle.getAvailableLocales()` holds the five. `ofLocalizedPattern`, and
`appendLocalized` and `getLocalizedDateTimePattern` of a requested template, are refused by
name; the `Format` of `toFormat` has no `clone` (the subset has no `Cloneable`). In the text
of a formatter, `parseDefaulting` shows as `Default(field,value)`, where a JDK shows the
identity of an object. The fields that are no `ChronoField` -- of weeks, quarters, Julian days or
the program's own -- are resolved in the order they were read, where a JDK's order changes
from run to run: it shows only where a text gives a day of the week twice (by its name and
by its number in the locale's week), or two such fields make two dates or two times.

**`java.time` has the ISO calendar alone.** `Chronology.of` knows no other calendar and
`getAvailableChronologies()` holds ISO's alone; `Duration.dividedBy` is refused by name. A
calendar of the program's own extends `AbstractChronology` and makes its dates of a map of
fields as a JDK's does, but its date-times, zoned date-times and periods (`localDateTime`,
`zonedDateTime`, `period`) are refused with an `UnsupportedOperationException`, and a
formatter reads and writes in ISO's calendar whatever `withChronology` names. The message of a text whose fields make no value lists them in one order
(`{Year=2020, MonthOfYear=13}`), and of several fields of a time with no hour out of range
the same one is refused first; a JDK's order changes from run to run. A `ValueRange`, a
`ZoneRules`, a `ZoneOffsetTransition` and a `ZoneOffsetTransitionRule` have hash codes of
their own, and `IsoChronology` and `InstantSource.system()` hash as objects, as a JDK's do by
the identity of a class or an object.

**`Normalizer` reads the Unicode data of JDK 25.** A character whose data a later JDK's
Unicode adds or changes is normalized as JDK 25 does it. `normalize` of a text already in
the form answers that string itself, where a JDK sometimes answers an equal copy.

**Two older classes are the newer ones.** A `StringBuffer` is a `StringBuilder` and
`getClass().getName()` says so; a `Vector` is an `ArrayList` to `instanceof`.

**The standard streams are streams of characters.** `System.in` reads the characters of
the host's standard input as the bytes of their UTF-8, so input that is no text does not
come through as its bytes; a byte written to `System.out` or `System.err` is a byte of
UTF-8, and its character is written once its last byte is. A read of several bytes from
`System.in` waits for the first and takes what is at hand after it; a `.wasm` cannot ask
its host what is at hand and reads to the end of the line. `System.out` and `System.err`
cannot be replaced (`System.setOut`) and stay open after `close()`.

**Six charsets.** `UTF-8`, which is the default, `ISO-8859-1`, `US-ASCII`, `UTF-16`,
`UTF-16BE` and `UTF-16LE`; any other name is an `UnsupportedEncodingException` or an
`UnsupportedCharsetException`. Bytes that are no UTF-8 or no UTF-16 decode to U+FFFD, as
many as a JDK 25 writes. After its first print, `charset()` of a `PrintStream` in `UTF-16`
answers another object of the same name.

**A class object says what a class is and reaches nothing in it.** A `Class` answers its
names, the class above it, whether a value is an instance, a cast and an enum's constants.
It has no reflection: a class by name (`Class.forName`), the members, constructors and
annotations of a class and `getInterfaces()` are refused by name. The classes above a
JDK class are the subset's: `StringBuilder.class.getSuperclass()` is `Object`, where
a JDK has a class of its own between the two, and `isAssignableFrom` knows the interfaces
the subset's classes declare (a `String` is a `Comparable` and a `CharSequence`).
`getClass()` of a lambda is a run-time error. `synchronized (Name.class)` holds the monitor
the `static synchronized` methods of the class hold, which is not the monitor of the class
object held in a variable.

**A resource is found when the program is compiled.** The bytes the program reads are
those of the file at the compile. A name computed when the program runs finds no source
file (`.java`), no class file and no file or directory whose name starts with a dot, where
the launcher finds them, and in the playground none of the uploads, which cannot be listed;
a program whose loaders may find more than it carries is refused when it is compiled
([Resources](index.md#resources)). A directory is no resource: `getResourceAsStream` of
one answers `null`, where the launcher answers a stream. The stream is a
`ByteArrayInputStream`. A JDK class finds no resource, not even the JDK's own, and the
platform loader none. A resource's URL names the file at the compile and reads the bytes
compiled into the output; any other `URL` has nothing to read: `openStream` throws a
`FileNotFoundException`, a URL made again from a resource's text included. A service a
`Class<?>` names is one whose class the program has. A bundle class `ResourceBundle.getBundle`
finds by a constant base name is a class of its own file or a member of it; by a base name
computed when the program runs, one the program reaches otherwise.
`ResourceBundle.Control`, `Properties.loadFromXML` and `storeToXML` are refused by name. The line of the date `Properties.store` writes is a `java.util.Date`'s text
(above).

**A stream runs in order on the thread that asks for its result.** `parallel()` and
`parallelStream()` are kept (`isParallel()` answers `true`) and change nothing else: the
elements go through one at a time in the source's order, and a collector's `combiner` and
the third argument of `collect` and `reduce` are never called. `String.chars()` and
`codePoints()` are one stream, of code points. A stream made inside `flatMap` is closed
once its last element is taken or the stream stops asking (`findFirst`, `limit`, a match,
`takeWhile`), as under a JDK; it is not closed where a later stage throws, where a JDK
closes it. `iterator()` and `Stream.concat` take the elements of such a stream one at a
time, where a JDK takes all of them before it hands on the first. A collector's
`finisher` is always called, whatever its `characteristics()` say, and a collector made
of a collector of the program's own class (`mapping`, `groupingBy`) reports none of that
one's characteristics. A spliterator of the subset's classes never splits: `trySplit` answers
`null`. A collection changed while its spliterator is read throws
`ConcurrentModificationException` before the next element, where a JDK hands that
element on first.

**`mapMulti` and a gatherer hold what they give for one element until the later stages
ask for it.** The function of `mapMulti` runs to its end for an element before a later
stage sees the first of what it gave; under a JDK the later stages see each one as it is
given, including those past a `limit`. A gatherer's `push` answers `true`, and
`isRejecting()` `false`, until the stream has stopped asking; under a JDK they turn once
the later stages have enough, in the middle of an element. So a gatherer that pushes
until `push` answers `false` never returns here. The elements a stream hands on are the
same. `Gatherers.mapConcurrent` hands a result on when as many mappings as its limit are
under way or the stream ends; a JDK may hand on a finished one sooner.

**A stream's `spliterator()` reports an order and the size.** Its characteristics are
`ORDERED`, with `SIZED` and `SUBSIZED` where the stream knows its count, and its estimate
is that count or `Long.MAX_VALUE`. A JDK also reports what it keeps track of through the
stages: `DISTINCT`, `SORTED`, a source's `NONNULL`, `IMMUTABLE` and `CONCURRENT`, no
`ORDERED` for a `HashSet`, a source's own estimate behind a `filter`; and its `sorted()`
takes no element ahead where the stream is sorted already (`IntStream.range`), where
here it does. `StreamSupport.stream(s, false).spliterator()` is a spliterator of its
own, not `s`.

**What a program asks of its host has fixed answers.** `Runtime.availableProcessors()` is
1. `System.getProperty` answers `line.separator`, `file.separator`, `path.separator` and
the encodings (`file.encoding`, `native.encoding`, `stdin.encoding`, `stdout.encoding`,
`stderr.encoding`, each `UTF-8`); any other key is `null`. `System.gc()` does nothing.
`System.getenv(name)` answers the host's variable -- a `.wasm` has those its host hands it
(`wasmtime run --env NAME=value`) -- and `getenv()` of every variable is not there.

## Threads

**The threads of a `.wasm` take turns.** A `.wasm` has one stack, so a thread there runs
until it waits -- in `wait`, `join` or `sleep`, at `yield`, at a monitor another thread
holds -- and a thread that can go on runs then. A started thread first runs when the thread
that started it waits. Every such execution is one the language allows, which promises no
thread a turn. Four kinds of program show it.

- A loop that never waits keeps its turn. The turn is given up once in about a thousand
  rounds by a loop that reads what another thread writes the way the language shares it: a
  `volatile` field, an atomic, a `synchronized` method or block, the interrupt. A loop that
  reads a plain field instead, one that asks an object through an interface inside its body
  (`while (true) { if (future.isDone()) break; }`), and one turning while its thread holds a
  monitor let no other thread run until they end. `Thread.yield()` or a `sleep` in the loop
  gives the turn.
- Threads that all wait for one another end the program with a message on standard error
  and the status 1, where a JDK waits for ever: two threads each holding the monitor the
  other enters, a pool that is never shut down, a `Timer` that is never cancelled. A
  program serving under `wasmtime serve` is not ended so: a request there may be waiting
  for what the next one brings ([The HTTP server](#the-http-server)).
- A `toString()` or a `hashCode()` that `String.format` or `printf` asks for, and the
  `toString()` of an exception being reported, cannot wait. One that has to -- it is
  `synchronized` and a sleeping thread holds its monitor -- ends the program the same way.
- A Java file with no `main` that a Common Lisp program loads is refused when it starts a
  thread or a server.

**In a browser a program that starts threads runs as a `.wasm`.** **Run** on these pages
and the playground run a program on the interpreter, which in a browser has one stack and
cannot leave a thread waiting on it. A whole program that can start a thread is therefore
compiled in the page to a Preview 1 `.wasm` and run by the browser: its threads take turns
as above, and it has what a Preview 1 module has. One that also sends an HTTP request is
refused with that reason, where a program that starts no thread sends it. A buffer at the
playground's REPL stays on the interpreter and starts no thread: `start()` is refused there
with a message, and the playground's **Run source** runs the program whole.

**What is shared without a monitor or `volatile` is the host's.** On the interpreter and
the JVM a field two threads read and write with neither may be read stale, as the language
allows. `volatile`, `synchronized`, `Thread.start` and `join` order what they order in the
language.

**A pool is not in every respect a JDK's.** A task put into `getQueue()` by the program
waits for the next `execute`. A scheduled pool keeps its tasks in a table of its own, so
its `getQueue()` is empty. `CompletableFuture.defaultExecutor()` answers the pool the
stages run on, which is not the object `ForkJoinPool.commonPool()` answers. A
`LongAccumulator` and a `DoubleAccumulator` hold one value, where a JDK folds the values
of each thread apart and so asks for a function with the identity given. A `ForkJoinPool`
starts a thread whenever none is free for a task: its parallelism is a number it answers,
2 for `new ForkJoinPool()` and 1 for the common pool. An exception a task threw is thrown
again as it is; a JDK throws a copy holding it when another thread ran the task.

**A `ConcurrentHashMap` keeps the order a JDK 25 walks it in**, but for a table of 64
chains or more in which nine keys share one chain.

**A `ConcurrentSkipListMap`'s iterator reads the key above the last one it gave.** It gives
the entry it read ahead even when that was removed since, and never refuses a change; a key
put after such a removal between the two is given here, where a JDK's iterator may pass it
by. A function given to `compute`, `merge` and their kind runs once, under the map's monitor.

**An iterator of `ConcurrentLinkedQueue`, `ConcurrentLinkedDeque` and the blocking queues walks
the elements there were when it was made.** Its `remove` takes the element it gave out of the
queue; where the queue changed meanwhile and holds that element more than once, it takes the
first equal one (walking back, the last), where a JDK's takes the one it gave.

**A thread notified in `wait` may still be `WAITING`.** `getState` answers `BLOCKED` for a
thread waiting to enter a monitor another thread holds; one that was notified and waits
to take its monitor again may answer `WAITING` or `TIMED_WAITING` until it has it. An
uncaught-exception handler is asked for the threads a program starts, not for the thread
of `main`.

**A field updater is made of class literals and a constant name.**
`AtomicIntegerFieldUpdater.newUpdater(Node.class, "next")` is read where it is written, so
the classes are class literals and the name a constant: any other call is refused by name
when the program is compiled. A field the class calling it cannot read is refused there
too; a JDK throws when the call runs. What a JDK finds wrong with the field itself -- no
such field, another type, not `volatile`, `static` -- is its exception where the call
runs.

**A thread's number is rontolisp's own.** `threadId()` and the number in `toString()` count
the threads of the program from 1.

## The HTTP client

**A request is sent by the transport of the target**
([`rontolisp:fetch`](../guides/http-fetch.md)), so what belongs to a connection is the
transport's and has no member: a proxy, an authenticator, a cookie handler, an
`SSLContext`, WebSocket. `version(..)` is kept and answered, and no transport reads it; a
reply's `version()` is `HTTP_1_1` whatever was spoken. A request with no `User-Agent` of
its own carries rontolisp's.

**A time limit is the transport's, in milliseconds.** `timeout` and `connectTimeout` are
[`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md)'s `:first-byte-timeout` and
`:connect-timeout`: a part of a millisecond counts as one, and the body of a reply is not
timed. In a component the limit of `timeout` is counted once for the connection and
once more for the start of the reply. A component under `wasmtime serve` cannot time a
request: one with a time limit is an `IOException` there.
The `timeout()` of an `HttpRequest` of a program's own class is an
`UnsupportedOperationException` where the request is sent, in a program that gives no
builder a time limit.

**A request is sent as `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS` or `PATCH`.** Any
other method a builder took (`TRACE`, `get`) and `expectContinue(true)` are an
`UnsupportedOperationException` where the request is sent.

**A body is read whole.** `send` returns, and the future of `sendAsync` completes, once
the whole reply is in: the stream `BodyHandlers.ofInputStream()` answers reads a body
already there. `BodyPublishers.ofInputStream` is read to its end before the request is
sent. A `BodyHandler` is a function choosing among the subscribers of `BodySubscribers`,
and a body is published by `BodyPublishers`; a subscriber or a publisher of a program's
own class is an `UnsupportedOperationException` where it is used. `BodyHandlers.ofString()`
reads the charset a reply's `Content-Type` names when it is UTF-8, ISO-8859-1 or US-ASCII,
and UTF-8 otherwise; `ofString(charset)` reads any.

**A failure is an `IOException` with the transport's words**, a refused connection a
`ConnectException` on every backend; a JDK has more classes (`UnknownHostException`,
`SSLHandshakeException`).

**`sendAsync` completes its future on a thread of the common pool**, or of the client's
`executor`. In a `.wasm` the [threads take turns](#threads). In a component a thread
waiting for a reply leaves the others their turns, as one waiting in `sleep` does. In a
`--native` executable it lets the threads that can go on take a turn first, and then no
other thread goes on until the reply is in -- a thread asleep included, and a request of
the program's own server: a `main` asking its own server waits for ever there.

**A `URI`** is checked as a JDK checks one, with its messages, but for an address in
brackets, of which only the characters are checked. `hashCode` is not a JDK's number, and
`compareTo` orders by the text. The constructors of several parts, `normalize`,
`relativize` and `toASCIIString` are not there.

**A `URL`** is cut and refused as a JDK cuts one, with its messages, of the protocols a JDK
has a handler for. `equals` compares the host's text, where a JDK first asks for its
address. `hashCode` is not a JDK's number. `URL.of`, the constructor of a context and a
text, `openConnection` and `getContent` are not there.

## The HTTP server

**A server serves through the transport of the target**, the one a Clack application is
served through, so what belongs to a connection has no class or member: `HttpsServer` and
what configures it, `SimpleFileServer`; the backlog is not read. A program serves through
one server: binding a second while one is bound is an `UnsupportedOperationException`.

**Under `wasmtime serve` the host owns the port and the program's life.** The address of
`create` is not bound (`--addr` is) and `getAddress()` answers it as given. The host starts
an instance of the program for the requests it likes: wasmtime 49 hands one instance up to
128 requests, 16 of them at once, and drops it after a second with none in hand, so a
static field lasts as long as its instance and no longer. An instance's start is `main` up
to its end, or up to where no thread of the program can go on; from then on the program
runs while the host has a request of that instance in hand. A request stays in hand after
its response while a thread of the program waits for a time or for a reply that request
was the one to ask the host for, so a task scheduled by a handler runs once its handler
has answered; a thread asleep since before the first request goes on when a request
arrives. After `stop` an instance answers 503 until the host drops it. `wasi:http` tells
a handler neither its peer nor its port: `getRemoteAddress()` is `0.0.0.0:0`, and the port
of `getLocalAddress()` is 80.

**On one stack a request is a thread taking turns with the program's others**
([Threads](#threads)): in a `--native` executable and a component a handler may wait for a
thread, and `main` may wait for ever after `start()`. A thread that never waits keeps
every request from being answered.

- A `--native` executable takes a request where no thread can go on, so requests are
  answered one at a time unless a handler waits. With no thread left that can go on and
  no request to take, the program is refused as threads that all wait.
- Under `wasmtime serve` a handler that waits -- asleep, for a thread, for the reply to a
  request it sent, for what another request brings -- leaves its instance to the host,
  which hands that instance other requests meanwhile:

  ```console
  server.createContext("/held", exchange -> {
      RELEASED.await();                     // answered once /release has been asked
      exchange.sendResponseHeaders(204, -1);
  });
  server.createContext("/release", exchange -> {
      RELEASED.countDown();
      exchange.sendResponseHeaders(204, -1);
  });
  ```

  The request that ends another's wait has to reach the same instance, and which instance
  a request reaches is the host's choice: wasmtime 49 hands a request to an instance whose
  requests all wait, and may start another when several arrive at once. A request waiting
  for what never comes waits as long as the host lets it.

**A handler runs on the executor's thread, and the exchange ends with it.** With no
executor the thread that takes the requests runs the handlers one at a time, named
`HTTP-Dispatcher` as a JDK's. That thread hands an executor each request and waits until
its handler has returned, where a JDK's goes on to the next: an exchange still open when
its handler returns is closed there, and no thread can answer it later.

**A body in chunks goes out as it is written only under `wasmtime serve`.** After
`sendResponseHeaders(status, 0)` a chunk is on its way there when its write returns; the
interpreter, the JVM and a `--native` executable send the body whole, with its length, once
the handler has returned. A body of a length told ahead is sent when all of it is written.
The transport frames a response: a `Content-Length` or `Transfer-Encoding` field of the
handler's is dropped.

**Every request is answered.** A handler, a filter or an authenticator that throws, a
handler that returns having sent no head, and a body shorter than its length are a 500 with
no body, where a JDK closes the connection or leaves the request unanswered. An executor
that refuses a request is a 500 too. An exception `main` leaves uncaught after `start()`
ends the program.

**`stop` waits its delay as a JDK's does**, until the requests in hand are answered or the
delay has passed, and for all of it when a handler calls it. Requests still in hand after
a stop by a handler are answered before the server closes, where a JDK cuts them.

**The request is whole before the handler runs**: its body is read in, and a field sent
more than once is one value, the values joined by `, `. A write after the end of a body in
chunks is an `IOException` saying `stream closed`, where a JDK throws a class of its own.

**No name is looked up.** `InetAddress.getByName` and an `InetSocketAddress` of a host
take an address written in numbers and `localhost`; any other name is an
`UnknownHostException` from `getByName`, and an unresolved `InetSocketAddress`, whose name
the transport binds. `InetAddress.getLocalHost` and the reverse lookups are not there.

## What is refused

Each of these is an error naming the construct and its position.

- `assert`.
- Reflection: `Class.forName`, the members, constructors and annotations of a class
  (`getMethods`, `getDeclaredFields`, `newInstance`), `getInterfaces()`.
- A `switch` over a `long`, a `float`, a `double` or a `boolean`; a method reference and a
  field through a qualified `super` (`Interface.super::m`, `Outer.super.x`).
- A class extending `String`, `StringBuilder` or a box.
- Of `java.util.concurrent`, what [The JDK classes](classes.md) does not list: the queues
  ordered by priority or by delay. A `Timer`'s `schedule` of a `Date`. Thread groups.
- Any JDK class or member [The JDK classes](classes.md) does not list:
  `Scanner`, files
  (`File`, `FileReader`, `java.nio.file`), `Calendar`,
  reflection. Of `java.net`, everything but `URI`, `URL`, the addresses of a server and the
  [HTTP client](#the-http-client): sockets, `HttpURLConnection`.
