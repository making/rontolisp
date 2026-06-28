# Compile to JVM Bytecode

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar hello.lisp -o Hello.class
java Hello
```

Example (`hello.lisp`):

```lisp
(print (+ 1 2))
```

```
3
```

The generated `.class` file targets Java 6 (class version 50) and depends only on `java.lang` and `java.io` standard library classes. It runs on any JRE 6+.
