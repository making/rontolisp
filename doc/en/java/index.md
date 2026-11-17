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

`--no-gc` is refused: that backend has no structures, strings or closures. There is no
REPL: `--source-language java` with no program is an error.

[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) holds a
runnable program with its expected output.

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
program as `rontolisp app/Main.java` does.

```console
$ rontolisp app/Main.java
$ rontolisp app/Main.java -o main.wasm
```

An `import` names a class, a package on demand, `module java.base` (every class of the
library), or with `static` a static member of a class, or all of them on demand. A class
that is not `public` and a member with no access modifier are reached from their own
package only, a `protected` member from its package and from the classes under its class.

## What is read

| | |
|---|---|
| Types | `int`, `long`, `short`, `byte`, `char`, `double`, `float`, `boolean`, `String`, classes, interfaces, enums, records, arrays; `var` for a local; type arguments, wildcards (`?`, `? extends T`, `? super T`) and raw types |
| Declarations | classes with `extends` and `implements`, abstract classes and methods; classes, interfaces, records and methods with type parameters and their bounds; enums with fields, constructors, methods and constants that have a body; records with compact and canonical constructors; interfaces with abstract, `default`, `static` and `private` methods and constants; `sealed`, `permits` and `non-sealed`; static nested, inner, local and anonymous classes; fields, static fields, static and instance initializer blocks; constructors with `this(..)` and `super(..)` and statements ahead of them; overloaded and variable arity methods; `package` and `import` |
| Statements | local variables (`final`, the unnamed `_`), `if`, `while`, `do`, `for`, the enhanced `for` over an array or an `Iterable`, `switch` with colon or arrow cases, patterns and guards, labeled statements, `break` and `continue` with or without a label, `yield`, `return`, `throw`, `try` with `catch` (of one type or several) and `finally`, `try`-with-resources |
| Expressions | every operator of the primitive types with its conversions, string concatenation, the conditional, assignments and compound assignments, casts, `instanceof` with a type, a type pattern or a record pattern, `switch` expressions, `new`, `outer.new Inner()`, `Outer.this`, array creation with one or several lengths and initializers, text blocks, method calls with or without type arguments, `super.m(..)`, `new Box<>(..)`, lambdas and method references (`Type::name`, `expression::name`, `super::name`, `Type::new`, `int[]::new`) for any functional interface |

Annotations are read and dropped. An `int`, a `long`, a `short` and a `byte` wrap as two's
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
| `Object` | `new Object()`, `toString`, `equals`, `hashCode`, `getClass().getName()`, `getClass().getSimpleName()` |
| An enum | `values()`, `valueOf(String)`, `name`, `ordinal`, `compareTo`, `toString` |
| A record | the accessors, `equals`, `hashCode`, `toString` |
| An array | `length`, `clone()` |
| `String` | `length`, `charAt`, `isEmpty`, `isBlank`, `equals`, `equalsIgnoreCase`, `compareTo`, `compareToIgnoreCase`, `hashCode`, `substring`, `subSequence`, `indexOf` and `lastIndexOf` of a string or a character, `contains`, `startsWith`, `endsWith`, `toUpperCase`, `toLowerCase`, `trim`, `strip`, `stripLeading`, `stripTrailing`, `repeat`, `replace`, `concat`, `toCharArray`, `split`, `intern`, `formatted`; `String.valueOf`, `String.join`, `String.format`; `new String()`, `new String(String)`, `new String(char[])`, `new String(char[], int, int)` |
| `StringBuilder`, `StringBuffer` | the constructors of nothing, a capacity or a text; `append`, `insert`, `reverse`, `setLength`, `setCharAt`, `deleteCharAt`, `delete`, `replace`, `indexOf`, `toString`, `length`, `isEmpty`, `charAt`, `substring`, `subSequence` |
| `CharSequence` | `length`, `charAt`, `subSequence`, `isEmpty`; a `String` and a `StringBuilder` are one |
| `Integer`, `Long` | boxing and unboxing; `MAX_VALUE`, `MIN_VALUE`, `parseInt` / `parseLong` with or without a radix, `valueOf`, `toString` with or without a radix, `toBinaryString`, `toHexString`, `Integer.toOctalString`, `compare`, `signum`, `sum`, `max`, `min`, `bitCount`, `hashCode`, `intValue`, `longValue`, `doubleValue`, `compareTo` |
| `Byte`, `Short` | boxing and unboxing; `MAX_VALUE`, `MIN_VALUE`, `parseByte` / `parseShort` with or without a radix, `valueOf`, `toString`, `compare`, `hashCode`, `toUnsignedInt`, `byteValue` / `shortValue`, `intValue`, `longValue`, `floatValue`, `doubleValue`, `compareTo` |
| `Float` | `MAX_VALUE`, `MIN_VALUE`, `MIN_NORMAL`, `POSITIVE_INFINITY`, `NEGATIVE_INFINITY`, `NaN`, `parseFloat`, `valueOf`, `toString`, `compare`, `hashCode`, `floatToIntBits`, `isNaN`, `isInfinite`, `isFinite`, `sum`, `max`, `min`, `floatValue`, `doubleValue`, `intValue`, `longValue`, `compareTo` |
| `Double` | `MAX_VALUE`, `MIN_VALUE`, `POSITIVE_INFINITY`, `NEGATIVE_INFINITY`, `NaN`, `parseDouble`, `valueOf`, `toString`, `compare`, `hashCode`, `doubleToLongBits`, `isNaN`, `isInfinite`, `isFinite`, `sum`, `max`, `min`, `intValue`, `longValue`, `doubleValue`, `compareTo` |
| `Boolean`, `Character` | `Boolean.TRUE`, `FALSE`, `parseBoolean`, `valueOf`, `toString`, `hashCode`, `compare`, `logicalAnd`, `logicalOr`, `logicalXor`, `booleanValue`, `compareTo`; `Character.MAX_VALUE`, `MIN_VALUE`, `valueOf`, `toString`, `compare`, `hashCode`, `isDigit`, `isLetter`, `isLetterOrDigit`, `isAlphabetic`, `isWhitespace`, `isSpaceChar`, `isUpperCase`, `isLowerCase`, `toUpperCase`, `toLowerCase`, `getNumericValue`, `digit`, `forDigit`, `charValue`, `compareTo` |
| `Number` | `intValue`, `longValue`, `floatValue`, `doubleValue`, `byteValue`, `shortValue` |
| `Math` | `PI`, `E`, `abs`, `max`, `min`, `sqrt`, `cbrt`, `pow`, `exp`, `log`, `log10`, `floor`, `ceil`, `rint`, `round`, `signum`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `tanh`, `hypot`, `toRadians`, `toDegrees`, `floorDiv`, `floorMod`, `random`, `addExact`, `subtractExact`, `multiplyExact`, `negateExact`, `toIntExact` |
| `System` | `out`, `err` (`println`, `print`, `printf`, `format`, `flush`), `currentTimeMillis`, `nanoTime`, `exit`, `arraycopy`, `identityHashCode`, `lineSeparator` |
| `IO` | `println`, `print`, `readln` |
| `Iterable`, `Iterator` | `iterator`, `forEach`; `hasNext`, `next`, `remove`, `forEachRemaining` |
| `Collection` | `size`, `isEmpty`, `contains`, `add`, `remove`, `containsAll`, `addAll`, `removeAll`, `retainAll`, `removeIf`, `clear`, `toArray`, `iterator`, `forEach`, `equals`, `hashCode`, `toString` |
| `List`, `ArrayList` | the members of `Collection`; `get`, `set`, `add` and `addAll` at an index, `remove` at an index, `indexOf`, `lastIndexOf`, `subList`, `sort`, `replaceAll`, `getFirst`, `getLast`, `removeFirst`, `removeLast`, `addFirst`, `addLast`, `reversed`; `List.of`, `List.copyOf`; `new ArrayList<>()` of nothing, a capacity or a collection, `ensureCapacity`, `trimToSize` |
| `Set`, `HashSet` | the members of `Collection`; `Set.of`, `Set.copyOf`; `new HashSet<>()` of nothing, a capacity or a collection |
| `Map`, `HashMap`, `Map.Entry` | `size`, `isEmpty`, `containsKey`, `containsValue`, `get`, `getOrDefault`, `put`, `putIfAbsent`, `putAll`, `remove`, `replace`, `clear`, `keySet`, `values`, `entrySet`, `forEach`, `replaceAll`, `computeIfAbsent`, `computeIfPresent`, `compute`, `merge`, `equals`, `hashCode`, `toString`; `Map.of` up to ten pairs, `Map.entry`, `Map.ofEntries`, `Map.copyOf`; `new HashMap<>()` of nothing, a capacity or a map; `getKey`, `getValue`, `setValue`, `Map.Entry.comparingByKey`, `comparingByValue` |
| `Queue`, `Deque`, `ArrayDeque` | the members of `Collection`; `offer`, `poll`, `peek`, `element`, `remove`; `addFirst`, `addLast`, `offerFirst`, `offerLast`, `removeFirst`, `removeLast`, `pollFirst`, `pollLast`, `getFirst`, `getLast`, `peekFirst`, `peekLast`, `push`, `pop`, `descendingIterator` |
| `AbstractCollection`, `AbstractList`, `AbstractSet`, `AbstractMap` | what a class of the program extends to be a collection |
| `Vector`, `Stack`, `Enumeration` | the members of `ArrayList`; `addElement`, `elementAt`, `firstElement`, `lastElement`, `setElementAt`, `insertElementAt`, `removeElementAt`, `removeElement`, `removeAllElements`, `elements`; `push`, `pop`, `peek`, `empty`, `search`; `hasMoreElements`, `nextElement` |
| `Hashtable` | the members of `Map`; `keys`, `elements`, `contains`; no `null` key or value |
| `Arrays` | `asList`, `toString`, `fill`, `equals`, `copyOf`, `copyOfRange`, `sort` of a whole array or a range, with or without a comparator, `binarySearch`, `hashCode` of an `int[]` or an `Object[]`; over arrays of `int`, `long`, `double`, `char`, `boolean` and of objects |
| `Collections` | `sort`, `reverse`, `shuffle`, `swap`, `max`, `min`, `frequency`, `addAll`, `fill`, `nCopies`, `emptyList`, `emptySet`, `emptyMap`, `singletonList`, `singleton`, `unmodifiableList`, `unmodifiableSet`, `unmodifiableCollection`, `unmodifiableMap`, `reverseOrder` |
| `Objects` | `equals`, `hashCode`, `hash`, `toString`, `isNull`, `nonNull`, `requireNonNull`, `requireNonNullElse`, `compare`, `checkIndex` |
| `Optional` | `empty`, `of`, `ofNullable`, `isPresent`, `isEmpty`, `get`, `orElse`, `orElseGet`, `orElseThrow`, `ifPresent`, `ifPresentOrElse`, `filter`, `map`, `flatMap`, `or`, `equals`, `hashCode`, `toString` |
| `Random` | `new Random()`, `new Random(long)`, `setSeed`, `nextInt()`, `nextInt(int)`, `nextLong`, `nextDouble`, `nextBoolean`, `nextGaussian`; a seed gives the specified sequence |
| `Comparator` | `compare`, `reversed`, `thenComparing` of a comparator or a key, `thenComparingInt`, `Comparator.naturalOrder`, `reverseOrder`, `comparing` with or without a comparator of the key, `comparingInt`, `comparingLong`, `comparingDouble`, `nullsFirst`, `nullsLast` |
| Exceptions | `Throwable`, `Exception`, `Error`, `RuntimeException`, `IllegalArgumentException`, `IllegalStateException`, `ArithmeticException`, `NullPointerException`, `ClassCastException`, `IndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException`, `StringIndexOutOfBoundsException`, `NegativeArraySizeException`, `UnsupportedOperationException`, `NumberFormatException`, `CloneNotSupportedException`, `InterruptedException`, `NoSuchElementException`, `ConcurrentModificationException`, `EmptyStackException`, `MatchException`: the constructors of the API -- of no argument, of a message, of a message and a cause, of a cause, of an index --, `getMessage`, `getLocalizedMessage`, `getCause`, `initCause`, `addSuppressed`, `getSuppressed`, `printStackTrace()`, `toString`. `IllegalFormatException` and three subclasses the run time raises, with no constructor: `IllegalFormatConversionException`, `UnknownFormatConversionException`, `MissingFormatArgumentException` |
| `Runnable`, `Comparable`, `AutoCloseable` | `run`; `compareTo`, which a `String`, a box and an enum constant have too; `close` |
| `java.util.function` | `Function`, `BiFunction`, `UnaryOperator`, `BinaryOperator`, `Supplier`, `Consumer`, `BiConsumer`, `Predicate`, `BiPredicate`, `BooleanSupplier`; for `Int`, `Long` and `Double`: `IntFunction`, `IntPredicate`, `IntUnaryOperator`, `IntBinaryOperator`, `IntSupplier`, `IntConsumer`, `ToIntFunction`, `ToIntBiFunction`; each with its abstract method and its `andThen`, `compose`, `and`, `or`, `negate`, `identity`, `not`, `isEqual` |

`String.format`, `formatted` and `printf` take `%d`, `%s`, `%S`, `%f`, `%e`, `%E`, `%x`,
`%X`, `%o`, `%c`, `%b`, `%n` and `%%`, with the flags `-`, `0`, `+`, a space, `,`, `(` and
`#`, a width, a precision and an argument index (`%2$s`). `String.split` takes a text, or
one character class (`\\s`, `\\d`, `\\w`, `[,;]`, `[0-9]`) alone or followed by `+`.

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

What differs from the language and what is refused: [Deviations](deviations.md).
