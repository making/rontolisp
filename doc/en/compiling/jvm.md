# Compile to JVM Bytecode

```bash
rontolisp hello.lisp -o Hello.class
java Hello
```

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
