# count-vowels -- sharing a string with a host through Wasm memory

The rontolisp counterpart of the classic "share a string through Wasm memory"
host tutorial. A `count_vowels.wasm` receives a string by pointer and returns the
vowel count; here the module is written in Lisp
([`count-vowels.lisp`](count-vowels.lisp)) and driven from Node and from a
pure-Java [Endive](https://endive.run) host
([`src/main/java/CountVowels.java`](src/main/java/CountVowels.java)). Endive is
the successor to Chicory; its
[memory guide](https://endive.run/docs/core/memory)
covers the linear-memory API used here.

WebAssembly only understands integers and floats, so a string crosses the
boundary as a `(pointer, length)` pair of raw UTF-8 bytes living in the module's
linear memory. `(rontolisp:wasm-export 'count-vowels :params '(:string) :returns
:int)` sets that up: the compiled module exports its `memory` plus a bump
allocator `__ronto_alloc(size)`, so the host reserves space, writes the bytes,
then calls `count-vowels(ptr, len)`. This is exactly the alloc / writeString /
call flow of the tutorial.

`__ronto_alloc` is a bump allocator and there is no `dealloc`, so the interesting
question is **who reclaims that memory** in a host that keeps one instance alive
and calls it in a loop. The same Lisp source answers it two ways, depending on how
it is compiled -- both are driven from Node below:

| Build | Who frees | Host work |
|---|---|---|
| [`--no-gc`](#1---no-gc-the-host-pops-the-heap) | the host, with the arena API `__ronto_alloc_mark`/`_reset` | alloc, write, call, reset |
| [`--no-gc --component`](#2-component-model-the-canonical-abi-does-everything) | the canonical ABI + `post-return`, on every call | none -- pass a JS string |

The export keeps its Lisp name `count-vowels` (a component-model export name must
be lower-kebab-case, so it is not renamed to `count_vowels` with `:as`), which
lets one directive serve both builds.

`count-vowels` is a pure loop over the characters of a string, so it fits the
[non-GC subset](../../doc/en/compiling/wasm.md#eligible-subset) and both builds
here are `--no-gc`: the module is 667 bytes and runs on **any** engine. A function
that needs the full language (cons, hash tables, `string-upcase`, ...) compiles to
a wasm-GC core module instead (`--no-wasi`); the memory boundary is exactly the
one in case 1 -- the same `__ronto_alloc` + `__ronto_alloc_mark`/`_reset` bracket,
since the engine's GC collects the Lisp values but never the host's input buffer.

## Build the two modules

```bash
JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar   # ./mvnw clean package first

java -jar $JAR count-vowels.lisp -o count_vowels.wasm           --no-gc --optimize
java -jar $JAR count-vowels.lisp -o count_vowels_component.wasm --no-gc --component --optimize
```

## 1. `--no-gc`: the host pops the heap

`--no-gc` emits a plain MVP module: no wasm-GC, no WASI imports, so **any**
WebAssembly engine instantiates it with an empty import object. It exports:

```
count-vowels       : (i32 ptr, i32 len) -> i32      the vowel count
__ronto_alloc      : (i32 size)         -> i32 ptr  bump allocator
__ronto_alloc_mark : ()                 -> i32 mark heap-top snapshot (arena API)
__ronto_alloc_reset: (i32 mark)         -> ()       restore the heap top (arena API)
memory                                              the linear memory to write into
```

The last two are the **host arena API**. Snapshot the heap top with
`__ronto_alloc_mark` *before* allocating the input, then pop back to it with
`__ronto_alloc_reset` *after* reading the result: the input buffer -- and
everything the call allocated internally -- is reclaimed, so a resident instance
stays perfectly flat.

```bash
node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("count_vowels.wasm"), {})).instance.exports;
  const enc = new TextEncoder();
  const countVowels = (s) => {
    const b = enc.encode(s);
    const mark = ex.__ronto_alloc_mark();          // snapshot BEFORE allocating
    const ptr = ex.__ronto_alloc(b.length);
    new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
    const n = ex["count-vowels"](ptr, b.length);   // scalar result, read out here
    ex.__ronto_alloc_reset(mark);                  // pop input + call scratch
    return n;
  };
  console.log("\"Hello, World!\" has", countVowels("Hello, World!"), "vowels");
  const before = ex.memory.buffer.byteLength;
  for (let i = 0; i < 100000; i++) countVowels("Hello, World! " + i);
  console.log("resident 100000 calls: memory", before, "->", ex.memory.buffer.byteLength);
})()'
# "Hello, World!" has 3 vowels
# resident 100000 calls: memory 65536 -> 65536
```

Drop the two `__ronto_alloc_mark` / `__ronto_alloc_reset` lines and the same loop
grows past 2 MB.

Two caveats -- the arena is a manual stack, not a garbage collector:

- Only ever reset to a mark taken **before** everything still live. Popping to a
  mark taken *after* data you still need frees that data.
- For a `:string`-**returning** export, **read the returned bytes out of memory
  before calling `__ronto_alloc_reset`** -- resetting first frees the string, and
  the next allocation overwrites it.

### The same thing from Java (Endive)

This directory is a self-contained Maven project ([`pom.xml`](pom.xml)) that
depends on `run.endive:runtime`. First build the rontolisp compiler jar once (from
the repository root), then a single Maven command compiles the module, compiles
the host and runs it:

```bash
# from the repository root -- builds ../../target/rontolisp-*-exec.jar
./mvnw -q clean package -DskipTests

# in this directory -- `compile` also builds count_vowels.wasm (see pom.xml)
mvn -q compile exec:java
# "Hello, World!" has 3 vowels
# resident 100000 calls: memory 1 -> 1 pages
```

[`CountVowels.java`](src/main/java/CountVowels.java) is the Node host line for
line: `mark = instance.export("__ronto_alloc_mark").apply()[0]` before the input
`__ronto_alloc`, `instance.export("__ronto_alloc_reset").apply(mark)` after
reading the result, and `instance.memory().pages()` stays constant across the
100000-call loop.

To count a different word, pass it as a program argument (the `exec:java` goal
splits `exec.args` on whitespace, so use a single token):

```bash
mvn -q exec:java -Dexec.args=Programming
# "Programming" has 3 vowels
```

## 2. Component model: the canonical ABI does everything

Compiled with `--no-gc --component` the output is a WebAssembly component whose
export is typed `func(s: string) -> s32`. The canonical string ABI lowers the
host's string into the module's memory (through a `cabi_realloc` the compiler
emits for you) and the generated `post-return` resets the bump heap after every
call -- so the host does no allocation, no writing into `memory`, no arena
bookkeeping. It just calls a function with a string:

```bash
npx -y @bytecodealliance/jco transpile count_vowels_component.wasm -o dist

node --input-type=module -e '
import { countVowels } from "./dist/count_vowels_component.js";
console.log("\"Hello, World!\" has", countVowels("Hello, World!"), "vowels");
for (let i = 0; i < 100000; i++) countVowels("Hello, World! " + i);
console.log("resident 100000 calls: no allocator code in the host at all");
'
# "Hello, World!" has 3 vowels
# resident 100000 calls: no allocator code in the host at all
```

The kebab-case export `count-vowels` surfaces in JS as `countVowels`. The
component is ~850 bytes: `--no-gc` needs no adapter, so it is just the core module
plus the type/lift wiring.

`wasmtime` runs it with no flags at all:

```bash
wasmtime run --invoke 'count-vowels("Hello, World!")' count_vowels_component.wasm
# 3
```

Endive cannot run this one yet -- its roadmap has WASIp2 / the component model as
ongoing work -- so the Java host above stays on the `--no-gc` core module.

## The Lisp is portable

`count-vowels` is an ordinary pure function, so the same source also runs on the
interpreter, the JVM backend and the wasm-GC backend as a normal program (add a
`(print (count-vowels "Hello, World!"))` and run it directly) -- the
`rontolisp:wasm-export` directive is a no-op everywhere except a WASM build.
