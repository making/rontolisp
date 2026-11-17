# Deviations

The subset is partial by design. Where a program that the subset accepts behaves
differently from the language specification is listed here, and it is the same on every
backend but for the [threads](#threads) of a `.wasm`.

## What differs

**A string's elements are code points.** `length`, `charAt` and `substring` count Unicode
code points where the language counts UTF-16 units, so they differ for a character outside
the Basic Multilingual Plane. `hashCode` is the specified number, and `toCharArray` answers
the UTF-16 units, which `new String(char[])` reads back.

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
variable tests its bound, a cast to a parameterized type its class.

**A call that is an argument of an overloaded method is typed by its own arguments.** When
the method has several overloads of that many parameters, a lambda among the call's
arguments whose parameter types only the method around could give is an error: with
`show(Comparator<String>)` and `show(Runnable)` declared,
`show(Comparator.comparing(s -> s.length()))` is refused at `s.length()`, `s` being an
`Object` there.

**A store into an array is not checked.** An array of references does not record its
element type, so a value stored through an array type above the array's own
(`Object[] a = new String[1]; a[0] = 1;`) is stored, where the language throws
`ArrayStoreException`.

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

Two things differ. An error the run time raises inside an initialization that runs before
`main` (an array index out of bounds while a table is filled) is raised before `main`
starts. An exception thrown by a static initializer reaches the program as itself, not
inside an `ExceptionInInitializerError`.

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

**An uncaught exception is reported without a stack trace.** Standard error gets the line a
JDK writes first, `Exception in thread "main"` and the exception's `toString()`, and the
program exits with the status 1. No frame and no cause follows. `printStackTrace()` writes
an exception, the exceptions it suppressed and its causes as a JDK does, without the frame
lines.

```console
$ rontolisp Uncaught.java
Exception in thread "main" java.lang.IllegalStateException: insufficient funds: 30
```

**Some errors a compiler reports are not reported**, and such a program runs. `throws`
clauses are read and not checked: a checked exception that is neither caught nor declared
is not reported, nor is a `catch` of one its `try` cannot throw. No unchecked warning is
printed. A `protected` member is reached from every class under its
class, where the language allows an instance member only through a reference of that
class's own type and a constructor only through `super(..)`.

**A record's `hashCode`** is computed from its components in order, `31 * h + hash`; the
language leaves the number unspecified.

**`hashCode` and `toString` of an object declaring neither** are an identity number of
rontolisp's own, `Name@` and that number in hexadecimal. A `HashMap` keyed by such objects,
enum constants included, walks them in another order than a JDK, whose own order changes
from run to run.

**The order of a `HashMap` differs from a JDK's in two cases.** More than eight keys whose
hash codes fall into one bucket of a table of 64 buckets or more are walked in the order
they were put. Keys of a `Double` with few digits get there from 64 keys on (`0.0`, `0.25`,
`0.5` ..: their hash codes differ in the high bits only), and so do the keys of a class
whose `hashCode` has few values; strings and integers do not. `Set.of` and `Map.of` have
one fixed order, where a JDK picks another at every start.

**The functions of `Math` are those of `StrictMath`**, so a result is the same on every
backend and processor. A JDK's `Math.sin`, `Math.exp` and the like may differ from it in
the last digit.

**`String.split` reads a small part of the regular expressions**: a text with no operator,
or one character class alone or followed by `+`. Any other expression is an error when the
program runs. `String.format` has the conversions the [overview](index.md) lists; any other
is an error naming it.

**Two older classes are the newer ones.** A `StringBuffer` is a `StringBuilder` and
`getClass().getName()` says so; a `Vector` is an `ArrayList` to `instanceof`.

**The standard streams are streams of characters.** `System.in` reads the characters of
the host's standard input as the bytes of their UTF-8, so input that is no text does not
come through as its bytes; a byte written to `System.out` or `System.err` is a byte of
UTF-8, and its character is written once its last byte is. A read of several bytes from
`System.in` waits for the first and takes what is at hand after it; a `.wasm` cannot ask
its host what is at hand and reads to the end of the line. `System.out` and `System.err`
cannot be replaced (`System.setOut`) and stay open after `close()`.

**Three charsets.** `UTF-8`, which is the default, `ISO-8859-1` and `US-ASCII`; any other
name is an `UnsupportedEncodingException` or an `UnsupportedCharsetException`. Bytes that
are no UTF-8 decode to U+FFFD, as many as a JDK 25 writes.

## Threads

**A `.wasm` runs the threads of a program on one stack.** A thread started does not run
until the running thread cannot go on: it waits in `wait` or `join`, sleeps, yields, or is
the thread of `main` and ends. The threads started then run on that thread's stack, one
after another in the order started, each to its end. Every such execution is one the
language allows, which promises no thread a turn; a JDK seldom produces it. Three kinds of
program show it.

- A thread suspended that way goes on only once every thread run above it has ended. A wait
  that only a suspended thread could end is refused where it happens, with a message naming
  it on standard error and the status 1: two threads taking turns through `wait` and
  `notify`, a bounded buffer whose producer waits for its consumer.
- Nothing interrupts a running thread. A thread that runs until another one tells it to
  stop, by a flag or an interrupt, never ends when the one to tell it is suspended under it.
- `sleep` lasts at least its time. A thread sleeping under another goes on when that one
  has ended, so two threads sleeping different times can print in the other order.

A daemon thread not yet run when the last other thread ends never runs.

**What is shared without a monitor or `volatile` is the host's.** On the interpreter and
the JVM a field two threads read and write with neither may be read stale, as the language
allows. `volatile`, `synchronized`, `Thread.start` and `join` order what they order in the
language.

**The classes a JDK synchronizes take no lock.** `StringBuffer`, `Vector` and `Hashtable`
are the unsynchronized classes: a program sharing one between threads guards it with
`synchronized`. An `AtomicInteger` and an `AtomicLong` are no `Number`.

**A thread's number is rontolisp's own.** `threadId()` and the number in `toString()` count
the threads of the program from 1. A class literal is read only as the lock of a
`synchronized` statement. One lock guards the static initialization of every class, so a
static initializer that waits for a thread initializing another class never ends.

## What is refused

Each of these is an error naming the construct and its position.

- Annotation types, `assert`.
- Class literals, but as the lock of a `synchronized` statement.
- A `switch` over a `long`, a `float`, a `double` or a `boolean`; a method reference through a
  qualified `super` (`Interface.super::m`); a constructor reference with type arguments
  (`Box::<T>new`).
- A field hiding one of a superclass.
- A lambda or a method reference cast to several types (`(Runnable & Marker) () -> ..`); a
  cast or an `instanceof` narrowing to an array type other than `int[]`, `byte[]`,
  `float[]`, `double[]` and `boolean[]`.
- A class extending `String`, `StringBuilder` or a box.
- `java.util.concurrent` but for the four atomic classes: locks, executors, concurrent
  collections, `CompletableFuture`. `Thread.getState`, thread groups and uncaught-exception
  handlers.
- Any class or member of the library not listed in the [overview](index.md): streams
  (`java.util.stream`), `LinkedList`, `LinkedHashMap`, `TreeMap`, `Scanner`, files
  (`File`, `FileReader`, `java.nio.file`), `java.time`, `BigInteger`, `BigDecimal`, regular
  expressions, reflection.
