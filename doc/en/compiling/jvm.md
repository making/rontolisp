# Compile to JVM Bytecode

Give `rontolisp` an output path ending in `.class` with `-o`, and it compiles the
source straight to JVM bytecode instead of interpreting it -- no ASM or other
library, the bytecode is emitted by hand. The output extension is what selects the
backend (`.class` or `.jar` for JVM, `.wasm` for WASM).

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o Hello.class
java Hello
```

The generated class is named after the output file, so the name you pass to
`java` is the file's stem: `-o Hello.class` produces a class `Hello` you run with
`java Hello`. A directory in the path becomes the class's Java **package**:
`-o com/example/Kernels.class` produces `com.example.Kernels`, which you run
with `java -cp . com.example.Kernels` from the directory the path started in
(the missing directories are created). A directory that could not BE a package is
just a directory instead: an absolute path (whose leading `/` would open the name
with an empty package, which no JVM loads) and a `./` or `../` segment leave only
the file's stem, so `-o /tmp/out/Hello.class` is again the class `Hello` that
`java -cp /tmp/out Hello` runs. `--class-name` is how an absolute path names a
package anyway. The program's top-level forms
become the class's entry point and run in order when you launch it.

`-o out.jar` writes a jar rather than a bare class: the class, the runtime
classes that have to travel with it, a manifest, and -- with
`--maven-coordinates` -- the Maven metadata that lets a consumer install it with
no flags at all. The jar is **executable**, so it needs nothing else to run:

```bash
rontolisp hello.lisp -o hello.jar
java -jar hello.jar
```

A jar path names no class, so the class inside takes its name from the file's
stem in CamelCase (`hello.jar` -> `Hello`, `my-app-1.0.0.jar` -> `MyApp100`) and
the manifest's `Main-Class` points at it. That name only matters if you call the
class directly: `--class-name` sets it, and it is REQUIRED for a `--no-main`
library jar, whose class is the artifact's Java API rather than an entry point.
It works for `.class` output too, where it replaces the name the path would give.

A class can also be a **library** Java code calls directly:
[`rontolisp:jvm-export`](../reference/functions/rontolisp-jvm-export.md)
declares a typed, Java-callable static method for a `defun`, and `--no-main`
drops the `main` entry point entirely. See
[Export a JVM library](../guides/jvm-library.md).

Example (`hello.lisp`):

```lisp
(print (+ 1 2))
```

```
3
```

## Optimize (Dead-Code Elimination)

Compilation drops every method unreachable from `main`, along with any static field
only they referenced, and compacts the constant pool accordingly. You get that
without asking:

```bash
echo '(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(print (fact 10))' > fact.lisp
rontolisp fact.lisp -o Fact.class
java Fact
```

For a small program like `fact` the class is ~6.5 KB. Pass `--optimize=off` and the
class instead embeds the **entire** runtime (printer, numeric, reader and `eval`
helper methods, plus a first-class wrapper for every built-in) regardless of what the
program actually uses, which for the same `fact` is ~190 KB. The elimination is
behavior-preserving: reachability follows the actual `invoke` instructions
in the bytecode, so anything a first-class function value, `funcall`, or an embedded
`eval`/`load` can dispatch to is kept, and the `java:` interop bridge's reflective
entry point survives as an explicit root. Every
[`rontolisp:jvm-export`](../reference/functions/rontolisp-jvm-export.md) typed
method is an explicit root too — its caller is Java code the bytecode cannot
show — which is what lets a compiled
[library](../guides/jvm-library.md) keep the default size. The same levels also
tree-shake the [WASM output](wasm.md).

The dispatch methods `funcall` goes through list only the functions your program
can actually obtain as a value — `#'name`, a quoted `'name` designator, a
`lambda`, or (while the program holds a symbol builder such as `intern` or
`find-symbol`) a string or keyword constant spelling the name — so everything
else becomes ordinary dead code the shaker removes. That listing switches off,
and every function stays reachable, only when the program can name a function
out of data this compile never sees: any use of `eval`, `read`,
`read-from-string`, a runtime `load` or a `~/name/`
[`format`](../reference/macros/format.md) directive — including one inside a
library you loaded — as does `--dynamic`. Compile with
`-Drontolisp.debug.dispatchgate=true` to have the compiler name the operator
responsible.

One carve-out follows from that: a designator assembled at run time out of
**computed** pieces — `(funcall (intern (concatenate 'string "gre" suffix)))` —
is no constant the compiler can read, so the call signals the ordinary
"undefined function" error. `--dynamic` is the way back. `--optimize=off` is
not: the listing is not part of what the level switches, so declining the
optimizer does not bring such a name back.

`--optimize` takes an optional level, shared with the [WASM backend](wasm.md).
`--optimize` and `--optimize=default` both spell what an absent flag already
selects — everything above — for a build script that wants it written down.
`--optimize=off` declines it, and emits what a build before the flag was on by
default emitted. `--optimize=size` asks for the smallest output a backend can
give. On this backend it declines the two emissions that spend bytes on speed.
One is the typed numeric loop: a `dotimes` whose body reads and writes packed
single/double-float arrays through fixnum index math, `let` temporaries,
`+ - * /`, the unary math functions and `if`/`when`/`unless` tests compiles by
default to a primitive `long`/`double` loop over raw `float[]`/`double[]`
accesses, behind a check at the loop's entry that falls back to the ordinary
emission whenever the variables are not what the typing assumed -- the same
values either way, several times faster, and a larger class because the body is
emitted more than once. The other is integer expression-tree fusion: a nested
`+ - * mod rem logand logior logxor lognot ash` tree compiles by default into a
shared method that runs the whole tree as raw `long` arithmetic and boxes only
the result, with the generic per-operation chain kept alongside as the fallback
for anything that is not a machine-word integer at run time -- again the same
values, and a class that carries each tree twice. `--optimize=size` keeps only
the ordinary emissions; a program with neither shape compiles to the same class
at both levels, and the same program's JVM bytecode is about a third the size of
its WASM to begin with. So one build script can pass `--optimize=size` for every
target.

`--optimize=off` exists for two jobs, and neither of them is making a program
work: comparing an artifact against one built before a compiler change, and
bisecting a suspected shaker bug by asking whether the unshaken class behaves
differently. A program whose functions are reached only through a name the
compiler cannot read needs `--dynamic`, as above.

Independently of the level, compilation always tree-shakes the libraries it
splices in: the bundled Lisp-source ones (`linalg:`, `vec:`, JSON, URL,
`equalp`/`string<`) and every system loaded with
[`asdf:load-system` / `ql:quickload`](../guides/asdf-systems.md). A function,
variable or constant your program never mentions -- by name anywhere in the
source, including quoted symbols and string literals -- is not compiled in. Your
own code is never pruned, and neither is anything a `load`/`require` splices in:
only a library that came from a system is subject to it.

Classes, generic functions, methods, conditions and structures are pruned by
the same rule: a class nothing references leaves together with its methods,
and a method on a generic your program does call is still dropped when no
reachable code can create an instance of the class it specializes on. Methods
on the standard protocol names (`initialize-instance`, `print-object`,
`close`, ...) follow their class alone, since those calls are implicit.

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

## Skip the JIT Warm-Up with an AOT Cache

A compiled program starts as bytecode, so its first few dozen milliseconds run in
the JVM's interpreter and first-tier compiler while the JIT works out which
methods are hot. For a long-running program that cost disappears into the run.
For a short one it can be most of it: `bench-report`'s `mandelbrot` finishes in
about 95 ms on its first in-process run and about 22 ms on its third, and every
new process starts over at 95.

JDK 25 can persist what that first run learned. Compile to a **jar**, do one
training run, build a cache from it, and pass the cache to every run after:

```bash
rontolisp mandelbrot.lisp -o app.jar
java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -jar app.jar
java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -cp app.jar
java -XX:AOTCache=app.aot -jar app.jar
```

On a 64-core Linux box with GraalVM 25 that takes `mandelbrot`'s first run from
95 ms to 51 and `matmul`'s from 94 to 57 (medians). The gain is the JIT not
re-deriving a profile it has already been handed, so it shows up where warm-up
was a large share of a short run and nowhere else: of the ten `bench-report`
programs, only those two move more than 10%.

Four things to know before relying on it.

**It needs `-o app.jar`.** The cache cannot be built from a classpath that
contains a directory, so a bare `-o Prog.class` run under `java -cp . Prog`
cannot be trained. The jar is the same compiled class plus a manifest, so this
costs nothing else.

**The training run has to do the real work.** The cache stores a profile, not
compiled code, and a profile only exists once the training run has itself warmed
up. Training `mandelbrot` on a quarter-size grid -- 32 ms of work -- buys the
full run nothing. Train on a representative workload, not a smoke test.

**The cache belongs to one jar file.** It records the jar's path and timestamp,
so rebuilding the program invalidates it. Nothing breaks when that happens: the
JVM prints a few `[error][aot]` lines on stderr, ignores the cache and runs the
program correctly at the usual speed. Rebuild the cache whenever you rebuild the
jar, or drop the flag. A cache is around 11 MB.

**On GraalVM, use the two-step flow above rather than the one-command
`-XX:AOTCacheOutput`.** That shortcut assembles the cache in a child JVM which
loses GraalVM's `jdk.internal.vm.ci` module, and the cache it writes is rejected
at load -- with the error only on stderr, so the program still runs, just with no
gain at all.

The same works on `rontolisp` itself, where it is worth roughly 3x on start-up
(`java -jar rontolisp.jar` on a program that computes nothing: 476 ms to 144 ms;
a cache trained on a compile invocation halves `-o out.class` instead, 978 ms to
480 ms). If you have a GraalVM to hand, the [native binary](../getting-started/build.md)
is the better answer to the same problem -- it does those in 12 ms and 109 ms
with no cache file and no training step.
