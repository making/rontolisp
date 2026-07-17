# Compile to JVM Bytecode

Give `rontolisp` an output path ending in `.class` with `-o`, and it compiles the
source straight to JVM bytecode instead of interpreting it -- no ASM or other
library, the bytecode is emitted by hand. The output extension is what selects the
backend (`.class` for JVM, `.wasm` for WASM).

```bash
echo '(print (+ 1 2))' > hello.lisp
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

## Optimize (Dead-Code Elimination)

By default a compiled class embeds the **entire** runtime (printer, numeric, reader
and `eval` helper methods, plus a first-class wrapper for every built-in) regardless
of what the program actually uses. Add `--optimize` to drop every method unreachable
from `main`, along with any static field only they referenced, and compact the
constant pool accordingly:

```bash
rontolisp fact.lisp --optimize -o Fact.class
java Fact
```

For a small program like `fact` the class shrinks from ~46 KB to ~4.6 KB. The flag is
opt-in and behavior-preserving: reachability follows the actual `invoke` instructions
in the bytecode, so anything a first-class function value, `funcall`, or an embedded
`eval`/`load` can dispatch to is kept (the dispatch methods call every registered
function directly), and the `java:` interop bridge's reflective entry point survives
as an explicit root. The same flag also tree-shakes the
[WASM output](wasm.md).

Independently of `--optimize`, compilation always tree-shakes the bundled
Lisp-source libraries (`linalg:`, `vec:`, JSON, URL, `equalp`/`string<`): a
library function your program never mentions -- by name anywhere in the source,
including quoted symbols and string literals -- is not compiled in. The one
consequence: a library function whose name is only assembled at runtime from
computed strings and called through `eval`/`apply` signals the usual
"undefined function" error. Compile with `--no-prune` (or `--dynamic`) to keep
every library definition in that case.

The generated `.class` file targets Java 17 (class version 61), so running it
requires a Java 17 or newer JRE. Beyond `java.lang` and `java.io`, the emitted
runtime helpers reference `java.math` (`BigInteger`/`BigDecimal`/`MathContext`,
for the overflow-promoting integer and exact ratio arithmetic) and `java.util`
(`ArrayList`/`Arrays`, and `HashMap` for hash tables); a program that calls
`rontolisp:fetch` additionally references `java.net`/`java.net.http`, and
`rontolisp:await` / `rontolisp:futurep` represent futures as
`java.util.concurrent` futures -- all of which are part of Java 17, so none of
these raise the requirement. The one exception is a program that uses the
[`java:` interop package](../guides/java-interop.md): the compiler embeds a
reflection bridge (compiled with the project's own Java release) into the
class, so it needs a JRE at least as new as the one rontolisp was built with.
