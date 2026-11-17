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

**Static fields are set before `main`, class by class in the file's order.** The language
initializes a class when it is first used. A static initializer may therefore only read
constants and the static fields declared above it in its class, and may not call the
program's own methods or constructors; any other is refused. A `static final` field of a
primitive type or `String` with a constant initializer is a constant wherever it is read.

**An error of the run time is not the language's exception.** A division by zero is an
`ArithmeticException` with the message `/ by zero`. A `null` receiver, an array index out
of bounds, a failed `Integer.parseInt` and the like are errors a `catch` of
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

**Some errors a compiler reports are not reported.** A local read before it is assigned,
unreachable code, a missing `return`, a checked exception not declared, an access modifier
and an assignment to a `final` variable are not checked: such a program runs.

**`hashCode` and `toString` of an object declaring neither** are an identity number of
rontolisp's own, `Name@` and that number in hexadecimal.

## What is refused

Each of these is an error naming the construct and its position.

- `switch`, `enum`, `record`, nested, local and anonymous classes, generic classes and
  methods, sealed types, initializer blocks, `default` and `static` interface methods,
  interface constants, variable arity parameters, static imports.
- Patterns (`instanceof` with a binding), method references, the unnamed `_`, text blocks,
  labeled statements, `try`-with-resources, multi-catch, `return`, `break` or `continue`
  leaving a `finally` block, statements ahead of `this(..)` or `super(..)`.
- A field hiding one of a superclass; `float`, `byte`, `short`; an array of several dimensions made with its lengths
  (`new int[2][3]`; `new int[2][]` and an initializer are read).
- `==` and `!=` between two strings or two boxed values: they compare references. Compare
  with `equals`, or unbox one side.
- `instanceof` or a cast to an interface when some lambdas of the program implement it and
  others do not: a lambda does not record the interface it was made for.
- A lambda for an interface of the library other than `Runnable` (`java.util.function`).
- Threads, `synchronized`, `volatile`.
- Any class or member of the library not listed in the [overview](index.md), and the text
  of a `HashMap`.
