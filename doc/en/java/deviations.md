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

**A store into an array is not checked.** An array does not record its element type, so a
value stored through an array type above the array's own (`Object[] a = new String[1];
a[0] = 1;`) is stored, where the language throws `ArrayStoreException`.

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
`null`, a box that is `null` read as its primitive, an array index out of bounds, a failed `Integer.parseInt` and the like are errors a `catch` of
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
refused. A type argument outside the bound of its type parameter is not reported, and no
unchecked warning is printed. Of the access modifiers `private` is checked; a program is one
file, so the others never restrict.

**A record's `hashCode`** is computed from its components in order, `31 * h + hash`; the
language leaves the number unspecified. A record with a `double` component has no
`hashCode` yet.

**`hashCode` and `toString` of an object declaring neither** are an identity number of
rontolisp's own, `Name@` and that number in hexadecimal.

## What is refused

Each of these is an error naming the construct and its position.

- Static imports, annotation types, `assert`.
- Class literals, `try`-with-resources, multi-catch, `return`, `break`, `continue` or
  `yield` leaving a `finally` block.
- A `switch` over a `long`, a `double` or a `boolean`; a switch expression whose colon
  cases run into the next; `Interface.super.m()`; a class extending an inner class from
  outside that class's outer class; an anonymous class made with `outer.new` or with `<>`.
- A field hiding one of a superclass; `float`, `byte`, `short`.
- `==` and `!=` between two strings: they compare references. Compare with `equals`.
- A type written `Outer<A>.Inner`, and a member of an inner class of a parameterized class
  read from outside that class; a cast to several types (`(A & B) x`); a cast narrowing
  to an array type; a method reference whose type has type arguments (`List<String>::size`) or that
  writes its own (`this::<T>m`).
- A class extending or implementing a collection of the library; a `HashMap` keyed by
  anything but `String`, `Integer`, `Long`, `Character` or `Boolean`, a type parameter
  included.
- Threads, `synchronized`, `volatile`.
- Any class or member of the library not listed in the [overview](index.md), and the text
  of a `HashMap`.
