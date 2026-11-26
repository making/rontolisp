# Deviations

The subset is partial by design. Where a program that the subset accepts behaves
differently from the language specification is listed here, and it is the same on every
backend but for the [threads](#threads) of a `.wasm` and the half of a surrogate pair it
writes alone (with the strings, below).

## What differs

**A string's elements are code points.** `length`, `charAt` and `substring` count Unicode
code points where the language counts UTF-16 units, so they differ for a character outside
the Basic Multilingual Plane. `hashCode` is the specified number. `toCharArray` and `chars()`
answer the UTF-16 units, and where text is made of them -- `new String(char[])`, a
concatenation, the `toString` of a `StringBuilder` they were appended to -- a high surrogate
followed by a low one is the one character the two spell, so the text is the one the units
came from. A `StringBuilder` holds a unit appended alone as it came: its `length` and
`charAt` count the two halves of a pair appended one by one as two. `toUpperCase` and
`toLowerCase` change one character into one: `"ß".toUpperCase()` is `ß`, where a JDK answers
`SS`. A surrogate with no partner in a string is its three bytes in UTF-8, where a JDK writes
`?`. Written to the standard output, such a surrogate is `?` and the two halves of a pair
printed one after the other are the character they spell, as under a JDK, but on wasm,
where each is its three bytes. `Character.getNumericValue` of a character that is a number
and no digit (`'Ⅷ'`, `'²'`, `'½'`) is -1, where a JDK answers 8, 2 and -2.

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
`ArrayStoreException`. So is a reference `Array.set` stores into an array of references, where
a JDK throws `IllegalArgumentException` (`array element type mismatch`).

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
`printStackTrace()` writes the same lines but that one, to standard error or to the
`PrintStream` or `PrintWriter` it is given. An exception keeps no frames:
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
`protected` field is reached from every class under its class (one of a JDK class from
every class), where the language allows an instance field only through a reference of that
class's own type, and a `protected` constructor by `new` from a class under its class,
where the language allows only `super(..)`. Of the warnings a compiler prints, only the
[unchecked ones](index.md#unchecked-warnings) of a call, a conversion, a cast and a
variable arity parameter and the one of a `catch` clause nothing reaches are.

**A record's `hashCode`** is computed from its components in order, `31 * h + hash`; the
language leaves the number unspecified. A `Locale`'s is a number of its own, as a JDK's
is.

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

**No reference is cleared by the collector.** A `WeakReference`, a `SoftReference` and a
`PhantomReference` keep their object until the program calls `clear()` or `enqueue()`, a
reference is put on its `ReferenceQueue` by `enqueue()` alone, a `WeakHashMap` keeps a key until
it is removed, and a `Cleaner`'s action runs where `clean()` is called, on every target. The
specification lets a collector keep what it could reclaim: a JDK started with a collector that
reclaims nothing (`-XX:+UseEpsilonGC`) answers the same. A program that reads a reference after
`System.gc()`, or a map's size after its keys were dropped, sees them kept where a JDK's default
collector has cleared them, and a cache keyed by objects the program drops grows with every key.
The null key of a `WeakHashMap` is walked first, where a JDK's place for it changes from run to
run.

**A `TreeMap` and a sort ask their comparator about other pairs than a JDK's.** The tree is
balanced another way and a sort of objects is a merge sort of rontolisp's own, so a comparator
that counts or prints its calls sees other pairs and another number of them; the order and
what the map answers are a JDK's. A map's `keySet()`, `values()`, `entrySet()` and
`descendingMap()` are a new view at each call, where a JDK's map answers the same object every
time: `m.values().equals(m.values())` is `false`.

**A few answers of the unmodifiable collections differ.** A list of `List.of` of one or two
elements refuses an index outside it with `ArrayIndexOutOfBoundsException: Index 2 out of
bounds for length 2`, a JDK's with `IndexOutOfBoundsException: Index: 2 Size: 2`. The iterator
of a list of `List.of`, of `Stream.toList()` and of a gatherer's window throws
`IllegalStateException` for a `remove()` before `next()`, a JDK's
`UnsupportedOperationException`. A set of `Set.of` and a map of `Map.of` answer a `null` asked
for (`contains(null)` is `false`), where a JDK's throw `NullPointerException`, and the views of
such a map refuse even a change that changes nothing. A list of `Arrays.asList` refuses a
removal with no message, a JDK's with `remove`. `List.of()`, `Set.of()`, `Map.of()` and
the list `toList()` makes of an empty stream are a new object at each call. An iterator of a list of `AbstractList`'s past its end throws `NoSuchElementException`
with no cause, where a JDK's has the list's `IndexOutOfBoundsException` as its cause.

**A part of a list (`subList`) and an iterator of a list refuse a change made behind them**,
as a JDK's do, through the count `AbstractList.modCount` which `ArrayList`, `LinkedList` and
`Vector` keep and a class of the program under `AbstractList` keeps up if it wants them to.
A part of a list that was changed some other way refuses its members with
`ConcurrentModificationException`, and so does an iterator of a list after a change made
behind it. A part of a `LinkedList` or of a program's list differs from a JDK's in a few
answers: a changed part refuses `subList` where a JDK's makes one, the iterator of such a
part throws `ConcurrentModificationException` for a `next()` past the end where a JDK's
throws `NoSuchElementException`, and that of a `LinkedList`'s part `IllegalStateException`
for a `remove()` before `next()` where a JDK's throws `ConcurrentModificationException`.
The class of a part is `java.util.AbstractList$SubList`, where a JDK's part of an
`ArrayList` is `java.util.ArrayList$SubList`. A part of a `CopyOnWriteArrayList` compares
the array it was made over, as a JDK's does, and its iterator walks the array there was.

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

**A `Scanner`'s match after a number is the token alone.** `match()` after `nextInt` ..
`nextBigDecimal`, and after their `hasNext`, has no group; a JDK's has the groups of its own
pattern of a number. The positions of `match()` and of `toString()` count from the start of
what the scanner holds: all of a `String`'s text, where a JDK's count from its buffer past the
first 1024 characters. A `Readable` is handed an empty `CharBuffer` of 1024 characters at each
read; a JDK hands it the scanner's buffer, positioned after what the scanner still holds.
Each read past the first asks for 1024 characters, where a JDK's asks for what its buffer
has room for: a `Scanner` of a `File` whose bytes stop being text past its first 1024
characters gives the tokens before another read than a JDK's does.

**A format refuses a specifier when it reaches it.** A JDK reads the whole control first, so a
`Formatter` it refuses has appended nothing; here it has appended what came before. The flags
and precisions `%d`, `%x`, `%o`, `%e`, `%f`, `%c`, `%b` and `%s` do not take (`%#d`, `%,x`,
`%.2d`, `%#s` of a value that is no `Formattable`) are not refused, and the relative index
`%<s` is not taken. A converted argument is appended as one text with its padding, where a
JDK appends each character of the padding by itself. `%tZ` and `%tc` of a `long` or a `Date`
name the zone as `Date.toString()` does. The default locale is `Locale.US`, and it is the
locale of the `Formatter` a `Formattable` given to `String.format` without a locale writes
itself into ([any other locale](#a-locale-of-any-language)).

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
`ZoneRulesProvider.getRules("Europe/Paris", false)`. A parameter of a static or a private
method that the method never assigns and that is passed to `ZoneId.of` brings the regions of
the constants every call of the method passes it (`zone("Europe/Paris")` of
`static ZoneId zone(String id) { return ZoneId.of(id); }` brings Paris). `ZoneId.of` of any
other name computed where the program runs (a call through a method reference among them),
`ZoneId.of` with a map of aliases other than `SHORT_IDS`, and `getAvailableZoneIds()` bring
all of them. `ZonedDateTime.parse` written in the program's
own files or in a library of its source path brings the regions a constant text names
(`"2026-07-01T10:15+02:00[Europe/Paris]"` brings Paris) and all of them for a text computed
where the program runs. A region the program does not hold is unknown
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
offset. The members deprecated since JDK 1.1 (`getYear`, `getHours`) are refused by
name.

**A formatter writes the words of the root locale and the English ones.** The months,
the days, the eras, the quarters, the halves and the periods of a day, the localized forms
(`ofLocalizedDate` and its kind), the patterns of a requested template (`ofLocalizedPattern`
and its kind) and the names of zones in words are those a JDK writes for the root locale,
`ENGLISH`, `US`, `UK` and `CANADA` ([any other locale](#a-locale-of-any-language)); a week
starts on Monday and its first holds four days of the year in `UK`, on Sunday and one day in
the others (`WeekFields.of`; [a week is its region's](#a-locale-of-any-language));
`ofPattern` without a locale, the localized forms, `ofLocalizedPattern` and the ISO forms
take `US`, the default locale, and `localizedBy` takes its locale and the ISO calendar, as none of these locales
names another calendar, a zone or other digits. A zone's name in words, like its id, is
read among the zones the program has (above): a name a JDK reads as a region the program
does not hold is read as the region it holds that writes the name, or not at all. A JDK
that has written a zone's names for `ENGLISH` writes some of that zone's names for `UK`
and `CANADA` as `ENGLISH` has them from then on (`Abidjan Daylight Time` where it wrote
`Abidjan Daylight Saving Time` for `CANADA`); the names here are the ones it writes before
that. A name a JDK writes as an offset (`GMT-05:00`) is the offset the zone has where the JDK
runs; here it is the one the tz database rontolisp was built with gives, as the rules are, so a
change of a zone's offsets that comes after the build reaches the names with a later build.
The `DecimalStyle` of each of these locales is the standard one, and
`DecimalStyle.getAvailableLocales()` holds the five. In the text
of a formatter, `parseDefaulting` shows as `Default(field,value)`, where a JDK shows the
identity of an object. The fields that are no `ChronoField` -- of weeks, quarters, Julian days or
the program's own -- are resolved in the order they were read, where a JDK's order changes
from run to run: it shows only where a text gives a day of the week twice (by its name and
by its number in the locale's week), or two such fields make two dates or two times.

**`java.time` has the ISO calendar alone.** `Chronology.of` knows no other calendar and
`getAvailableChronologies()` holds ISO's alone; `Duration.dividedBy` is refused by name. A
calendar of the program's own extends `AbstractChronology` and has its dates, date-times,
zoned date-times and periods as a JDK's does, and a formatter of `withChronology` writes and
reads in it. But the week-based years of `WeekFields` and the Julian days are read and set in
ISO's years, `appendChronologyId` and `appendChronologyText` are refused by name, and a
calendar whose type is one of the five others a JDK has words for (`buddhist`, `japanese`,
`roc`, `islamic-umalqura` and its kin) writes and reads its eras as numbers. A text giving an
instant and a zone beside a date or a time is read as the instant's date and time, where a JDK
refuses another one. The message of a text whose fields make no value lists them in one order
(`{Year=2020, MonthOfYear=13}`), and of several fields of a time with no hour out of range
the same one is refused first; a JDK's order changes from run to run. A `ValueRange`, a
`ZoneRules`, a `ZoneOffsetTransition` and a `ZoneOffsetTransitionRule` have hash codes of
their own, and `IsoChronology` and `InstantSource.system()` hash as objects, as a JDK's do by
the identity of a class or an object.

**`Normalizer` reads the Unicode data of JDK 25.** A character whose data a later JDK's
Unicode adds or changes is normalized as JDK 25 does it. `normalize` of a text already in
the form answers that string itself, where a JDK sometimes answers an equal copy.

**Two older classes are the newer ones.** A `StringBuffer` is a `StringBuilder` and
`getClass().getName()` says so; a `Vector` is an `ArrayList` to `instanceof`. An index outside a
`Vector` is refused with an `ArrayIndexOutOfBoundsException` in each member's words, as a JDK
does; a negative index words what a JDK words by its array's capacity (`Index -1 out of bounds
for length 10`) with the size, since the list keeps no capacity.

**The standard streams are streams of characters.** `System.in` reads the characters of
the host's standard input as the bytes of their UTF-8, so input that is no text does not
come through as its bytes; a byte written to `System.out` or `System.err` is a byte of
UTF-8, and its character is written once its last byte is. A read of several bytes from
`System.in` waits for the first and takes what is at hand after it; a `.wasm` cannot ask
its host what is at hand and reads to the end of the line. `System.out` and `System.err`
cannot be replaced (`System.setOut`) and stay open after `close()`. A closed `System.in`
is at its end, so a `Scanner` made over it again has no token, as under a JDK, whose read
throws `IOException` there.

**Six charsets.** `UTF-8`, which is the default, `ISO-8859-1`, `US-ASCII`, `UTF-16`,
`UTF-16BE` and `UTF-16LE`; any other name is an `UnsupportedEncodingException` or an
`UnsupportedCharsetException`. Bytes that are no UTF-8 or no UTF-16 decode to U+FFFD, as
many as a JDK 25 writes. After its first print, `charset()` of a `PrintStream` in `UTF-16`
answers another object of the same name.

**An annotation is read as a JDK reads it.** An annotation of a type declared with
`@Retention(RetentionPolicy.RUNTIME)`, written on a class, a field, a method, a constructor,
a parameter, a record component, an enum constant or a package (its `package-info.java` in
the program's source tree) of the program or of a library on the source path, is answered by
`getAnnotation`, `getAnnotations`, `getDeclaredAnnotations`, `getAnnotationsByType` and the
rest where its type's `@Target` names that kind of declaration or there is none. Repeated
annotations of a `@Repeatable` type are one annotation of its container, and a class carries
the annotations of an `@Inherited` type a class above it carries, ahead of its own. A class
of the JDK and its members carry what a JDK 25's do (`@FunctionalInterface` on `Runnable`,
`@Deprecated(since="9")` on `new Integer(int)`, the JDK's own `@jdk.internal.ValueBased`).
An element answers what is written or the type's default, an array a new one each time;
`equals` and `hashCode` are the ones `Annotation` specifies, and `toString` writes a JDK's
text, the elements with a default first and in a JDK's order -- two of those that share a
bucket of its hash map are in the order declared, where a JDK's is its VM's. Each lookup
answers a new object, where a JDK answers one object each time. In a program whose own code
reads no annotation and lists none -- one whose library reads annotations for its own use,
as a Servlet container finds its servlets -- `equals` and `hashCode` of an annotation are an
object's and `toString` is refused where the program runs. An annotation on a declaration
its type's `@Target` does not name is not refused, where javac refuses it, and is not
carried.

**A declaration's generic types are the ones it writes.** `getGenericSuperclass`,
`getGenericInterfaces`, `getTypeParameters`, `getGenericReturnType`,
`getGenericParameterTypes`, `getGenericExceptionTypes`, `getGenericType` and
`toGenericString` answer, of a class of the program, of a library's class reachability
metadata declares and of a class of the JDK (as a JDK 25's: its own, and its members' where
it answers its members), the types the declaration names: a `ParameterizedType`, a
`TypeVariable`, a `WildcardType` or a `GenericArrayType`, written, equal and hashed as a
JDK's, else the class object. A constructor of an inner class or of an enum whose generic
signature javac keeps leaves out the parameters the compiler adds, as a JDK's does. The
types are objects of the subset's own classes, a new one each lookup.
`getRecordComponents()` answers a record's components with their types, signatures,
accessors and annotations. A parameter (`getParameters()`) has the name javac keeps without
`-parameters` -- a record's canonical constructor's, else `arg` and its place -- and the
modifiers javac records of an inner class's instance around it, of an enum's name and
ordinal and of the parameter of an enum's `valueOf`.

**A program finds a class by a computed name only where an index lists it or reachability
metadata declares it.** No class file is there to look in, on any target. `Class.forName` of
a name computed where the program runs finds the classes an index of annotated classes lists
and those GraalVM reachability metadata declares
([Reflection from reachability metadata](index.md#reflection-from-reachability-metadata)), and
is a `ClassNotFoundException` for any other, as under GraalVM's `native-image`; of a constant
name it finds the class the name names. The index is a resource a program asks its class loader for by a constant
name, `META-INF/annotations/<the binary name of an annotation type>`: beside any file of
that name, rontolisp answers one listing the classes of the program's source tree and one
for each root of the source path, a binary name a line -- every class there that carries
the annotation, read while the program is compiled, a file the program does not name
included. That is how a library finds annotated classes with the code it runs under a
JDK, where it reads the class path ([A Servlet application](servlet.md#annotations)).
A system property the subset does not have (`java.class.path`) is `null`, and what a
library does under `if (System.getProperty(..) != null)` is not compiled.

**A class object says what a class is, and answers the members of the program's classes and
of the JDK's.** A `Class` answers its names, its modifiers, the class above it, the interfaces it
declares, whether a value is an instance, a cast, an enum's constants and its members. The members
a class of the program declares (`getDeclaredMethods`, `getDeclaredFields`,
`getDeclaredConstructors`) are the ones its source declares and the ones the language declares with
them (an enum's `values` and `valueOf` and its constructor taking the constant's name and ordinal
first, a record's accessors, `toString`, `hashCode` and `equals` and its canonical constructor, a
default constructor, an inner class's constructor taking the instance around it first), each with
the types, modifiers and text a JDK answers. A JDK's order is its own, and its arrays also hold the
members its compiler adds (a lambda's body, a bridge, an enum's `$values`); where a JDK lists a
bridge of a class of the program `getMethods` lists the method it stands for, and a local or an
anonymous class's constructor lists no local it reads. A lookup by name and types, a call
(`invoke`, `newInstance`) and a field's `get`, `set` and typed reads and writes are a JDK's:
dispatched, each argument unboxed and widened to its parameter, the answer boxed, refused with a
JDK's exceptions and words -- the object `null` or of another class, the count or a type of the
arguments, a final field, the class calling a member it may not use while the program did not ask
`setAccessible(true)`, a protected member of another package included (the class calling has to be
under the member's class, and the object under the class calling; a protected constructor never).
A name is a value the program computes, so every member of each class of the program whose class
object it has is in the compiled program, called only where a call can reach it: a call giving
arguments where one in the program does.
A class of the JDK answers its public and protected members, as a JDK 25's reflection lists them
(names, types, modifiers, exceptions, bridges, `getMethods`, `getFields`, the class's
modifiers), where the program holds its class object -- a class literal, the class of a value
(`getClass()`), a class found by its name -- or reads it above one (`getSuperclass`,
`getInterfaces`). Its private and package members are not there, as no program may use them, and its
members are listed in the order of their names. An interface the program reaches only through
`getInterfaces` answers none of its members by a name (`getDeclaredMethod(name)`,
`getDeclaredField(name)` are refused where the program runs, `UnsupportedOperationException`), as
the program holds no class object of it itself: a template engine's walk over the interfaces of a
value's class finds no default method there (jmustache's `{{stream}}` of a list). A class object the program holds only as the type
of a member reflection found answers no members, nor do `Class` and the classes of
`java.lang.reflect`, nor a class of a library on the source path that no reachability metadata
declares, nor a class of the subset's own under a JDK's package (a `List.of(..)`'s): asking is
refused where the program runs (`UnsupportedOperationException`); `getDeclaredMethods` of a
library's class answers its methods by name, and `getDeclaredConstructor()` its constructor of no
parameter. A library's class the metadata declares answers its members as a class of the program
does, and runs those the metadata declares; using any other is refused where the program runs
(`UnsupportedOperationException`: it runs only by reflection), as `native-image` refuses it. An
entry's `typeReached` condition counts where the program reaches its class anywhere, where
`native-image` asks it when the program runs. A member of a class of the
JDK runs by reflection where the compiled program holds what it runs: a method the program calls,
an instance method whose every body a call of it can run is in the program, a constructor the
program makes with, a constant, a static field of a class the program initializes, an instance
field. A lookup naming a member by constants (`String.class.getMethod("length")`) puts it in the
program, as GraalVM's native image keeps what such a lookup names, and so does an entry of
reachability metadata naming it. Any other use is refused where
the program runs (`UnsupportedOperationException`: it runs only by reflection), as is a member the
subset's library lacks; `equals`, `hashCode` and `toString` of `Object`, and `name`, `ordinal` and
`compareTo` of `Enum`, run wherever they are found. So jmustache finds `{{length}}` of a `String`,
`{{size}}` of a list and `{{name}}` of an enum constant as a JDK does, and a template naming a member
the program calls nowhere (`{{blank}}`) fails with jmustache's own exception. `setAccessible(true)`
of a member of a class of the JDK that is not public, or of a class that is not, is an
`InaccessibleObjectException` in a JDK's words (the identity of the unnamed module is the subset's
own number); `trySetAccessible()` answers `false`. Refused where the program runs as well: calling
`getClass` through a `Method`, `newInstance` of a local or an anonymous class, and in a program
asking no more of a method than its name and its class, any other member of one
`getDeclaredMethods` answers. A final field set by reflection is read again where the program reads
it, also where it is a constant, which a JDK's compiler writes into each read. `getInterfaces()` of
a JDK class answers those of the subset's class that the program has. The classes above a JDK class
are the subset's: `StringBuilder.class.getSuperclass()` is `Object`, where a JDK has a class of its
own between the two, and `isAssignableFrom` knows the interfaces the subset's classes declare (a
`String` is a `Comparable` and a `CharSequence`). `o.getClass().isArray()` answers whether `o` is
an array, for a lambda too; `getClass()` of a lambda read otherwise is a run-time error.
`synchronized (Name.class)` holds the monitor the `static synchronized` methods of the class hold,
which is not the monitor of the class object held in a variable.

**A method handle is a member the class objects answer, used as its type says.** A lookup
(`MethodHandles.lookup()`, with the access of the class calling it, or `publicLookup()`) finds a
method by its name, parameter types and return type in the class, the classes above it and its
interfaces, and a constructor by its parameter types, among the members reflection answers
(above), and decides there whether its class may use it. `invokeExact` and `invoke` have the type
a compiler gives the call: the static types of the arguments (`Void` for `null`), `void` for a
statement, a cast's type, else `Object`. `invokeExact` refuses a handle of another type; `invoke`
converts each argument and the result as `asType` does, and a handle of a variable arity puts the
arguments past its fixed ones into an array. The refusals are a JDK's, in its words, but for a
`NullPointerException` the JDK words after its own code. A member of a class of the JDK runs where
it would by reflection, and a lookup naming it by constants (a class literal, a name and
`MethodType.methodType` of class literals) puts it in the program. Two method types of the same
types are equal, not one object, which the specification allows.

**A resource is found when the program is compiled.** The bytes the program reads are
those of the file at the compile. A name computed when the program runs finds no source
file (`.java`), no class file and no file or directory whose name starts with a dot, where
the launcher finds them;
a program whose loaders may find more than it carries is refused when it is compiled
([Resources](index.md#resources)). The names a directory's stream reads are in the order of
their characters, where the launcher sorts them as the host's default locale does. The stream is a
`ByteArrayInputStream`. A JDK class finds no resource, not even the JDK's own, and the
platform loader none. A resource's URL names the file at the compile and reads the bytes
compiled into the output; any other `URL` has nothing to read: `openStream` throws a
`FileNotFoundException`, a URL made again from a resource's text included. A service a
`Class<?>` names is one whose class the program has. A bundle class `ResourceBundle.getBundle`
finds by a constant base name is a class of its own file or a member of it.
A `Control`'s time to live keeps a bundle for good unless it is `TTL_DONT_CACHE`:
`needsReload` is never asked. `Properties.loadFromXML` refuses a document with the words a
JDK's `InvalidPropertiesFormatException` has, but with no cause. The line of the date
`Properties.store` writes is a `java.util.Date`'s text (above).

**A stream runs in order on the thread that asks for its result.** `parallel()` and
`parallelStream()` are kept (`isParallel()` answers `true`) and change nothing else: the
elements go through one at a time in the source's order, and a collector's `combiner` and
the third argument of `collect` and `reduce` are never called. A stream made inside
`flatMap` is closed once its last element is taken or the stream stops asking (`findFirst`,
`limit`, a match, `takeWhile`), as under a JDK; it is not closed where a later stage throws,
where a JDK closes it. `iterator()` and `Stream.concat` take the elements of such a stream
one at a time, where a JDK takes all of them before it hands on the first. A collector's
`finisher` is always called, whatever its `characteristics()` say, and a collector made of a
collector of the program's own class (`mapping`, `groupingBy`) reports none of that one's
characteristics. A spliterator of the subset's classes never splits: `trySplit` answers
`null`. A collection changed while its spliterator is read throws
`ConcurrentModificationException` before the next element, where a JDK hands that element on
first.

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
`stderr.encoding`, each `UTF-8`); `user.dir` named by a constant is the working directory
([Files](#files)); any other key is `null`, `java.io.tmpdir` too. `System.gc()` does nothing.
`System.getenv(name)` answers the host's variable -- a `.wasm` has those its host hands it
(`wasmtime run --env NAME=value`) -- and `getenv()` of every variable is not there.

**Assertions are never enabled.** An `assert` statement is checked where the program is
compiled and evaluates neither of its expressions, as under a JDK started without `-ea`.

## A locale of any language

**A locale of any language is made, and writes as the root or an English locale does, or
is refused.** `Locale.forLanguageTag`, `Locale.of`, the constructors and the builder make a
locale of any language, script, country, variant and extensions, as a JDK does. What a
locale writes -- the numbers and words of `String.format`, `printf` and `Formatter`, a
`java.time` formatter's words and patterns, `DecimalStyle.of`, a field's or a calendar's
name, the numbers a `Scanner` reads and a `MessageFormat` writes -- is the root's for
`ROOT` and for a language a JDK has no data of (`xx`, `tlh`), the English ones' for
`ENGLISH`, `US`, `UK` and `CANADA`. Any other locale (`fr`, `en-AU`, one with a script, a
variant or a Unicode keyword such as `-u-nu-arab`) is refused by name, with an
`UnsupportedOperationException`, where the program would need that locale's own data:
`String.format(Locale.FRANCE, "%s", s)` and `DateTimeFormatter.ofPattern("uuuu-MM-dd",
Locale.FRANCE)` write, `String.format(Locale.FRANCE, "%,d", n)` and
`ofPattern("MMMM", Locale.FRANCE)` are refused. The conversions of a format that need it
are `%d`, `%e`, `%f`, `%g` and `%t`, and in Turkish, Azerbaijani and Lithuanian the
upper-case ones.

**A week is its region's** (`WeekFields.of`, a formatter's week letters): the country's,
or the one a `-u-rg-` keyword names, its first day the one a `-u-fw-` keyword names; a
locale with no country starts on Sunday with a day, as under a JDK. **`toUpperCase` and
`toLowerCase` of a locale** change letters as the root does but in Turkish and
Azerbaijani (`I` and `ı`, `İ` and `i`) and Lithuanian (`i` keeps its dot under an accent);
where that needs a letter's combining class -- a dot above after other marks -- it is
refused. **`ResourceBundle`** looks a bundle up through the candidates of any locale, as
the default `Control` lists them. `Chronology.ofLocale` and `localizedBy` take ISO's
calendar; a calendar a `-u-ca-` keyword names (`japanese`), digits (`-u-nu-`), a region
(`-u-rg-`) or a zone (`-u-tz-`) is refused. `DecimalStyle.getAvailableLocales()` holds the
five locales.

## Files

**A file is the host's, and a `.wasm` has the directories its host preopens.** A relative
path is resolved against the working directory, which a `.wasm` takes to be its first
preopen: where that one is named relatively (`wasmtime run --dir .`), `getAbsolutePath`,
`getCanonicalPath`, `toAbsolutePath` and `toRealPath` answer a relative path, `toURI` and
`toUri` a URI of `file:` and that path, and `System.getProperty("user.dir")` null. A
temporary file is made in `/tmp` where no directory is given, which a `.wasm` reaches only
where its host preopens it. A `.wasm` follows, reads and makes no link whose target is an
absolute path: there such a link leads nowhere (`exists` is false, its attributes an
`AccessDeniedException`), and `readSymbolicLink` of it, `createSymbolicLink` of an absolute
target and `copy` of such a link with `NOFOLLOW_LINKS` are an `AccessDeniedException`.

**What a file is, its size and its times are the host's `stat`, on every target, and a
`.wasm` has a part of it.** WASI's `stat` holds no time a file was made, so there
`creationTime` is the time of last writing, a JDK's answer on a file system that keeps
none. A `.wasm` writes no time before 1970: such a time is written as
`1970-01-01T00:00:00Z`, and on Preview 1 read as it too. `fileKey()` is null on every
target. The attributes named in a text are those of the `basic` view and, on the
interpreter and the JVM, of the `posix`, `unix` and `owner` views; a JDK on a Unix host has
`dos` and `user` too, which are an `UnsupportedOperationException` here (`View 'dos' not
available`) and which `supportedFileAttributeViews` does not name. `getFileAttributeView`
answers the basic view, the posix and the owner views where those are there, and null for
any other.

**Permission bits, owners and the space of a disk are the host's on the interpreter and the
JVM, and a `.wasm` has none of them.** There what may be done with a path is the host's
`access(2)`, the bits are read, written, given to a file made and to a copy (less the
umask, as a JDK makes one), a temporary file is `rw-------` and a temporary directory
`rwx------`, an owner and a group are named as the host's user database names them, and the
space is the partition's. WASI has no permission bits, no owners, no `access` and no
`statvfs`, so a `.wasm` answers as a JDK does on a file system with no POSIX view, or where
it cannot tell: `getOwner`, `setOwner` and `readAttributes` of `PosixFileAttributes` are an
`UnsupportedOperationException`, the `posix`, `unix` and `owner` views are not available by
name, `getFileAttributeView` answers null for them, `supportedFileAttributeViews` names
`basic` alone, `getPosixFilePermissions` and
`setPosixFilePermissions` are an `UnsupportedOperationException`, a `posix:permissions`
attribute to make a file with is refused (`'posix:permissions' not supported as initial
attribute`), a file and a copy have the host's bits, `canExecute` and `isExecutable` are
false, `canWrite` and `isWritable` of a directory false, `canRead` and `canWrite` of a file
whether it opens, the setters (`setReadable` ..) false, and `getTotalSpace`,
`getFreeSpace` and `getUsableSpace` 0.

**Some members do otherwise, and some are not there.** A shorter
`RandomAccessFile.setLength` writes the bytes it keeps again. `StandardOpenOption.SYNC`,
`DSYNC` and `SPARSE` change nothing. Every `Path` is of one class of the subset's own (what
`getClass()` answers), and so is the one `FileSystem`. A glob whose bracket expression is
empty (`[]`, `[!]`) is refused with words of the subset's own. Not there, refused by name:
`Path.register`; `Files.createLink`; `FileSystem.getUserPrincipalLookupService`, the
`dos` and `user` views (`DosFileAttributes`, `UserDefinedFileAttributeView` ..);
`FileDescriptor` and the channels.
`FileSystems.newFileSystem` of a file finds no provider (`ProviderNotFoundException`): an
archive is not read as a file system.

**In the browser a program that reaches a file, or asks for `user.dir`, is refused before
it runs**: the page has no file system. One that only computes paths runs.

## Buffers

**A buffer's class is the subset's.** `getClass().getName()` of a buffer is the class it is
made by (`java.nio.ByteBuffer`, `java.nio.IntBuffer`), and of a view of a byte buffer one of
the subset's own (`java.nio.ByteBufferAsIntBuffer`), where a JDK names each kind
(`java.nio.HeapByteBuffer`, `java.nio.DirectByteBuffer`, `java.nio.ByteBufferAsIntBufferB`).
`toString()` names the kind as a JDK's does. A direct buffer is an array, which it does not
hand out. A charset's coder is of a class of the subset's own as well
(`java.nio.charset.StandardCharsetEncoder`).

**The native order is little-endian on every target.** `ByteOrder.nativeOrder()`, and the
`order()` of a buffer that is no view of a byte buffer, is `LITTLE_ENDIAN`, as a wasm module's
memory is; a JDK on a big-endian machine answers `BIG_ENDIAN`.

**A `CharBuffer` wraps a text as it is.** A `CharSequence` that is no `String` is read
through `charAt`, so a character outside the Basic Multilingual Plane is one element of it,
where a JDK's is two.

**`Charset.encode` writes the bytes `getBytes` writes**: a surrogate with no partner is its
three bytes in UTF-8 ([What differs](#what-differs)), where a JDK writes `?`. A
`CharsetEncoder` counts it malformed and writes its replacement, `?`, as a JDK's does.

**A direct buffer starts at an address every unit size divides.** `alignmentOffset` and
`alignedSlice` of a direct buffer answer as if it did, so they answer a JDK's for a unit of up
to 16 bytes; for a larger unit a JDK's answer depends on where the buffer was allocated. A heap
buffer answers for a unit of one byte only and refuses a larger one, as a JDK's does.

**Not there**, refused by name: the channels.

## Threads

**The threads of a `.wasm` take turns.** A `.wasm` has one stack, so a thread there runs
until it waits -- in `wait`, `join` or `sleep`, at `yield`, at a monitor another thread
holds -- and a thread that can go on runs then. A started thread first runs when the thread
that started it waits. Every such execution is one the language allows, which promises no
thread a turn. Four kinds of program show it.

- A loop that never waits keeps its turn. The turn is given up once in about a thousand
  rounds by a loop that reads what another thread writes the way the language shares it: a
  `volatile` field, an atomic, a `synchronized` method or block, the interrupt. A loop its
  own progress ends -- over an iterator it takes the elements of, over indexes it raises
  below a bound -- and one whose test and exits read only locals it computes keep the turn
  whatever their body calls; so do a loop that reads a plain field instead, one that asks
  an object through an interface inside its body
  (`while (true) { if (future.isDone()) break; }`), one whose only such read is in making
  the exception a `throw` throws (an `SQLException`'s constructor reads `DriverManager`'s log
  writer), and one turning while its thread holds a monitor. These let no other thread run until they end. `Thread.yield()` or a `sleep` in
  the loop gives the turn.
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
the classes are class literals, with or without casts around them
(`(Class<Node>) Node.class`), and the name a constant: any other call is refused by name
when the program is compiled. A field the class calling it cannot read is refused there
too; a JDK throws when the call runs. What a JDK finds wrong with the field itself -- no
such field, another type, not `volatile`, `static` -- is its exception where the call
runs.

**A thread's number is rontolisp's own.** `threadId()` and the number in `toString()` count
the threads of the program from 1.

**A shutdown hook that calls `System.exit` ends the program.** It writes a line on standard
error and ends with the status 1, where a JDK's hook waits there for ever and the program with
it. The hooks are started in the order they were added, which a JDK does not promise.

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
an instance of the program for the requests it likes: wasmtime 51 hands one instance up to
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
  a request reaches is the host's choice: wasmtime 51 hands a request to an instance whose
  requests all wait, and may start another when several arrive at once. A request waiting
  for what never comes waits as long as the host lets it.

**A `--no-wasi` reactor is called for one request at a time, and no time passes within a
call, unless the build says `--host-timer`.** Its clock is the one its host sets before
each call. The call answers once the request's exchange is answered, and a handler may wait
for a thread of the program until then; a wait nothing can end within the call before the
exchange is answered -- a sleep, a time limit, what another request brings -- ends the
instance, as `System.exit` and an exception `main` leaves uncaught do: the host's call
fails, and the generated Worker answers `500` and starts the program anew for the next
request. An exchange no thread of the call answers, and that no thread waits for a time to
answer, is answered `500` at the end of the call. Under `--host-timer` the host waits for
the program, through JSPI: a wait for a time waits in the host's timer, and a request whose
threads cannot go on waits in the host, which calls the program for other requests
meanwhile -- so a request can wait for what another brings, and an exchange nothing answers
stays open, as a JDK's. The threads take their turns while the host has a request in hand:
one whose wait ends after the last answer goes on with the next request. What the
program prints is discarded unless the build says `--host-output`, which makes it the
Worker's log, the report of what ended the instance included. The host's `Request` tells a handler neither its peer nor its
port, as `wasi:http`, and takes no `TRACE` request. Its `Headers` join the values of a
response field set more than once with `, ` (`Set-Cookie` apart).

**A handler runs on the executor's thread, and an exchange outlives it as a JDK's does.**
With no executor the thread that takes the requests runs the handlers one at a time, named
`HTTP-Dispatcher` as a JDK's; else it hands the executor each request and goes on to the
next. An exchange its handler returns from unanswered is answered by whichever thread
answers it later -- another request's handler, a thread of the program's -- and holds no
thread meanwhile; one nothing answers stays open until the server stops.

**A body in chunks goes out as it is written only under `wasmtime serve`.** After
`sendResponseHeaders(status, 0)` a chunk is on its way there when its write returns; the
interpreter, the JVM and a `--native` executable send the body whole, with its length, once
it ended. A body of a length told ahead is sent when all of it is written. The transport
frames a response: a `Content-Length` or `Transfer-Encoding` field of the handler's is
dropped.

**A request that fails is answered.** A handler, a filter or an authenticator that throws,
an exchange closed having sent no head, and a body shorter than its length closed are a 500
with no body, where a JDK closes the connection. An executor that refuses a request is a 500
too. An exception `main` leaves uncaught after `start()` ends the program.

**A handler that throws after `sendResponseHeaders(status, 0)` cuts its body short under
`wasmtime serve`**, as a JDK does: the connection closes with no last chunk, and with
nothing sent at all where the head had not left yet. The interpreter, the JVM, a
`--native` executable and a Worker have sent nothing by then and answer the 500 of a failed
application, `Internal Server Error`, and print the exception on the standard error (a
Worker: its log).

**`stop` waits its delay as a JDK's does**, until the requests in hand are answered or the
delay has passed, and for all of it when a handler calls it before answering its own; then
the exchanges still in hand are cut.

**The request is whole before the handler runs**: its body is read in, and a field sent
more than once is one value, the values joined by `, `. A write after the end of a body in
chunks is an `IOException` saying `stream closed`, where a JDK throws a class of its own.

**No name is looked up.** `InetAddress.getByName` and an `InetSocketAddress` of a host
take an address written in numbers and `localhost`; any other name is an
`UnknownHostException` from `getByName`, and an unresolved `InetSocketAddress`, whose name
the transport binds. `InetAddress.getLocalHost` and the reverse lookups are not there.

## Sockets

**A socket is the target's own TCP socket.** `Socket` and `ServerSocket` reach the host's
network through the program's own sockets, on every target that has them:

| Target | Build | Sockets |
|---|---|---|
| Interpreter, JVM | `rontolisp App.java`, `-o App.class` | the JDK's |
| WASM component | `-o app.wasm --component`, run with `wasmtime run -S inherit-network=y` | `wasi:sockets`; a name is looked up with `-S allow-ip-name-lookup=y` |
| Native executable | `--native` | the executable's own |
| `--no-wasi` module, a Cloudflare Worker | `-o worker.wasm --no-wasi --host-sockets` | the host's `connect()`, through the glue `--emit-js-glue` writes |

A plain Preview 1 module has no network: the program's first socket throws a
`SocketException` naming `--component` and `--native`. In the browser the first socket
throws, and so does a `--no-wasi` module's built without `--host-sockets`, naming the flag.

**A `--no-wasi` module's sockets only connect, and end with their request.** Its host is
`connect()` of `cloudflare:sockets`, which has no listener: a `ServerSocket` and a `bind`
throw a `BindException`. A connect nothing takes is a `ConnectException` with the host's
words whatever the reason, a name not found included; where the host does not tell the
local end, `getLocalPort` is 0 and `getLocalAddress` the wildcard address. Under the
generated `worker()` a socket left open ends with the request that opened it, and its next
use throws `SocketException: Socket closed: the request that opened it has ended`. No
socket can be waited for while `main` runs: the instance ends there. Built with
`--host-timer` too, `main` can wait for one, and a socket it made ends with the first
request.

**A name is looked up where the socket connects, binds or listens** (see [The HTTP
server](#the-http-server) for `InetSocketAddress`), and an address made with
`createUnresolved` is refused there with an `UnknownHostException`, as a JDK refuses it.

**A connected socket's options are the host's.** `TCP_NODELAY`, `SO_KEEPALIVE`,
`SO_LINGER`, `SO_OOBINLINE` and `IP_TOS` (`setTcpNoDelay`, `setKeepAlive`, `setSoLinger`,
`setOOBInline`, `setTrafficClass`, or `setOption` with `StandardSocketOptions`) set before
the connect are given to the host's socket when it connects, and set after, at once; a
getter answers what was set. `sendUrgentData` sends its byte as the host's urgent data. A
component's host has only `SO_KEEPALIVE` of these: the others are kept and answered back
there, and `sendUrgentData` throws a `SocketException`. The time limit of `connect`, the
read deadline of `setSoTimeout`, a server socket's accept deadline and `shutdownInput` /
`shutdownOutput` are the host's on every target.

**Some options are only kept and answered back:** the buffer sizes (8192 and 65536 until
set, where a JDK answers the host's), `SO_REUSEADDR` and `SO_REUSEPORT`, and a server
socket's `IP_TOS`. A connection a server socket accepts has the server socket's
`SO_REUSEADDR` and receive buffer size. `setTrafficClass` drops the two low bits, as a
JDK on Linux answers. `supportedOptions()` holds the standard options a JDK's holds;
`jdk.net.ExtendedSocketOptions` and `IP_MULTICAST_IF` are not in the subset.

**A connect a time limit or a close ended goes on in a component's host** until the host's
own connect ends: `wasi:sockets` lends the socket to its connect, so the host's socket is
released then, where a JDK closes it at once.

**What has come is counted by the stream.** `available()` answers the bytes the stream has
taken from the host and not handed out, else those it can take with no wait, 8 KiB at most.

**What the host says is the host's.** A refused connection is a `ConnectException`, a
failed bind a `BindException`, a read, a connect or an accept past its time a
`SocketTimeoutException` and a name not found an `UnknownHostException`, with a JDK's
words, as are an address in use, one no interface has and a connection its peer reset; the
words of any other failure are the host's, and a component's are its error code in the
operating system's words. A component reads a connection its peer reset as the end of the
stream.

**A thread waits for a socket while the others go on**, on one stack too
([Threads](#threads)): in a `--native` executable and a component, a connect, an accept and
a read wait among the threads, each until its time limit. Where no thread can go on, a
`--native` executable waits in its host for the sockets its threads wait for and the time
one waits for, and for the next request of a server of `com.sun.net.httpserver` in the same
wait.

**A read takes what has come in one read of the host's**, as a JDK's does: reading a MiB
through an 8 KiB array takes about 40 ms in a `--native` executable and in a component and
0.2 s on the JVM, against a few milliseconds under a JDK.

**Not there**, refused by name: `getChannel`, a `Socket` of a `Proxy`, `SSLSocket` and
`javax.net.ssl`, `DatagramSocket`.

## Digests and keys

**The algorithms are every digest a JDK has**: `MessageDigest` of MD5, MD2, SHA-1, SHA-224,
SHA-256, SHA-384, SHA-512, SHA-512/224, SHA-512/256, SHA3-224, SHA3-256, SHA3-384, SHA3-512,
SHAKE128-256 and SHAKE256-512, `Mac` of the HMAC of each but MD2 and SHAKE,
`SecretKeyFactory` of PBKDF2 over the HMACs of SHA-1 and the SHA-2 family, under every name
and alias a JDK takes. A name a JDK has and the subset does not -- the PBE Macs, `SslMacMD5`
and `SslMacSHA1`, DES, DESede and the PBE factories -- is refused by name where a program
writes it; a name computed where the program runs gets the JDK's `NoSuchAlgorithmException`.

**A `SecureRandom` is the host's entropy under every name.** A seed is taken and adds
nothing, so no `SecureRandom` repeats another's draws, where a JDK's `SHA1PRNG` seeded before
its first draw repeats them.

**The classes are the subset's own.** A digest's class is one of the subset
(`rontolisp.internal.Sha256` where a JDK's is `java.security.MessageDigest$Delegate$CloneableDelegate`),
and so is a derived key's. `SecretKeySpec.hashCode` and a derived key's are numbers of the
subset's own, the same for equal keys. A `NullPointerException` met while a factory reads a
key of the program's own has no message.

**The providers are a JDK's, holding the subset's services.** `Security` lists the twelve
providers a JDK installs, under their names, version (`25`) and descriptions. `SUN` holds the
digests and the `SecureRandom`s, `SunJCE` the Macs and the PBKDF2 factories, each under the
names, aliases and attributes a JDK's provider gives it; the others hold none. A service's
class is the subset's (`rontolisp.internal.Sha256`, `java.security.SecureRandom`,
`javax.crypto.Mac`), and so is a DRBG's of parameters (`java.security.SecureRandom$Drbg`).
What `getInstance` makes follows the subset's own tables: an entry a program puts in or
removes from `SUN` changes what the provider answers, not what `getInstance` makes, where a
JDK follows it.

**A provider of the program's own** holds the entries it puts and answers the services they
name. `getInstance` of a service it holds answers the `NoSuchAlgorithmException` a JDK
answers for a class it cannot find: the subset makes no class from its name.

**A DRBG keeps its parameters and holds a draw or a reseed to them**: its strength (112,
128, 192 or 256 bits) and whether it resists prediction. Every draw is the host's entropy;
the personalization string and the additional input add nothing. Where a generator refuses
parameters at `getInstance`, the refusal names the subset's class `java.security.SecureRandom`
where a JDK names its own, and its cause is `IllegalArgumentException` for `SHA1PRNG` too,
where a JDK's is a `NoSuchMethodException`.

**Not there**, refused by name: `Security.addProvider`, `insertProviderAt`, `removeProvider`
and the security properties, `Provider.Service.newInstance`, `MacSpi`, `SecretKeyFactorySpi`
and `SecureRandomSpi`.

## Calendars and time zones

**The default zone is the host's, and a program asking for it holds every region.**
`TimeZone.getDefault`, `Calendar.getInstance()` and `new GregorianCalendar()` read the zone as
a JDK reads it on Linux: `TZ` less a leading colon, else the zone file the link
`/etc/localtime` names; a region or a three-letter id is itself, a custom id normalized, a
POSIX string (`JST-9`, `EST5EDT,M3.2.0,M11.1.0`) the custom id of its offset, any other name
`GMT`. The host names its zone where the program runs, so such a program holds every region of
the tz database, some 60 KB of a `.wasm` more than one region. A `.wasm` reads `TZ` where its
host passes it and the link where `/etc` is granted; else its zone is `GMT`. The
`user.timezone` property is not read (a program sets no system property). Differs: a `TZ` that
is a file's path, or that starts with `posix/`, is `GMT` (a JDK reads the file's offset now, or
the region after the prefix).

**Refused where no rule of a JDK's answers was found**, with `UnsupportedOperationException`:
the offsets of a region whose offset changes once `setRawOffset` gave it another raw offset (a
zone of one offset takes the new one); a region's offsets, and a `SimpleTimeZone`'s daylight
saving, in the last three years a `long` holds; a `SimpleTimeZone`'s daylight saving before
1600, at an instant and a change both within the raw offset's size plus the savings of one new
year, and with a time mode other than `WALL_TIME`, `STANDARD_TIME` and `UTC_TIME`;
`GregorianCalendar.roll` of `WEEK_OF_MONTH` before 1583, of `WEEK_OF_YEAR` before 1582 or of
a day in a week another year numbers, of `DAY_OF_WEEK` of a day of 1583 in a week begun in
1582, and of a time field to a time the clock skips, from after that change; a `DAY_OF_WEEK_IN_MONTH` below -306783378; a first day of
the week or a least count of days outside 1 to 7; `TimeZone.getOffset` of an era, a date and a
time of the zones `getTimeZone` and `SimpleTimeZone` make; `GregorianCalendar`'s
`getMinimum`, `getMaximum`, `getGreatestMinimum` and `getLeastMaximum`. Where the fields of an
instant are refused, the `get` or `set` that needs them throws; a calendar whose instant alone
is read goes on.

**A `SimpleTimeZone` answers as a zone asked for the first time.** A JDK's answer can depend
on what the same zone, or the zone a clone copied, was asked before (a schedule whose start
and end come in another order from one year to the next).

**A calendar's and a zone's `toString` is `Object`'s**, and their hash codes are numbers of
their own, equal for equal objects.

**Not there**, refused by name: the calendar that is not lenient (`setLenient`),
`getActualMaximum` and the other limits, `getDisplayName` and `getDisplayNames`, week years,
`setGregorianChange`, `isSet`, `clear(int)`, the other `getInstance`s, `toInstant`,
`toZonedDateTime`; `TimeZone.getAvailableIDs`, `setDefault`, `toZoneId`, `getDisplayName`.

## JDBC

**The drivers are the program's.** `DriverManager` holds the drivers registered, in their
order, and at its first lookup (`getConnection` of a url, `getDriver`, `getDrivers`,
`drivers`) makes each driver the program's `META-INF/services/java.sql.Driver` names, as a
JDK does: a driver registers itself as its class is initialized, and the first that cannot
be made ends the making, unreported. The `jdbc.drivers` system property is not read
(`System.getProperty` knows no such key). No driver comes with the subset.

**A trace written to the log writer has no frames.** While `DriverManager` has a log writer,
a lookup writes the lines a JDK writes, and an `SQLException` writes its trace as it is
made; the trace is the lines a JDK writes but the `at` lines and `... n more`.

**Not there**, refused by name: `javax.sql.RowSet` and the `javax.sql.rowset` packages; of
`javax.naming`, `InitialContext`, `CompositeName`, `CompoundName`, the other packages and
`NamingException.appendRemainingComponent` (which makes a `CompositeName`).

## Logging

**Where a record was made is told by its call.** A JDK finds the class and the method that
called a logger on its stack. Here each call of a `Logger` method that makes or is handed a
record tells it its class and its method, named as a compiler names them (`<init>`,
`<clinit>`, `lambda$main$0`). Two differences follow. A call through a method reference
(`list.forEach(log::info)`) tells nothing, so `SimpleFormatter` writes the logger's name
where a JDK writes the frame that called the reference. A lambda's number counts the
lambdas, `super::` references and array constructors (`String[]::new`) before it; a
compiler also counts the other method references it makes a method of (of a variable arity
method, of an inner class's constructor, an array's `clone`), so a lambda after one of
those has a number one lower. A call of SLF4J's logger, or of its fluent builder's `log`,
tells it the same way, and SLF4J's own classes tell nothing: a record names the frame that
called SLF4J, which its binding to `java.util.logging` finds on its stack under a JDK
([Logging](logging.md)). So does a call of Commons Logging's `Log` (`trace` to `fatal`), its
classes telling nothing; its `Jdk14Logger` hands `Logger.logp` the method it finds on its
stack, which here is the method that call told.

**The configuration is the default one, and the program changes it.** The root logger is at
`INFO` with a `ConsoleHandler` on the standard error, made when the root's handlers are first
asked for. No configuration file is read: no target has the `java.util.logging.config.file`
system property. `LogManager.getProperty` answers the default configuration's `handlers`,
`.level` and the `ConsoleHandler`'s `level` and `formatter`; `readConfiguration()` restores
that configuration. `SimpleFormatter` writes its default format. A build with
`--wasi-logging` makes the root's handler one a JDK does not have, which hands each record to
the host's log ([The host's log](logging.md#the-hosts-log)); `getProperty("handlers")` names
it.

**A logger is kept.** A JDK may collect a logger nothing holds, and the level set on it with
it; here a logger of a name lives as long as the program. `LogRecord.getLongThreadID` is the
thread's `threadId()`, a number of rontolisp's own ([Threads](#threads)).

**Not there**, refused by name: resource bundles (`Logger.getLogger(String, String)`,
`logrb`, `setResourceBundle`; a `Logger` subclass given a bundle's name throws
`UnsupportedOperationException`), `readConfiguration(InputStream)`, `updateConfiguration`,
`FileHandler`, `SocketHandler`, `MemoryHandler`, `XMLFormatter`, `LoggingPermission`. A
message parameter with a format type (`{0,number}`) is formatted by `MessageFormat`, which
does not have the types yet: it throws `UnsupportedOperationException`, which a handler
reports through its `ErrorManager`.

**Commons Logging finds `Jdk14Logger` only where reachability metadata declares it**
([Logging](logging.md#commons-logging)): without it, and without SLF4J beside it,
`LogFactory.getLog` throws `LogConfigurationException: No suitable Log implementation`,
where a JDK finds the class on its class path.

## What is refused

Each of these is an error naming the construct and its position. In a library on the source
path, a statement naming a JDK class or member the subset lacks is a warning instead, and
throws where it runs ([Using a third-party library](index.md#using-a-third-party-library)).

- Reflection: annotated types (`getAnnotatedType`, `getAnnotatedReturnType`,
  `getAnnotatedSuperclass` ..) and the annotations of a type variable; of
  `java.lang.invoke`, what [The JDK classes](classes.md) does not list (`VarHandle`, the
  combinators of `MethodHandles`, the handles of a field such as `findGetter`, `Lookup.in`).
- A `switch` over a `long`, a `float`, a `double` or a `boolean`; a method reference and a
  field through a qualified `super` (`Interface.super::m`, `Outer.super.x`).
- A class extending `String`, `StringBuilder` or a box.
- Of `java.util.concurrent`, what [The JDK classes](classes.md) does not list: the queues
  ordered by priority or by delay. A `Timer`'s `schedule` of a `Date`. Thread groups.
- Any JDK class or member [The JDK classes](classes.md) does not list: reflection, what
  [Files](#files) and [Calendars and time zones](#calendars-and-time-zones) name. Of `java.net`, everything but `URI`, `URL`, the
  addresses, [sockets](#sockets) and the [HTTP client](#the-http-client): `HttpURLConnection`,
  `DatagramSocket`, what [Sockets](#sockets) names.
