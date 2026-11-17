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
class, and every library class is in scope without an import.

## What is read

| | |
|---|---|
| Types | `int`, `long`, `double`, `boolean`, `char`, `String`, classes, interfaces, arrays; `var` for a local |
| Declarations | classes with `extends` and `implements`, abstract classes and methods, interfaces with abstract methods, fields, static fields, constructors with `this(..)` and `super(..)`, overloaded methods, `package` and `import` |
| Statements | local variables, `if`, `while`, `do`, `for`, the enhanced `for` over an array or a list, `break`, `continue`, `return`, `throw`, `try` with `catch` and `finally` |
| Expressions | every operator of the primitive types with its conversions, string concatenation, the conditional, assignments and compound assignments, casts, `instanceof`, `new`, array creation and initializers, method calls, `super.m(..)`, lambdas for an interface of the program with one abstract method |

Annotations are read and dropped. An `int` and a `long` wrap as two's complement; a
`double` prints as the shortest text that reads back.

## The library

Each class has the members listed and no others; a missing one is refused by name.

| Class | Members |
|---|---|
| `Object` | `toString`, `equals`, `hashCode`, `getClass().getName()`, `getClass().getSimpleName()` |
| `String` | `length`, `charAt`, `substring`, `indexOf(String)`, `equals`, `isEmpty`, `toUpperCase`, `toLowerCase`, `hashCode`, `String.valueOf` |
| `StringBuilder` | `new StringBuilder()`, `new StringBuilder(String)`, `append`, `toString`, `length`, `charAt` |
| `Integer`, `Long`, `Double`, `Boolean`, `Character` | boxing and unboxing; `Integer.MAX_VALUE`, `MIN_VALUE`, `parseInt`, `toString(int)`, `valueOf(int)`, `intValue`; `Long.MAX_VALUE`, `MIN_VALUE` |
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
