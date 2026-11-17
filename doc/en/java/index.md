# A subset of the Java language (experimental)

**Experimental.** rontolisp reads a subset of the Java language and lowers it to the same
core forms as Common Lisp, so a `.java` program runs on every backend. It is a subset by
design and nothing here is a compatibility promise: a construct outside it is refused by
name, with its position, before anything runs. The front end and its library are written
for rontolisp from the published specifications; no JDK code is read, bundled or shipped
in a compiled program.

A `.java` file is read as this subset; `--source-language java` says so for any other file
and for `-e`. A program is one file.

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

## What is read

| | |
|---|---|
| Types | `int`, `long`, `double`, `boolean`, `char`, `String`, classes, interfaces, enums, records, arrays; `var` for a local |
| Declarations | classes with `extends` and `implements`, abstract classes and methods; enums with fields, constructors, methods and constants that have a body; records with compact and canonical constructors; interfaces with abstract, `default`, `static` and `private` methods and constants; `sealed`, `permits` and `non-sealed`; static nested, inner, local and anonymous classes; fields, static fields, static and instance initializer blocks; constructors with `this(..)` and `super(..)` and statements ahead of them; overloaded and variable arity methods; `package` and `import` |
| Statements | local variables (`final`, the unnamed `_`), `if`, `while`, `do`, `for`, the enhanced `for` over an array or a list, `switch` with colon or arrow cases, patterns and guards, labeled statements, `break` and `continue` with or without a label, `yield`, `return`, `throw`, `try` with `catch` and `finally` |
| Expressions | every operator of the primitive types with its conversions, string concatenation, the conditional, assignments and compound assignments, casts, `instanceof` with a type, a type pattern or a record pattern, `switch` expressions, `new`, `outer.new Inner()`, `Outer.this`, array creation with one or several lengths and initializers, text blocks, method calls, `super.m(..)`, lambdas for an interface of the program with one abstract method |

Annotations are read and dropped. An `int` and a `long` wrap as two's complement; a
`double` prints as the shortest text that reads back.

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

## The library

Each class has the members listed and no others; a missing one is refused by name.

| Class | Members |
|---|---|
| `Object` | `toString`, `equals`, `hashCode`, `getClass().getName()`, `getClass().getSimpleName()` |
| An enum | `values()`, `valueOf(String)`, `name`, `ordinal`, `compareTo`, `toString` |
| A record | the accessors, `equals`, `hashCode`, `toString` |
| An array | `length`, `clone()` |
| `String` | `length`, `charAt`, `substring`, `indexOf(String)`, `equals`, `isEmpty`, `toUpperCase`, `toLowerCase`, `hashCode`, `String.valueOf` |
| `StringBuilder` | `new StringBuilder()`, `new StringBuilder(String)`, `append`, `toString`, `length`, `charAt` |
| `Integer`, `Long`, `Double`, `Boolean`, `Character` | boxing and unboxing; `Integer.MAX_VALUE`, `MIN_VALUE`, `parseInt`, `toString(int)`, `valueOf(int)`, `intValue`; `Long.MAX_VALUE`, `MIN_VALUE`, `Long.hashCode(long)`; `Double.compare`; `Boolean.hashCode(boolean)` |
| `Math` | `sqrt`, `floor`, `pow`, `abs`, `max`, `min`, `PI`, `E` |
| `System.out`, `System.err`, `IO` | `println`, `print` |
| `ArrayList`, `List` | `new ArrayList<>()`, `add`, `get`, `set`, `size`, `isEmpty` |
| `HashMap`, `Map` | `new HashMap<>()`, `put`, `get`, `getOrDefault`, `containsKey`, `size`, `isEmpty`; keys of `String`, `Integer`, `Long`, `Character` or `Boolean` |
| Exceptions | `Throwable`, `Exception`, `Error`, `RuntimeException`, `IllegalArgumentException`, `IllegalStateException`, `ArithmeticException`, `NullPointerException`, `ClassCastException`, `IndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException`, `UnsupportedOperationException`, `NumberFormatException`: the constructors of no argument and of a message, `getMessage`, `toString` |
| `Runnable` | `run` |

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
