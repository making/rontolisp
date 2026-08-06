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
echo '(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(print (fact 10))' > fact.lisp
rontolisp fact.lisp --optimize -o Fact.class
java Fact
```

For a small program like `fact` the class shrinks from ~46 KB to ~4.6 KB. The flag is
opt-in and behavior-preserving: reachability follows the actual `invoke` instructions
in the bytecode, so anything a first-class function value, `funcall`, or an embedded
`eval`/`load` can dispatch to is kept, and the `java:` interop bridge's reflective
entry point survives as an explicit root. The same flag also tree-shakes the
[WASM output](wasm.md).

The dispatch methods `funcall` goes through list only the functions your program
can actually obtain as a value — `#'name`, a quoted `'name` designator, a
`lambda` — so everything else becomes ordinary dead code the flag removes. That
listing switches off, and every function stays reachable, as soon as the program
can name a function at run time: any use of `eval`, `read`, `read-from-string`, a
runtime `load`, `intern`, `find-symbol`, `make-symbol`, `symbol-function`,
`fdefinition`, `fboundp` or `uiop:symbol-call` — including one inside a library
you loaded — as does `--dynamic`. `(intern name :keyword)` is exempt: it only
ever builds a keyword, which can never name a function. Compile with
`-Drontolisp.debug.dispatchgate=true` to have the compiler name the operator
responsible.

`--optimize` takes an optional level, shared with the [WASM backend](wasm.md):
`--optimize=default` is the bare flag written out, and `--optimize=size` asks
for the smallest output a backend can give. This backend accepts it and emits a
byte-for-byte identical class, because what that level declines are the wasm-GC
emissions that spend bytes on speed and there is no counterpart here -- the same
program's JVM bytecode is about a third the size of its WASM to begin with. So
one build script can pass `--optimize=size` for every target.

Independently of `--optimize`, compilation always tree-shakes the libraries it
splices in: the bundled Lisp-source ones (`linalg:`, `vec:`, JSON, URL,
`equalp`/`string<`) and every system loaded with
[`asdf:load-system` / `ql:quickload`](../guides/asdf-systems.md). A function,
variable or constant your program never mentions -- by name anywhere in the
source, including quoted symbols and string literals -- is not compiled in. Your
own code is never pruned, and neither is anything a `load`/`require` splices in:
only a library that came from a system is subject to it.

Classes, generic functions, methods, conditions and structures always stay,
because a `make-instance` can reach a method no source line names.

The one consequence: a library function whose name is only assembled at runtime
from computed strings and called through `eval`/`apply` signals the usual
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
