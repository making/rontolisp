# Deviations

The subset is partial by design. Where a program that the subset accepts behaves
differently from the language specification is listed here, and it is the same on every
backend.

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
is not reported, nor is a `catch` of one its `try` cannot throw. A local read before it is assigned is
reported when no path assigns it, following `if`, `while` and `for`; an assignment anywhere
inside a `switch`, a `try`, a `do` loop or a conditional expression counts as made. A
statement that cannot be reached is reported after a `return`, a `throw`, a `break`, a
`continue` and a loop that never ends, and a missing `return` where a method's last
statement surely completes; the cases a compiler finds beyond those are not. A local
declared without a value, assigned twice and then read by a lambda or a local class is not
refused. A type argument outside the bound of its type parameter is not reported, and no
unchecked warning is printed. Of the access modifiers `private` is checked; a program is one
file, so the others never restrict.

**A record's `hashCode`** is computed from its components in order, `31 * h + hash`; the
language leaves the number unspecified.

**`hashCode` and `toString` of an object declaring neither** are an identity number of
rontolisp's own, `Name@` and that number in hexadecimal. A `HashMap` keyed by such objects,
enum constants included, walks them in another order than a JDK, whose own order changes
from run to run.

**The order of a `HashMap` differs from a JDK's in two cases.** More than eight keys whose
hash codes fall into one bucket of a table of 64 buckets or more are walked in the order
they were put. `Set.of` and `Map.of` have one fixed order, where a JDK picks another at
every start.

**The functions of `Math` are those of `StrictMath`**, so a result is the same on every
backend and processor. A JDK's `Math.sin`, `Math.exp` and the like may differ from it in
the last digit.

**`String.split` reads a small part of the regular expressions**: a text with no operator,
or one character class alone or followed by `+`. Any other expression is an error when the
program runs. `String.format` has the conversions the [overview](index.md) lists; any other
is an error naming it.

**Two older classes are the newer ones.** A `StringBuffer` is a `StringBuilder` and
`getClass().getName()` says so; a `Vector` is an `ArrayList` to `instanceof`.

## What is refused

Each of these is an error naming the construct and its position.

- Static imports, annotation types, `assert`.
- Class literals.
- A `switch` over a `long`, a `float`, a `double` or a `boolean`; a switch expression whose colon
  cases run into the next; `Interface.super.m()`; a class extending an inner class from
  outside that class's outer class; an anonymous class made with `outer.new` or with `<>`.
- A field hiding one of a superclass.
- A type written `Outer<A>.Inner`, and a member of an inner class of a parameterized class
  read from outside that class; a cast to several types (`(A & B) x`); a cast or an
  `instanceof` narrowing to an array type other than `int[]`, `byte[]`, `float[]`,
  `double[]` and `boolean[]`; a method reference whose type has type arguments (`List<String>::size`) or that
  writes its own (`this::<T>m`).
- A class extending `String`, `StringBuilder` or a box.
- Threads, `synchronized`, `volatile`.
- Any class or member of the library not listed in the [overview](index.md): streams,
  `LinkedList`, `LinkedHashMap`, `TreeMap`, `Scanner`, `java.io`, `java.time`,
  `BigInteger`, `BigDecimal`, regular expressions, reflection.
