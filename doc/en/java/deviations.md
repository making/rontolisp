# Deviations

The subset is partial by design. Where a program that the subset accepts behaves
differently from the language specification is listed here, and it is the same on every
backend.

## What differs

**A string's elements are code points.** `length`, `charAt` and `substring` count Unicode
code points where the language counts UTF-16 units, so they differ for a character outside
the Basic Multilingual Plane. `hashCode` is the specified number.

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

**A boxed `Long` and a boxed `Integer` of one number are one value.** A box is the number
itself, so `equals` between a boxed `long` and a boxed `int` of one number is true and
`getClass()` of a boxed `long` answers `java.lang.Integer`.

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

**An error of the run time is not the language's exception.** A division by zero is an
`ArithmeticException` with the message `/ by zero`. A `null` receiver, a `switch` over
`null`, an array index out of bounds, a failed `Integer.parseInt` and the like are errors a `catch` of
`RuntimeException`, `Exception` or `Throwable` takes, with another class and message than
the language's. A call or a field read on `null` is not always detected. A failed cast
throws `ClassCastException` with no message.

**An uncaught exception is reported in rontolisp's form**: `Unhandled condition:` and the
exception's `toString()` on standard error, then a non-zero exit. There is no stack trace.

```console
$ rontolisp Uncaught.java
Unhandled condition: java.lang.IllegalStateException: insufficient funds: 30
  at Uncaught.java:4 in Uncaught.check(int,int)
```

**Some errors a compiler reports are not reported**, and such a program runs. A checked
exception that is not declared is never reported. A local read before it is assigned is
reported when no path assigns it, following `if`, `while` and `for`; an assignment anywhere
inside a `switch`, a `try`, a `do` loop or a conditional expression counts as made. A
statement that cannot be reached is reported after a `return`, a `throw`, a `break`, a
`continue` and a loop that never ends, and a missing `return` where a method's last
statement surely completes; the cases a compiler finds beyond those are not. A local
declared without a value, assigned twice and then read by a lambda or a local class is not
refused. Of the access modifiers `private` is checked; a program is one file, so the others
never restrict.

**A record's `hashCode`** is computed from its components in order, `31 * h + hash`; the
language leaves the number unspecified. A record with a `double` component has no
`hashCode` yet.

**`hashCode` and `toString` of an object declaring neither** are an identity number of
rontolisp's own, `Name@` and that number in hexadecimal.

## What is refused

Each of these is an error naming the construct and its position.

- Generic classes, records and methods, static imports, annotation types, `assert`.
- Method references, class literals, `try`-with-resources, multi-catch, `return`, `break`,
  `continue` or `yield` leaving a `finally` block.
- A `switch` over a `long`, a `double` or a `boolean`; a switch expression whose colon
  cases run into the next; `Interface.super.m()`; a class extending an inner class from
  outside that class's outer class; an anonymous class made with `outer.new`.
- A field hiding one of a superclass; `float`, `byte`, `short`.
- `==` and `!=` between two strings or two boxed values: they compare references. Compare
  with `equals`, or unbox one side.
- `instanceof` or a cast to an interface when some lambdas of the program implement it and
  others do not: a lambda does not record the interface it was made for.
- A lambda for an interface of the library other than `Runnable` (`java.util.function`).
- Threads, `synchronized`, `volatile`.
- Any class or member of the library not listed in the [overview](index.md), and the text
  of a `HashMap`.
