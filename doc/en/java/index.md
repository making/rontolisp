# A subset of the Java language (experimental)

**Experimental.** rontolisp reads a subset of the Java language and lowers it to the same
core forms as Common Lisp, so a `.java` program runs on every backend. It is a subset by
design and nothing here is a compatibility promise: a construct outside it is refused by
name, with its position, before anything runs. The front end and its library are written
for rontolisp from the published specifications; no JDK code is read, bundled or shipped
in a compiled program.

A `.java` file is read as this subset; `--source-language java` says so for any other file
and for `-e`. A program is the file it starts at and the files of its source tree that
file names.

```bash
rontolisp Hello.java                                # interpreter
rontolisp Hello.java -o Hello.class && java Hello   # JVM
rontolisp Hello.java -o hello.wasm && wasmtime run hello.wasm
rontolisp Hello.java -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp -e 'void main() { IO.println(1 + 2); }' --source-language java
```

`--no-gc` is refused: that backend has no structures, strings or closures. With no program,
`--source-language java` starts a REPL ([At the prompt](#at-the-prompt)).

[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) holds a
program of several packages with its expected output, an HTTP server with its client, and
a miniature httpbin.

```java
interface Shape {
    double area();
}

class Circle implements Shape {
    final double r;

    Circle(double r) {
        this.r = r;
    }

    public double area() {
        return Math.PI * r * r;
    }

    public String toString() {
        return "circle " + r;
    }
}

class Square implements Shape {
    final double side;

    Square(double side) {
        this.side = side;
    }

    public double area() {
        return side * side;
    }
}

void main() {
    List<Shape> shapes = new ArrayList<>();
    shapes.add(new Circle(1.5));
    shapes.add(new Square(2));
    double total = 0;
    for (Shape shape : shapes) {
        total += shape.area();
    }
    IO.println(shapes.get(0) + ", " + shapes.size() + " shapes, total " + total);
    IO.println(Integer.MAX_VALUE + 1);
}
```

```
circle 1.5, 2 shapes, total 11.068583470577035
-2147483648
```

## Where a program starts

At the first class of the file when it has a `main`, otherwise at the class named as the
file. `main(String[])` is chosen before `main()`; an instance `main` runs on an instance
made with the constructor of no argument. The argument array is empty. A file whose
top level holds methods and fields is a compact source file: they are the members of one
class, and every library class is in scope without an import. A class declared beside them
is a member of that class too: an inner class, unless it is `static`, a record, an enum or
an interface.

## A program of several files

A class the program names that no file read so far declares is read from the file its
package and name spell under the root of the source tree, the directory above the
starting file's package: for `app/Main.java` declaring `package app;`, `util.Strings` is
`util/Strings.java` and `Helper` of the package `app` is `app/Helper.java`. Every class of a
file read joins the program, and the program is compiled whole. The directories above the
starting file must spell its package. A `(load "app/Main.java")` from Common Lisp runs the
program as `rontolisp app/Main.java` does ([Across languages](#across-languages)).

```console
$ rontolisp app/Main.java
$ rontolisp app/Main.java -o main.wasm
```

An `import` names a class, a package on demand, `module java.base` (every class of the
library), or with `static` a static member of a class, or all of them on demand. A class
that is not `public` and a member with no access modifier are reached from their own
package only, a `protected` member from its package and from the classes under its class.

## At the prompt

`--source-language java` with no program, and the playground's Java pick, read a buffer at a
time: a statement, an expression, a variable, a method, a class or an import. A statement
needs no `;`. An expression is shown under a generated name, `$1`, `$2`, ..., which a later
buffer can read with the expression's type; a variable under its own name. A string and a
`char` are shown as literals, an array with its type and elements.

```console
java> int twice(int n) { return 2 * n; }
java> int quad(int n) { return twice(twice(n)); }
java> quad(1)
$1 ==> 4
java> int twice(int n) { return n + 1; }
java> quad(1)
$2 ==> 3
java> var names = new String[] { "a", "b" }
names ==> String[2] { "a", "b" }
java> 1 / 0
Error: Exception java.lang.ArithmeticException: / by zero
```

A declaration replaces the earlier one of its name (a method: of its name and parameter types),
and what was declared before calls the new one: a method, an override, a subclass added later
is reached by every call. A class keeps its static fields until it is declared again; then the
variables whose type names it hold `null`. The session reads no other file.

## Across languages

A Common Lisp program loads a `.java` file. A file with a `main` runs it; a file with none
defines its classes and runs nothing. A static method is the function named by its class, its
name and its parameter types: `|Geometry.area(int)|`, `|app.Geometry.area(int)|` in a package,
`|Point.new(int,int)|` for a constructor. A `native` static method of a Java program is the
Common Lisp function its name spells, a hyphen before each capital: `stringUpcase` is
`string-upcase`, read in the package the program is loaded in.

```console
$ cat Geometry.java
record Point(int x, int y) { }

class Geometry {
    static native int square(int n);
    static native String stringUpcase(String s);

    static int norm2(Point p) {
        return square(p.x()) + square(p.y());
    }

    static String shout(String s) {
        return stringUpcase(s) + "!";
    }
}
$ cat main.lisp
(defun square (n) (* n n))
(load "Geometry.java")
(print (|Geometry.norm2(Point)| (|Point.new(int,int)| 3 4)))
(print (|Geometry.shout(String)| "hi"))
$ rontolisp main.lisp
25
"HI!"
```

Nothing is converted but this: a `char` crosses as a character, a `boolean` comes back as `T`
or `NIL`. A number is a number, a string a string, `null` is `NIL`, an object of the program a
structure, an `Object[]` a vector. A Common Lisp list is no `List`: hand a Java method a vector.

## The tools

`rontolisp test` refuses a `.java` file: the subset has no test framework, and a program's
exit status is its verdict. `rontolisp format` refuses one: it re-indents S-expressions. The
Maven plugin compiles `src/main/lisp`; `src/main/java` stays the Java compiler's.

## What is read

| | |
|---|---|
| Types | `int`, `long`, `short`, `byte`, `char`, `double`, `float`, `boolean`, `String`, classes, interfaces, enums, records, arrays; `var` for a local; type arguments, wildcards (`?`, `? extends T`, `? super T`) and raw types; an inner class of a parameterized class (`Outer<String>.Inner`) |
| Declarations | classes with `extends` and `implements`, abstract classes and methods; classes, interfaces, records and methods with type parameters and their bounds; enums with fields, constructors, methods and constants that have a body; records with compact and canonical constructors; interfaces with abstract, `default`, `static` and `private` methods and constants; `sealed`, `permits` and `non-sealed`; static nested, inner, local and anonymous classes; fields, static fields, a field hiding one of a class above it, static and instance initializer blocks; constructors with `this(..)`, `super(..)` and `outer.super(..)` and statements ahead of them; overloaded and variable arity methods; `synchronized` methods and `volatile` fields; `package` and `import` |
| Statements | local variables (`final`, the unnamed `_`), `if`, `while`, `do`, `for`, the enhanced `for` over an array or an `Iterable`, `switch` with colon or arrow cases, patterns and guards, labeled statements, `break` and `continue` with or without a label, `yield`, `return`, `throw`, `try` with `catch` (of one type or several) and `finally`, `try`-with-resources, `synchronized` over an object or a class literal |
| Expressions | every operator of the primitive types with its conversions, string concatenation, the conditional, assignments and compound assignments, casts (to several types, `(A & B) x`, a lambda and a method reference too), `instanceof` with a type, a type pattern or a record pattern, `switch` expressions, `new` with or without the constructor's type arguments (`new <T>Box(..)`), `outer.new Inner()` with or without a class body, `Outer.this`, array creation with one or several lengths and initializers, text blocks, method calls with or without type arguments, `super.m(..)`, `super.x` for a field, `Interface.super.m(..)`, `Outer.super.m(..)`, `new Box<>(..)` with or without a class body, lambdas and method references (`Type::name`, `expression::name`, `super::name`, `Type::new`, `int[]::new`, `List<String>::size`, `Type::<T>name`, `Type::<T>new`) for any functional interface |

The annotations of `java.lang` (`@Override`, `@FunctionalInterface`, `@SuppressWarnings`,
`@SafeVarargs`, `@Deprecated`) are read and dropped, but for what a compiler checks of
`@Override` and `@FunctionalInterface` and for the warnings `@SuppressWarnings("unchecked")`
and `@SafeVarargs` silence. Any other name is no annotation type of the program,
and is refused as a compiler refuses it. An `int`, a `long`, a `short` and a `byte` wrap as two's
complement; a `float` is rounded to single precision at every operation. A `double` and a
`float` print as the shortest text that reads back as the same value of their type.

```java
sealed interface Shape permits Circle, Rect { }

record Circle(double r) implements Shape { }

record Rect(double w, double h) implements Shape { }

enum Unit {
    CM(1), M(100);

    final int scale;

    Unit(int scale) {
        this.scale = scale;
    }
}

double area(Shape shape) {
    return switch (shape) {
        case Circle c -> Math.PI * c.r() * c.r();
        case Rect(double w, double h) -> w * h;
    };
}

void main() {
    Shape[] shapes = {new Circle(1), new Rect(2, 2), new Rect(2, 3)};
    for (Shape shape : shapes) {
        String kind = shape instanceof Rect r && r.w() == r.h() ? "square" : "shape";
        IO.println(shape + " is a " + kind + " of area " + area(shape));
    }
    for (Unit unit : Unit.values()) {
        String size = switch (unit) {
            case CM -> "small";
            case M -> "large";
        };
        IO.println(unit + " " + unit.ordinal() + " " + unit.scale + " " + size);
    }
}
```

```
Circle[r=1.0] is a shape of area 3.141592653589793
Rect[w=2.0, h=2.0] is a square of area 4.0
Rect[w=2.0, h=3.0] is a shape of area 6.0
CM 0 1 small
M 1 100 large
```

## Unchecked warnings

What a compiler warns of as unchecked -- a member called through a raw type, a raw type
converted to a parameterized one, a cast the program that runs cannot check, a variable
arity parameter of a type it cannot check -- is printed on standard error before the
program runs, as a compiler prints it. A compile prints them as it compiles; the compiled
program prints nothing. A declaration annotated `@SuppressWarnings("unchecked")` warns of
nothing inside it. A `catch` clause nothing reaches, because the clauses before it take
every exception of its `try` it could, is warned of in the same way
(`unreachable catch clause`).

```java
void main() {
    List names = new ArrayList();
    names.add("ann");
    IO.println(names);
}
```

```
[ann]
```

Run as `Raw.java`, it prints first, on standard error:

```console
Raw.java:3: warning: [unchecked] unchecked call to add(E) as a member of the raw type List
    names.add("ann");
             ^
  where E is a type-variable:
    E extends Object declared in interface List
1 warning
```

## Type parameters, lambdas and method references

A type parameter exists only while the program is read: the type arguments of a call and of
`new Box<>(..)` are found from the arguments, from the type the value is wanted as and from
the lambdas among the arguments, and a value is boxed or unboxed where a type argument is a
box. A lambda and a method reference are made for any interface with one abstract method,
the program's or the library's.

```java
record Pair<A, B>(A first, B second) {
    <C> Pair<A, C> withSecond(Function<? super B, ? extends C> f) {
        return new Pair<>(first, f.apply(second));
    }
}

<T extends Comparable<T>> T max(List<T> items) {
    T best = items.get(0);
    for (T item : items) {
        if (item.compareTo(best) > 0) {
            best = item;
        }
    }
    return best;
}

<T, R> List<R> map(List<T> items, Function<? super T, ? extends R> f) {
    List<R> out = new ArrayList<>();
    for (T item : items) {
        out.add(f.apply(item));
    }
    return out;
}

void main() {
    List<String> words = new ArrayList<>();
    words.add("pear");
    words.add("fig");
    words.add("banana");
    List<Integer> lengths = map(words, String::length);
    Pair<String, Integer> longest = new Pair<>(max(words), max(lengths));
    IO.println(lengths + " " + longest + " " + longest.withSecond(n -> n * 1.5));
    Comparator<String> byLength = Comparator.comparingInt(String::length);
    Predicate<String> isShort = word -> word.length() < 5;
    IO.println(byLength.reversed().compare("fig", "pear") + " " + map(words, isShort.negate()::test));
}
```

```
[4, 3, 6] Pair[first=pear, second=6] Pair[first=pear, second=9.0]
1 [false, false, true]
```

## The library

Each class has the members listed and no others; a missing class or member is refused by
name. `String`, `StringBuilder`, the boxes, `Math`, `System` and `IO` are part of the
front end. Every other class is written in the subset itself and compiled with the program
as far as the program reaches it, so a class of the program can extend or implement one
(`AbstractList`, `Iterable`, `Comparator`).

| Class | Members |
|---|---|
| `Object` | `new Object()`, `toString`, `equals`, `hashCode`, `getClass().getName()`, `getClass().getSimpleName()`, `getClass()` and a class literal (`Name.class`) compared with one another by `==`, `!=` or `equals`, a class literal's `getName()` and `getSimpleName()`, `wait()`, `wait(long)`, `notify`, `notifyAll` |
| An enum | `values()`, `valueOf(String)`, `name`, `ordinal`, `compareTo`, `toString` |
| A record | the accessors, `equals`, `hashCode`, `toString` |
| An array | `length`, `clone()` |
| `String` | `length`, `charAt`, `isEmpty`, `isBlank`, `equals`, `equalsIgnoreCase`, `compareTo`, `compareToIgnoreCase`, `hashCode`, `substring`, `subSequence`, `indexOf` and `lastIndexOf` of a string or a character with or without a start, `indexOf` within a range, `codePointAt`, `codePointBefore`, `codePointCount`, `offsetByCodePoints`, `regionMatches`, `contentEquals`, `getChars`, `indent`, `contains`, `startsWith`, `endsWith`, `toUpperCase`, `toLowerCase`, `trim`, `strip`, `stripLeading`, `stripTrailing`, `repeat`, `replace`, `replaceAll`, `replaceFirst`, `matches`, `concat`, `toCharArray`, `split`, `intern`, `formatted`, `getBytes` of nothing, a `Charset` or a charset's name, `toUpperCase` and `toLowerCase` of a `Locale`; `String.valueOf`, `String.copyValueOf`, `String.join`, `String.format` with or without a `Locale`, `String.CASE_INSENSITIVE_ORDER`; `new String()`, `new String(String)`, `new String(char[])`, `new String(char[], int, int)`, `new String(byte[])` with or without a range and a `Charset` or a charset's name |
| `StringBuilder`, `StringBuffer` | the constructors of nothing, a capacity or a text; `append` of a value or of a range of a text or of a `char[]`, `insert`, `reverse`, `setLength`, `setCharAt`, `deleteCharAt`, `delete`, `replace`, `indexOf` and `lastIndexOf` with or without a start, `appendCodePoint`, `repeat` of a text or a code point, `compareTo`, `toString`, `length`, `isEmpty`, `charAt`, `substring`, `subSequence` |
| `CharSequence` | `length`, `charAt`, `subSequence`, `isEmpty`; a `String` and a `StringBuilder` are one |
| `Appendable` | `append` of a `CharSequence`, of a range of one and of a `char`; a `StringBuilder`, a `Writer` and a `PrintStream` are one |
| `Integer`, `Long` | boxing and unboxing; `MAX_VALUE`, `MIN_VALUE`, `SIZE`, `BYTES`, `parseInt` / `parseLong` with or without a radix, `valueOf`, `decode`, `toString` with or without a radix, `toBinaryString`, `toHexString`, `toOctalString`, `Integer.parseUnsignedInt` and `Long.parseUnsignedLong` with or without a radix, `toUnsignedString` with or without a radix, `Integer.toUnsignedLong`, `divideUnsigned`, `remainderUnsigned`, `compareUnsigned`, `highestOneBit`, `lowestOneBit`, `numberOfLeadingZeros`, `numberOfTrailingZeros`, `reverse`, `reverseBytes`, `rotateLeft`, `rotateRight`, `compare`, `signum`, `sum`, `max`, `min`, `bitCount`, `hashCode`, `intValue`, `longValue`, `doubleValue`, `compareTo` |
| `Byte`, `Short` | boxing and unboxing; `MAX_VALUE`, `MIN_VALUE`, `SIZE`, `BYTES`, `parseByte` / `parseShort` with or without a radix, `valueOf`, `toString`, `compare`, `hashCode`, `toUnsignedInt`, `byteValue` / `shortValue`, `intValue`, `longValue`, `floatValue`, `doubleValue`, `compareTo` |
| `Float` | `MAX_VALUE`, `MIN_VALUE`, `MIN_NORMAL`, `MAX_EXPONENT`, `MIN_EXPONENT`, `SIZE`, `BYTES`, `POSITIVE_INFINITY`, `NEGATIVE_INFINITY`, `NaN`, `parseFloat`, `valueOf`, `toString`, `toHexString`, `compare`, `hashCode`, `floatToIntBits`, `floatToRawIntBits`, `intBitsToFloat`, `isNaN`, `isInfinite`, `isFinite`, `sum`, `max`, `min`, `floatValue`, `doubleValue`, `intValue`, `longValue`, `compareTo` |
| `Double` | `MAX_VALUE`, `MIN_VALUE`, `MIN_NORMAL`, `MAX_EXPONENT`, `MIN_EXPONENT`, `SIZE`, `BYTES`, `POSITIVE_INFINITY`, `NEGATIVE_INFINITY`, `NaN`, `parseDouble`, `valueOf`, `toString`, `toHexString`, `compare`, `hashCode`, `doubleToLongBits`, `doubleToRawLongBits`, `longBitsToDouble`, `isNaN`, `isInfinite`, `isFinite`, `sum`, `max`, `min`, `intValue`, `longValue`, `doubleValue`, `compareTo` |
| `Boolean`, `Character` | `Boolean.TRUE`, `FALSE`, `parseBoolean`, `valueOf`, `toString`, `hashCode`, `compare`, `logicalAnd`, `logicalOr`, `logicalXor`, `booleanValue`, `compareTo`; `Character.MAX_VALUE`, `MIN_VALUE`, `valueOf`, `toString`, `compare`, `hashCode`, `isDigit`, `isLetter`, `isLetterOrDigit`, `isAlphabetic`, `isWhitespace`, `isSpaceChar`, `isUpperCase`, `isLowerCase`, `toUpperCase`, `toLowerCase`, `getNumericValue`, `digit`, `forDigit`, `charValue`, `compareTo`; `getType` and the constants of the general categories, `isTitleCase`, `isDefined`, `isMirrored`, `isIdeographic`, `isISOControl`, `isJavaIdentifierStart`, `isJavaIdentifierPart`, `isUnicodeIdentifierStart`, `isUnicodeIdentifierPart`, `isIdentifierIgnorable`, `isEmoji`, `isEmojiPresentation`, `isEmojiModifier`, `isEmojiModifierBase`, `isEmojiComponent`, `isExtendedPictographic`, each test of a `char` or of a code point; `toChars` of a code point with or without an array to write into, `codePointAt` of an array, `reverseBytes`, `toString` of a code point, `charCount`, `isValidCodePoint`, `isBmpCodePoint`, `isSupplementaryCodePoint`, `isHighSurrogate`, `isLowSurrogate`, `isSurrogate`, `isSurrogatePair`, `toCodePoint`, `highSurrogate`, `lowSurrogate`; `MIN_RADIX`, `MAX_RADIX`, `SIZE`, `BYTES`, `MIN_CODE_POINT`, `MAX_CODE_POINT`, `MIN_SUPPLEMENTARY_CODE_POINT` and the bounds of the surrogates |
| `Number` | `intValue`, `longValue`, `floatValue`, `doubleValue`, `byteValue`, `shortValue` |
| `Math`, `StrictMath` | `PI`, `E`, `TAU`, `abs`, `max`, `min`, `sqrt`, `cbrt`, `pow`, `exp`, `log`, `log10`, `floor`, `ceil`, `rint`, `round`, `signum`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `tanh`, `hypot`, `toRadians`, `toDegrees`, `floorDiv`, `floorMod`, `random`, `addExact`, `subtractExact`, `multiplyExact`, `negateExact`, `toIntExact`, `absExact`, `incrementExact`, `decrementExact`, `divideExact`, `floorDivExact`, `ceilDivExact`, `ceilDiv`, `ceilMod`, `clamp`, `multiplyFull`, `multiplyHigh`, `unsignedMultiplyHigh`, `log1p`, `expm1`, `IEEEremainder`, `copySign`, `getExponent`, `nextAfter`, `ulp`, `nextUp`, `nextDown`, `fma`, `scalb` |
| `System` | `out`, `err` (each a `PrintStream`), `in` (an `InputStream`), `currentTimeMillis`, `nanoTime`, `exit`, `arraycopy`, `identityHashCode`, `lineSeparator`, `getProperty` with or without a default, `getenv` of a name, `gc` |
| `Runtime` | `Runtime.getRuntime`, `availableProcessors`, `gc`, `exit`, `halt` |
| `IO` | `println`, `print`, `readln` |
| `InputStream`, `OutputStream` | `read` of a byte or into an array, `readAllBytes`, `readNBytes`, `skip`, `skipNBytes`, `available`, `mark`, `reset`, `markSupported`, `transferTo`, `close`, `InputStream.nullInputStream`; `write` of a byte or of an array, `flush`, `close`, `OutputStream.nullOutputStream`; a class of the program extends either |
| `ByteArrayInputStream`, `ByteArrayOutputStream` | over a `byte[]` or a range of one; `toByteArray`, `size`, `reset`, `writeTo`, `writeBytes`, `toString` with or without a charset |
| `FilterInputStream`, `FilterOutputStream`, `BufferedInputStream`, `BufferedOutputStream` | a stream over another, the buffered ones with or without a size |
| `PushbackInputStream`, `SequenceInputStream` | with or without a size, `unread` of a byte or of an array; over two streams or an `Enumeration` of them |
| `DataInput`, `DataOutput`, `DataInputStream`, `DataOutputStream` | `readBoolean`, `readByte`, `readUnsignedByte`, `readShort`, `readUnsignedShort`, `readChar`, `readInt`, `readLong`, `readFloat`, `readDouble`, `readFully`, `skipBytes`, `readLine`, `readUTF`; the `write` of each, `writeBytes`, `writeChars`, `writeUTF`, `size` |
| `PrintStream` | over an `OutputStream`, with or without automatic flushing and a charset; `print` and `println` of each type, `printf` and `format` with or without a `Locale`, `append`, `write`, `writeBytes`, `flush`, `close`, `checkError`, `charset` |
| `Reader`, `Writer` | `read` of a character or into an array, `skip`, `ready`, `mark`, `reset`, `markSupported`, `transferTo`, `close`; `write` of a character, an array or a string, `append`, `flush`, `close`; `Reader.nullReader`, `Writer.nullWriter`; a class of the program extends either |
| `FilterReader`, `FilterWriter`, `CharArrayReader`, `CharArrayWriter`, `PushbackReader`, `LineNumberReader` | a reader or a writer over another; over a `char[]` or a range of one, `toCharArray`, `size`, `reset`, `writeTo`; with or without a size, `unread` of a character or of an array; `getLineNumber`, `setLineNumber` |
| `StringReader`, `StringWriter`, `InputStreamReader`, `OutputStreamWriter` | over a string; `getBuffer`, `toString`; over a stream, with or without a `Charset` or a charset's name, `getEncoding` |
| `BufferedReader`, `BufferedWriter`, `PrintWriter` | with or without a buffer size; `readLine`; `newLine`; a `PrintWriter` over a `Writer` or an `OutputStream`, with or without automatic flushing and a charset, with the members of `PrintStream` over characters |
| `Charset`, `StandardCharsets` | `UTF_8`, `ISO_8859_1`, `US_ASCII`, `UTF_16`, `UTF_16BE`, `UTF_16LE`; `Charset.forName`, `defaultCharset`, `isSupported`, `name`, `displayName`, `toString` |
| `Locale` | `ROOT`, `ENGLISH`, `US`, `UK`, `CANADA`, `Locale.getDefault` (which is `US`), `getLanguage`, `getCountry`, `toLanguageTag`, `toString` |
| `Iterable`, `Iterator` | `iterator`, `forEach`; `hasNext`, `next`, `remove`, `forEachRemaining` |
| `Collection` | `size`, `isEmpty`, `contains`, `add`, `remove`, `containsAll`, `addAll`, `removeAll`, `retainAll`, `removeIf`, `clear`, `toArray` of nothing, an array or a function making one, `iterator`, `forEach`, `equals`, `hashCode`, `toString` |
| `List`, `ArrayList` | the members of `Collection`; `get`, `set`, `add` and `addAll` at an index, `remove` at an index, `indexOf`, `lastIndexOf`, `subList`, `sort`, `replaceAll`, `getFirst`, `getLast`, `removeFirst`, `removeLast`, `addFirst`, `addLast`, `reversed`, `listIterator` with or without an index (a `ListIterator`: `hasNext`, `next`, `hasPrevious`, `previous`, `nextIndex`, `previousIndex`, `remove`, `set`, `add`); `List.of`, `List.copyOf`; `new ArrayList<>()` of nothing, a capacity or a collection, `ensureCapacity`, `trimToSize` |
| `Set`, `HashSet` | the members of `Collection`; `Set.of`, `Set.copyOf`; `new HashSet<>()` of nothing, a capacity or a collection |
| `Map`, `HashMap`, `Map.Entry` | `size`, `isEmpty`, `containsKey`, `containsValue`, `get`, `getOrDefault`, `put`, `putIfAbsent`, `putAll`, `remove`, `replace`, `clear`, `keySet`, `values`, `entrySet`, `forEach`, `replaceAll`, `computeIfAbsent`, `computeIfPresent`, `compute`, `merge`, `equals`, `hashCode`, `toString`; `Map.of` up to ten pairs, `Map.entry`, `Map.ofEntries`, `Map.copyOf`; `new HashMap<>()` of nothing, a capacity or a map; `getKey`, `getValue`, `setValue`, `Map.Entry.comparingByKey`, `comparingByValue`; `AbstractMap.SimpleEntry` and `SimpleImmutableEntry` of a key and a value or of an entry |
| `Queue`, `Deque`, `ArrayDeque` | the members of `Collection`; `offer`, `poll`, `peek`, `element`, `remove`; `addFirst`, `addLast`, `offerFirst`, `offerLast`, `removeFirst`, `removeLast`, `pollFirst`, `pollLast`, `getFirst`, `getLast`, `peekFirst`, `peekLast`, `push`, `pop`, `descendingIterator` |
| `AbstractCollection`, `AbstractList`, `AbstractSet`, `AbstractMap` | what a class of the program extends to be a collection |
| `Vector`, `Stack`, `Enumeration` | the members of `ArrayList`; `addElement`, `elementAt`, `firstElement`, `lastElement`, `setElementAt`, `insertElementAt`, `removeElementAt`, `removeElement`, `removeAllElements`, `elements`; `push`, `pop`, `peek`, `empty`, `search`; `hasMoreElements`, `nextElement` |
| `Hashtable` | the members of `Map`; `keys`, `elements`, `contains`; no `null` key or value |
| `Arrays` | `asList`, `toString`, `fill`, `equals`, `copyOf`, `copyOfRange`, `sort` of a whole array or a range, with or without a comparator, `binarySearch`; over arrays of `int`, `long`, `double`, `char`, `boolean` and of objects; `toString`, `fill`, `equals`, `copyOf` and `copyOfRange` of a `byte[]`; `toString` and `equals` of a `short[]` and a `float[]`; `hashCode` of every array; `setAll` of an `int[]`, a `long[]`, a `double[]` and an array of objects; `deepToString`, `deepEquals`, `deepHashCode` |
| `Collections` | `sort`, `binarySearch` with or without a comparator, `reverse`, `shuffle`, `swap`, `max`, `min`, `frequency`, `addAll`, `fill`, `nCopies`, `emptyList`, `emptySet`, `emptyMap`, `singletonList`, `singleton`, `unmodifiableList`, `unmodifiableSet`, `unmodifiableCollection`, `unmodifiableMap`, `synchronizedList`, `synchronizedSet`, `synchronizedCollection`, `synchronizedMap`, `reverseOrder`, `rotate`, `replaceAll`, `indexOfSubList`, `lastIndexOfSubList` |
| `Objects` | `equals`, `hashCode`, `hash`, `toString`, `isNull`, `nonNull`, `requireNonNull` with or without a message or a supplier of one, `requireNonNullElse`, `requireNonNullElseGet`, `compare`, `checkIndex`, `checkFromIndexSize`, `checkFromToIndex` |
| `Optional` | `empty`, `of`, `ofNullable`, `isPresent`, `isEmpty`, `get`, `orElse`, `orElseGet`, `orElseThrow`, `ifPresent`, `ifPresentOrElse`, `filter`, `map`, `flatMap`, `or`, `equals`, `hashCode`, `toString` |
| `OptionalLong` | `empty`, `of`, `isPresent`, `isEmpty`, `getAsLong`, `orElse`, `orElseGet`, `orElseThrow`, `ifPresent`, `ifPresentOrElse`, `equals`, `hashCode`, `toString` |
| `Random` | `new Random()`, `new Random(long)`, `setSeed`, `nextInt()`, `nextInt(int)`, `nextLong`, `nextDouble`, `nextBoolean`, `nextGaussian`; a seed gives the specified sequence |
| `Comparator` | `compare`, `reversed`, `thenComparing` of a comparator, of a key or of a key and its comparator, `thenComparingInt`, `thenComparingLong`, `thenComparingDouble`, `Comparator.naturalOrder`, `reverseOrder`, `comparing` with or without a comparator of the key, `comparingInt`, `comparingLong`, `comparingDouble`, `nullsFirst`, `nullsLast` |
| Exceptions | `Throwable`, `Exception`, `Error`, `RuntimeException`, `IllegalArgumentException`, `IllegalStateException`, `ArithmeticException`, `NullPointerException`, `ClassCastException`, `IndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException`, `StringIndexOutOfBoundsException`, `NegativeArraySizeException`, `UnsupportedOperationException`, `NumberFormatException`, `CloneNotSupportedException`, `InterruptedException`, `IllegalMonitorStateException`, `IllegalThreadStateException`, `NoSuchElementException`, `InputMismatchException`, `ConcurrentModificationException`, `EmptyStackException`, `MatchException`, `ArrayStoreException`, `SecurityException`, `AssertionError`, `VirtualMachineError`, `StackOverflowError`, `OutOfMemoryError`, `LinkageError`, `NoClassDefFoundError`, `ExceptionInInitializerError` with `getException`, `IOException`, `EOFException`, `FileNotFoundException`, `UTFDataFormatException`, `UnsupportedEncodingException`, `UncheckedIOException`, `UnsupportedCharsetException`, `IllegalCharsetNameException`: the constructors of the API -- of no argument, of a message, of a message and a cause, of a cause, of an index --, `getMessage`, `getLocalizedMessage`, `getCause`, `initCause`, `addSuppressed`, `getSuppressed`, `printStackTrace()`, `getStackTrace`, `setStackTrace`, `fillInStackTrace`, `toString`; `StackTraceElement` with its constructor of four arguments, `getClassName`, `getMethodName`, `getFileName`, `getLineNumber`, `isNativeMethod`. `IllegalFormatException` and three subclasses the run time raises, with no constructor: `IllegalFormatConversionException`, `UnknownFormatConversionException`, `MissingFormatArgumentException`. Of `java.util.concurrent`: `ExecutionException`, `TimeoutException`, `CancellationException`, `RejectedExecutionException`, `CompletionException`, `BrokenBarrierException` |
| `Runnable`, `Comparable`, `AutoCloseable`, `Closeable`, `Flushable` | `run`; `compareTo`, which a `String`, a box and an enum constant have too; `close`; `flush` |
| `Thread` | the constructors of nothing, a `Runnable`, a name or both; a class extending it; `start`, `run`, `join()`, `join(long)`, `interrupt`, `isInterrupted`, `isAlive`, `getName`, `setName`, `isDaemon`, `setDaemon`, `getPriority`, `setPriority`, `isVirtual`, `threadId`, `getId`, `toString`; `Thread.currentThread`, `sleep(long)`, `sleep(long, int)`, `sleep(Duration)`, `yield`, `onSpinWait`, `interrupted`, `holdsLock`, `startVirtualThread`; `Thread.ofPlatform()` and `ofVirtual()` with `name`, `daemon`, `priority`, `start`, `unstarted`, `factory`; `getState` and `Thread.State`; `setUncaughtExceptionHandler`, `getUncaughtExceptionHandler`, `Thread.setDefaultUncaughtExceptionHandler`, `getDefaultUncaughtExceptionHandler` |
| `ThreadLocal`, `InheritableThreadLocal` | `new ThreadLocal<>()`, `withInitial`, `get`, `set`, `remove`, `initialValue`; `childValue` |
| `AtomicInteger`, `AtomicLong` | `get`, `set`, `lazySet`, `getAndSet`, `compareAndSet`, `getAndAdd`, `addAndGet`, `getAndIncrement`, `getAndDecrement`, `incrementAndGet`, `decrementAndGet`, `getAndUpdate`, `updateAndGet`, `getAndAccumulate`, `accumulateAndGet`, `intValue`, `longValue`, `doubleValue`, `toString` |
| `AtomicBoolean`, `AtomicReference` | `get`, `set`, `lazySet`, `getAndSet`, `compareAndSet`, `toString`; of a reference also `getAndUpdate`, `updateAndGet`, `getAndAccumulate`, `accumulateAndGet` |
| `AtomicIntegerArray`, `AtomicLongArray`, `AtomicReferenceArray`, `LongAdder` | of a length or an array; the members of the atomic of one value, each with an index, `length`, `toString`; `add`, `increment`, `decrement`, `sum`, `reset`, `sumThenReset`, `intValue`, `longValue`, `doubleValue`, `toString` |
| `LongAccumulator`, `DoubleAdder`, `DoubleAccumulator` | of a function and its identity; `accumulate`, `get`, `reset`, `getThenReset`; `add`, `sum`, `reset`, `sumThenReset`; `intValue`, `longValue`, `floatValue`, `doubleValue`, `toString` |
| `TimeUnit` | the seven units; `convert`, `toNanos`, `toMicros`, `toMillis`, `toSeconds`, `toMinutes`, `toHours`, `toDays`, `sleep`, `timedJoin`, `timedWait` |
| `Lock`, `ReentrantLock`, `Condition` | `lock`, `lockInterruptibly`, `tryLock` with or without a limit, `unlock`, `newCondition`, `isLocked`, `isHeldByCurrentThread`, `getHoldCount`, `isFair`, `hasQueuedThreads`, `getQueueLength`, `toString`; `await` with or without a limit, `awaitNanos`, `awaitUninterruptibly`, `signal`, `signalAll` |
| `ReadWriteLock`, `ReentrantReadWriteLock` | `readLock` and `writeLock`, each a `Lock`, the write lock with conditions; `getReadLockCount`, `getReadHoldCount`, `getWriteHoldCount`, `isWriteLocked`, `isWriteLockedByCurrentThread`, `isFair`, `hasQueuedThreads`, `getQueueLength`, `toString` |
| `StampedLock` | `writeLock`, `readLock`, each also `Interruptibly` and as `try..` with or without a limit; `tryOptimisticRead`, `validate`; `unlockWrite`, `unlockRead`, `unlock`, `tryUnlockWrite`, `tryUnlockRead`; `tryConvertToWriteLock`, `tryConvertToReadLock`, `tryConvertToOptimisticRead`; `isWriteLocked`, `isReadLocked`, `getReadLockCount`, `isLockStamp`, `isWriteLockStamp`, `isReadLockStamp`, `isOptimisticReadStamp`; `asReadLock`, `asWriteLock`, `asReadWriteLock`; `toString`. A stamp is the number a JDK 25 gives |
| `CountDownLatch`, `Semaphore` | `await` with or without a limit, `countDown`, `getCount`; `acquire`, `acquireUninterruptibly`, `tryAcquire` with or without a limit, `release`, each of one permit or several, `availablePermits`, `drainPermits`, `isFair`; `toString` of both |
| `CyclicBarrier`, `Exchanger` | of a number of threads, with or without an action; `await` with or without a limit, `reset`, `isBroken`, `getParties`, `getNumberWaiting`; `exchange` with or without a limit |
| `Phaser` | of nothing, a number of parties, a parent or both; `register`, `bulkRegister`, `arrive`, `arriveAndDeregister`, `arriveAndAwaitAdvance`, `awaitAdvance`, `awaitAdvanceInterruptibly` with or without a limit, `forceTermination`, `getPhase`, `getRegisteredParties`, `getArrivedParties`, `getUnarrivedParties`, `getParent`, `getRoot`, `isTerminated`, `toString`; `onAdvance` to override |
| `BlockingQueue`, `ArrayBlockingQueue`, `LinkedBlockingQueue`, `SynchronousQueue`, `ConcurrentLinkedQueue` | the members of `Queue`; of a blocking queue also `put`, `take`, `offer` and `poll` with a limit, `remainingCapacity`, `drainTo`; a `SynchronousQueue` holds nothing and hands an element from the thread putting it to the one taking it |
| `TransferQueue`, `LinkedTransferQueue` | the members of a blocking queue; `transfer`, `tryTransfer` with or without a limit, `hasWaitingConsumer`, `getWaitingConsumerCount` |
| `BlockingDeque`, `LinkedBlockingDeque`, `ConcurrentLinkedDeque` | the members of `Deque`; of a blocking deque also those of a blocking queue, and at either end `putFirst`, `putLast`, `takeFirst`, `takeLast`, `offerFirst`, `offerLast`, `pollFirst` and `pollLast` with a limit |
| `ConcurrentMap`, `ConcurrentHashMap` | the members of `Map`; `remove` of a key and its value, `replace` of an old value, `mappingCount`, `contains`, `keys`, `elements`, `keySet` of a value, `ConcurrentHashMap.newKeySet`; of nothing, a capacity or a map; no `null` key or value |
| `CopyOnWriteArrayList`, `CopyOnWriteArraySet` | the members of `List`, or of `Set`; `addIfAbsent`, `addAllAbsent`; an iterator walks the list there was when it was made and changes nothing |
| `Executor`, `ExecutorService`, `Executors` | `execute`, `submit` of a `Callable` or a `Runnable`, `invokeAll`, `invokeAny` with or without a limit, `shutdown`, `shutdownNow`, `isShutdown`, `isTerminated`, `awaitTermination`, `close`; `newFixedThreadPool`, `newSingleThreadExecutor`, `newCachedThreadPool`, `newScheduledThreadPool`, `newSingleThreadScheduledExecutor`, each with or without a `ThreadFactory`, `newVirtualThreadPerTaskExecutor`, `newThreadPerTaskExecutor`, `unconfigurableExecutorService`, `unconfigurableScheduledExecutorService`, `defaultThreadFactory`, `callable` |
| `ThreadPoolExecutor`, `RejectedExecutionHandler` | of a core size, a most size, a time and a queue, with or without a `ThreadFactory`, a handler or both; `AbortPolicy`, `CallerRunsPolicy`, `DiscardPolicy`, `DiscardOldestPolicy`; `getPoolSize`, `getCorePoolSize`, `getMaximumPoolSize`, `getLargestPoolSize`, `getActiveCount`, `getTaskCount`, `getCompletedTaskCount`, `getKeepAliveTime`, `getQueue`, `getThreadFactory`, `getRejectedExecutionHandler`, `setRejectedExecutionHandler`, `setCorePoolSize`, `setMaximumPoolSize`, `setKeepAliveTime`, `setThreadFactory`, `allowCoreThreadTimeOut`, `allowsCoreThreadTimeOut`, `prestartCoreThread`, `prestartAllCoreThreads`, `remove`, `purge`, `isTerminating`, `toString`; `beforeExecute`, `afterExecute` and `terminated` to override |
| `ScheduledExecutorService`, `ScheduledThreadPoolExecutor`, `ScheduledFuture`, `Delayed` | `schedule` of a `Runnable` or a `Callable`, `scheduleAtFixedRate`, `scheduleWithFixedDelay`; of a core size, with or without a `ThreadFactory`; `setRemoveOnCancelPolicy`, `setExecuteExistingDelayedTasksAfterShutdownPolicy`, `setContinueExistingPeriodicTasksAfterShutdownPolicy` and what reads them; `getDelay`, `compareTo` |
| `ForkJoinPool`, `ForkJoinTask`, `RecursiveTask`, `RecursiveAction`, `CountedCompleter` | `new ForkJoinPool()`, of a parallelism, `ForkJoinPool.commonPool`; `invoke`, `execute`, `submit`, `getParallelism`, `getCommonPoolParallelism`, `getPoolSize` and the members of `ExecutorService`; `fork`, `join`, `invoke`, `invokeAll`, `get` with or without a limit, `cancel`, `isDone`, `isCancelled`, `isCompletedNormally`, `isCompletedAbnormally`, `getException`, `complete`, `completeExceptionally`, `quietlyComplete`, `quietlyJoin`, `quietlyInvoke`, `getRawResult`, `ForkJoinTask.adapt`, `inForkJoinPool`, `getPool`; `compute`; of a `CountedCompleter` also `tryComplete`, `propagateCompletion`, `onCompletion`, `onExceptionalCompletion`, `getCompleter`, `getRoot`, `getPendingCount`, `setPendingCount`, `addToPendingCount`, `compareAndSetPendingCount`, `decrementPendingCountUnlessZero`, `firstComplete`, `nextComplete`, `quietlyCompleteRoot`, `helpComplete` |
| `Timer`, `TimerTask` | of nothing, a name, a daemon flag or both; `schedule` of a delay with or without a period, `scheduleAtFixedRate`, `cancel`, `purge`; `run`, `cancel`, `scheduledExecutionTime` |
| `Callable`, `Future`, `FutureTask`, `ThreadFactory` | `call`; `get` with or without a limit, `isDone`, `cancel`, `isCancelled`, `state`, `resultNow`, `exceptionNow`; `run`, `toString`; `newThread` |
| `CompletableFuture`, `CompletionStage` | `supplyAsync` and `runAsync` with or without an executor, `completedFuture`, `failedFuture`, `completedStage`, `failedStage`, `allOf`, `anyOf`; `thenApply`, `thenAccept`, `thenRun`, `thenCompose`, `handle`, `whenComplete`, `thenCombine`, `thenAcceptBoth`, `runAfterBoth`, `applyToEither`, `acceptEither`, `runAfterEither`, `exceptionally`, `exceptionallyCompose`, each also `Async` with or without an executor; `complete`, `completeExceptionally`, `completeAsync`, `obtrudeValue`, `obtrudeException`, `cancel`, `orTimeout`, `completeOnTimeout`, `delayedExecutor`, `copy`, `minimalCompletionStage`, `newIncompleteFuture`, `defaultExecutor`, `join`, `get` with or without a limit, `getNow`, `isDone`, `isCancelled`, `isCompletedExceptionally`, `getNumberOfDependents`, `state`, `resultNow`, `exceptionNow`, `toString` |
| `java.util.function` | `Function`, `BiFunction`, `UnaryOperator`, `BinaryOperator`, `Supplier`, `Consumer`, `BiConsumer`, `Predicate`, `BiPredicate`, `BooleanSupplier`; for `Int`, `Long` and `Double`: `IntFunction`, `IntPredicate`, `IntUnaryOperator`, `IntBinaryOperator`, `IntSupplier`, `IntConsumer`, `ToIntFunction`, `ToIntBiFunction`, `ObjIntConsumer`; from one of the three to another, `IntToLongFunction`, `IntToDoubleFunction`, `LongToIntFunction`, `LongToDoubleFunction`, `DoubleToIntFunction`, `DoubleToLongFunction`; each with its abstract method and its `andThen`, `compose`, `and`, `or`, `negate`, `identity`, `not`, `isEqual` |
| `Duration` | `ZERO`; `ofDays`, `ofHours`, `ofMinutes`, `ofSeconds` with or without nanoseconds, `ofMillis`, `ofNanos`, `parse`; `getSeconds`, `getNano`, `isZero`, `isNegative`, `isPositive`; `plus` and `minus` of a `Duration`, `plusDays` .. `plusNanos`, `minusDays` .. `minusNanos`, `multipliedBy`, `negated`, `abs`, `withSeconds`, `withNanos`; `toDays`, `toHours`, `toMinutes`, `toSeconds`, `toMillis`, `toNanos`, `toDaysPart` .. `toNanosPart`; `compareTo`, `equals`, `hashCode`, `toString`; `DateTimeException`, `DateTimeParseException` |
| `URI`, `URISyntaxException` | `new URI(String)`, `URI.create`; `getScheme`, `getSchemeSpecificPart`, `getAuthority`, `getUserInfo`, `getHost`, `getPort`, `getPath`, `getQuery`, `getFragment` and the `getRaw..` of each; `isAbsolute`, `isOpaque`, `resolve` of a `URI` or a string, `equals`, `hashCode`, `compareTo`, `toString`; `getInput`, `getReason`, `getIndex` |
| `HttpClient` | `newHttpClient`; `newBuilder` with `version`, `followRedirects`, `executor`, `connectTimeout`, `build`; `send`, `sendAsync`, `followRedirects`, `version`, `executor`, `connectTimeout`, `close`, `shutdown`, `shutdownNow`, `isTerminated`; `HttpClient.Version`, `HttpClient.Redirect` |
| `HttpRequest` | `newBuilder` with or without a `URI`, with `uri`, `header`, `headers`, `setHeader`, `GET`, `POST`, `PUT`, `DELETE`, `HEAD`, `method`, `version`, `expectContinue`, `timeout`, `copy`, `build`; `method`, `uri`, `headers`, `bodyPublisher`, `version`, `expectContinue`, `timeout`, `equals`, `hashCode`, `toString`; `BodyPublishers.ofString` with or without a `Charset`, `ofByteArray` of an array or a range, `ofInputStream`, `noBody`; `BodyPublisher.contentLength` |
| `HttpResponse` | `statusCode`, `headers`, `body`, `uri`, `request`, `previousResponse`, `version`, `toString`; `BodyHandlers.ofString` with or without a `Charset`, `ofByteArray`, `ofInputStream`, `discarding`, `replacing`; `BodySubscribers` of the same and `mapping`; a `BodyHandler` of the program's own choosing one by the `ResponseInfo` (`statusCode`, `headers`, `version`) |
| `HttpHeaders` | `firstValue`, `firstValueAsLong`, `allValues`, `map`, `HttpHeaders.of`, `equals`, `hashCode`, `toString` |
| `HttpTimeoutException`, `HttpConnectTimeoutException` | of a message; what `send` throws, and what the future of `sendAsync` completes with, when a time limit passes |
| `HttpServer`, `HttpContext`, `HttpHandler` | `HttpServer.create` of nothing or of an address and a backlog, `bind`, `createContext` with or without a handler, `removeContext` of a path or a context, `setExecutor`, `getExecutor`, `start`, `stop`, `getAddress`; `getPath`, `getHandler`, `setHandler`, `getServer`, `getAttributes`; `handle` |
| `HttpExchange` | `getRequestMethod`, `getRequestURI`, `getProtocol`, `getRequestHeaders`, `getRequestBody`, `getResponseHeaders`, `sendResponseHeaders`, `getResponseBody`, `getResponseCode`, `close`, `getHttpContext`, `getRemoteAddress`, `getLocalAddress`, `getAttribute`, `setAttribute`, `setStreams` |
| `Headers` | the members of `Map`; `getFirst`, `add`, `set`; `new Headers()` of nothing or a map, `Headers.of` of names and values in turn or of a map |
| `InetSocketAddress`, `InetAddress`, `BindException` | of a port, of a host and a port, of an `InetAddress` and a port, `createUnresolved`; `getPort`, `getHostString`, `getHostName`, `getAddress`, `isUnresolved`, `equals`, `hashCode`, `toString`; `getHostAddress`, `getHostName`, `isLoopbackAddress`, `isAnyLocalAddress`, `InetAddress.getLoopbackAddress` |

`String.format`, `formatted` and `printf` take `%d`, `%s`, `%S`, `%f`, `%e`, `%E`, `%x`,
`%X`, `%o`, `%c`, `%b`, `%h`, `%H`, `%n` and `%%`, with the flags `-`, `0`, `+`, a space, `,`, `(` and
`#`, a width, a precision and an argument index (`%2$s`).

`java.util.regex.Pattern` and `Matcher` take the syntax the `Pattern` documentation
describes -- classes with unions and intersections, groups (capturing, named, atomic),
lookahead and lookbehind, back references, greedy, reluctant and possessive quantifiers,
the inline flags, `\\p{..}` properties, `\\X` and `\\b{g}` -- and so do `String.matches`,
`replaceAll`, `replaceFirst` and `split`. A malformed pattern is a
`PatternSyntaxException` with a JDK's message. A `Matcher` searches a region
(`region`, `useAnchoringBounds`, `useTransparentBounds`), tells whether a search read the
end of the input (`hitEnd`, `requireEnd`), builds a replacement match by match
(`appendReplacement`, `appendTail`, `replaceAll` of a function) and keeps a match
(`toMatchResult`). See [deviations](deviations.md).

The streams of `java.io` are over arrays, strings, the standard streams and each other;
there are no files. A `PrintStream`, a `PrintWriter` and the two bridges between bytes and
characters take `UTF-8` (the default), `ISO-8859-1`, `US-ASCII`, `UTF-16`, `UTF-16BE` or
`UTF-16LE`.

```java
void main() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeInt(2026);
        out.writeUTF("naïve");
    }
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    IO.println(bytes.size() + " bytes: " + in.readInt() + " " + in.readUTF());
    BufferedReader lines = new BufferedReader(new StringReader("first\nsecond"));
    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
        IO.println(line.toUpperCase());
    }
}
```

```
12 bytes: 2026 naïve
FIRST
SECOND
```

A `HashMap`, a `HashSet` and a `Hashtable` find a key by its `equals` and `hashCode`, and
walk their keys in the order a JDK's do.

```java
record Point(int x, int y) {
}

class Grid implements Iterable<Point> {
    private final List<Point> points = new ArrayList<>();

    Grid add(int x, int y) {
        points.add(new Point(x, y));
        return this;
    }

    public Iterator<Point> iterator() {
        return points.iterator();
    }
}

void main() {
    Map<Point, String> names = new HashMap<>();
    names.put(new Point(0, 0), "origin");
    Map<Integer, List<Point>> byRow = new HashMap<>();
    for (Point p : new Grid().add(0, 0).add(3, 1).add(1, 1)) {
        byRow.computeIfAbsent(p.y(), row -> new ArrayList<>()).add(p);
    }
    List<Point> row = byRow.get(1);
    row.sort(Comparator.comparingInt(Point::x));
    Set<String> words = new HashSet<>(Arrays.asList("to be or not to be".split(" ")));
    IO.println(names.get(new Point(0, 0)) + " " + byRow.keySet() + " " + row + " " + words);
    IO.println(String.format("%-6s|%5.2f|%04d|%x", "pi", Math.PI, 42, 255));
}
```

```
origin [0, 1] [Point[x=1, y=1], Point[x=3, y=1]] [not, be, or, to]
pi    | 3.14|0042|ff
```

```java
class Account {
    private int balance;

    void withdraw(int amount) {
        if (amount > balance) {
            throw new IllegalStateException("insufficient funds: " + (amount - balance));
        }
        balance -= amount;
    }

    void deposit(int amount) {
        balance += amount;
    }
}

void main() {
    Account account = new Account();
    account.deposit(100);
    Map<String, Integer> attempts = new HashMap<>();
    for (int amount : new int[] {30, 100, 50}) {
        try {
            account.withdraw(amount);
            attempts.put("ok", attempts.getOrDefault("ok", 0) + 1);
        } catch (IllegalStateException e) {
            IO.println(e.getMessage());
            attempts.put("refused", attempts.getOrDefault("refused", 0) + 1);
        } finally {
            IO.println("tried " + amount);
        }
    }
    IO.println(attempts.get("ok") + " ok, " + attempts.get("refused") + " refused, " + attempts.get("other"));
}
```

```
tried 30
insufficient funds: 30
tried 100
tried 50
2 ok, 1 refused, null
```

## Threads

A `Thread` is started, joined, interrupted and named as the language says. `synchronized`
holds the monitor of an object, `wait` and `notify` hand it from one thread to another,
and a `volatile` field is read as the last write left it. What a thread leaves uncaught is
written to standard error with the thread's name and its causes, and ends that thread alone. The program
ends when every thread that is no daemon has.

```java
class Counter {
    private int count;

    synchronized void add() {
        count++;
    }

    synchronized int get() {
        return count;
    }
}

void main() throws InterruptedException {
    Counter counter = new Counter();
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
        Thread worker = new Thread(() -> {
            for (int k = 0; k < 1000; k++) {
                counter.add();
            }
        }, "worker-" + i);
        workers.add(worker);
        worker.start();
    }
    for (Thread worker : workers) {
        worker.join();
    }
    IO.println(counter.get() + " after " + workers.get(3).getName());
}
```

```
4000 after worker-3
```

On the interpreter and the JVM a thread is a virtual thread of the host, and threads run
in parallel. A `.wasm` has one stack, and its threads take turns on it: a thread runs until
it waits, sleeps, yields or ends, and goes on from there at a later turn. A program whose
threads share what they share under a monitor, a `volatile` field or an atomic prints the
same on every backend; what the turns change for any other is in
[Deviations](deviations.md#threads).

`java.util.concurrent` has locks, latches, barriers, queues, a concurrent map, executors
-- a pool of a program's own, a scheduled one, a fork/join pool -- and `CompletableFuture`,
and `java.util` a `Timer`, all written over the same monitors. `Vector`, `Hashtable`, `StringBuffer`
and the wrappers of `Collections.synchronizedList` and the like hold their own monitor
in every member.

```java
void main() {
    Map<String, Integer> lengths = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (String word : List.of("one", "three", "seven")) {
            pool.execute(() -> lengths.put(word, word.length()));
        }
    }
    CompletableFuture<Integer> total = CompletableFuture.supplyAsync(() -> lengths.get("three"))
        .thenApply(n -> n + lengths.size());
    IO.println(lengths + " " + total.join());
}
```

```
{one=3, seven=5, three=5} 8
```

## HTTP

`java.net.http` has the client. An `HttpClient` sends an `HttpRequest` and answers an
`HttpResponse` whose body is what the handler makes of the reply: `send` once the reply is
in, `sendAsync` as a `CompletableFuture`. Every request goes through
[`rontolisp:fetch`](../guides/http-fetch.md), so one source runs on every transport fetch
has:

| Target | Build | Transport |
|---|---|---|
| Interpreter, JVM | `rontolisp App.java`, `-o App.class` | the JDK's `HttpClient` |
| WASM component | `-o app.wasm --component`, run with `wasmtime run -S http=y` | `wasi:http` |
| Native executable | `--native` | the executable's own client |

A plain Preview 1 module has no transport: the compile refuses the program, naming the
flags that give it one. `java.net` is part of `java.base`; a compact source file imports
the client with `import module java.net.http;`.

```java
import module java.net.http;

void main() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://httpbin.ik.am/get"))
        .header("Accept", "application/json")
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    IO.println(response.statusCode() + " " + response.headers().firstValue("content-type").orElse("none"));
    HttpRequest missing = HttpRequest.newBuilder(URI.create("https://httpbin.ik.am/status/404")).build();
    IO.println(client.sendAsync(missing, HttpResponse.BodyHandlers.discarding())
        .thenApply(HttpResponse::statusCode)
        .join());
}
```

```
200 application/json
404
```

A builder refuses what a JDK's refuses, with its message: a restricted or malformed header,
a URI that is no absolute `http` or `https` one. `followRedirects(Redirect.NORMAL)` and
`ALWAYS` follow a 301, 302, 303, 307 and 308 as a JDK's client does, and
`previousResponse()` answers the replies on the way. A connection that is refused is a
`ConnectException`. `HttpRequest.Builder.timeout` limits how long a reply may take to start
and `HttpClient.Builder.connectTimeout` how long a connection may take, each a
`java.time.Duration`: a request that passes one is an `HttpTimeoutException` or an
`HttpConnectTimeoutException`. What the transports cannot give and what is not there:
[Deviations](deviations.md#the-http-client).

`com.sun.net.httpserver` has the server: an `HttpServer`, its contexts, and an `HttpHandler`
answering an `HttpExchange`. It serves through the transport of the target, so one source
is a server on each:

| Target | Build | Who listens |
|---|---|---|
| Interpreter, JVM | `rontolisp App.java`, `-o App.class` | the program, on the address `create` was given |
| WASM component | `-o app.wasm --component`, run with `wasmtime serve -S cli=y app.wasm` | the host (`--addr`), which calls the program for each request through `wasi:http` |
| Native executable | `--native` | the executable, on the address `create` was given |

A plain Preview 1 module cannot listen: `create` throws an `IOException` naming
`--component`. A compact source file imports the server with `import module jdk.httpserver;`.

A program lives while a server it started runs: `main` returns, and requests are answered
until `stop`. Under `wasmtime serve` the host starts the program -- `main`, up to its end --
for the requests it likes, and the address of `create` is not bound.
`sendResponseHeaders(status, length)` announces a body of that length, `-1` none, and `0` a
body of a length not told ahead, which goes out chunk by chunk as it is written where the
transport can (under `wasmtime serve`), and whole elsewhere.

This program serves one context and asks it once, so it runs where the target has threads,
the interpreter and the JVM:

```java
import module java.net.http;
import module jdk.httpserver;

void main() throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hello", exchange -> {
        String name = exchange.getRequestURI().getQuery();
        byte[] body = ("Hello, " + name + "!").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    });
    server.start();

    URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hello?Ann");
    HttpResponse<String> response = HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    IO.println(response.statusCode() + " " + response.body());
    server.stop(0);
}
```

```
200 Hello, Ann!
```

A server on its own, and how to run it on each target, is
[`examples/java/http`](https://github.com/making/rontolisp/tree/develop/examples/java/http).
What the transports cannot give and what is not there:
[Deviations](deviations.md#the-http-server).

What differs from the language and what is refused: [Deviations](deviations.md).
