# Compile to JVM Bytecode

Give `rontolisp` an output path ending in `.class` with `-o`, and it compiles the
source straight to JVM bytecode instead of interpreting it -- no ASM or other
library, the bytecode is emitted by hand. The output extension is what selects the
backend (`.class` for JVM, `.wasm` for WASM).

```bash
rontolisp hello.lisp -o Hello.class
java Hello
```

The generated class is named after the output file, so the name you pass to
`java` is the file's stem: `-o Hello.class` produces a class `Hello` you run with
`java Hello`. Keep the path free of directories (use a plain `Hello.class`, not
`out/Hello.class`), since the class name must match. The program's top-level forms
become the class's entry point and run in order when you launch it.

Example (`hello.lisp`):

```lisp
(print (+ 1 2))
```

```
3
```

The generated `.class` file targets Java 6 (class version 50), so its bytecode
loads on any JRE 6+. Beyond `java.lang` and `java.io`, the emitted runtime
helpers reference `java.math` (`BigInteger`/`BigDecimal`/`MathContext`, for the
overflow-promoting integer and exact ratio arithmetic) and `java.util`
(`ArrayList`/`Arrays`, and `HashMap` for hash tables) -- all of which already
exist in Java 6. The one exception is a program that calls `rontolisp:fetch`: it
additionally references `java.net`/`java.net.http`, so such a program needs JRE
11+.
