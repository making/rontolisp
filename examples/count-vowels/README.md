# count-vowels -- sharing a string with a host through Wasm memory

The rontolisp counterpart of the classic "share a string through Wasm memory"
host tutorial: a module receives a string by pointer and returns the vowel
count. Here it is written in Lisp ([`count-vowels.lisp`](count-vowels.lisp)) and
driven from Node and from a pure-Java [Endive](https://endive.run) host
([`CountVowels.java`](src/main/java/CountVowels.java)).

WebAssembly understands only integers and floats, so a string crosses as a
`(pointer, length)` pair of raw UTF-8 bytes in the module's linear memory. That
boundary's type is not written in the Lisp but in WIT
([`count_vowels_component.wit`](count_vowels_component.wit)):

```wit
package root:component;

world root {
  export count-vowels: func(s: string) -> s32;
}
```

and the Lisp says only *I implement that world*:

```lisp
(rontolisp:wit-export "count_vowels_component.wit")
```

The compiler reads the world, checks every export against the program's
`defun`s — name, arity, parameter and result types — and lowers each into the
export it stands for. Nothing states the signature twice, so nothing can drift:

```console
count_vowels_component.wit:4: export 'count-vowels' declares 1 parameter(s), but (defun count-vowels ...) takes 2
```

`__ronto_alloc` is a bump allocator with no `dealloc`, so the interesting
question is **who reclaims that memory** in a host that keeps one instance alive
and calls it in a loop. The same source answers it two ways:

| Build | Who frees | Host work |
|---|---|---|
| [`--no-gc`](#1---no-gc-the-host-pops-the-heap) | the host, with `__ronto_alloc_mark`/`_reset` | alloc, write, call, reset |
| [`--no-gc --component`](#2-component-model-the-canonical-abi-does-everything) | the canonical ABI + `post-return`, every call | none — pass a JS string |

`count-vowels` is a pure loop over characters, so it fits the
[non-GC subset](../../doc/en/guides/wasm-nogc.md#eligible-subset) and both builds
are `--no-gc`: a sub-kilobyte module that runs on **any** engine. A function
needing the full language compiles to a wasm-GC core module instead
(`--no-wasi`); the memory boundary is exactly case 1's, since the engine's GC
collects the Lisp values but never the host's input buffer.

## Build the two modules

```bash
JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar   # ./mvnw clean package first

java -jar $JAR count-vowels.lisp -o count_vowels.wasm           --no-gc --optimize
java -jar $JAR count-vowels.lisp -o count_vowels_component.wasm --no-gc --component --optimize --emit-wit
```

## 1. `--no-gc`: the host pops the heap

A plain MVP module — no wasm-GC, no WASI imports, so any engine instantiates it
with an empty import object. It exports:

```
count-vowels       : (i32 ptr, i32 len) -> i32      the vowel count
__ronto_alloc      : (i32 size)         -> i32 ptr  bump allocator
__ronto_alloc_mark : ()                 -> i32 mark heap-top snapshot (arena API)
__ronto_alloc_reset: (i32 mark)         -> ()       restore the heap top (arena API)
memory                                              the linear memory to write into
```

Snapshot the heap top *before* allocating the input, pop back to it *after*
reading the result: the input buffer and everything the call allocated
internally are reclaimed, so a resident instance stays flat.

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

Drop the two arena lines and the same loop grows linear memory without bound. Two caveats — the
arena is a manual stack, not a collector:

- Only reset to a mark taken **before** everything still live.
- For a `:string`-**returning** export, read the returned bytes out **before**
  resetting; resetting first frees the string.

### The same thing from Java (Endive)

This directory is a self-contained Maven project depending on
`run.endive:runtime`. Build the compiler jar once from the repository root, then
one Maven command compiles the module, the host, and runs it:

```bash
./mvnw -q clean package -DskipTests    # from the repository root
mvn -q compile exec:java               # here; `compile` also builds count_vowels.wasm
# "Hello, World!" has 3 vowels
# resident 100000 calls: memory 1 -> 1 pages
```

[`CountVowels.java`](src/main/java/CountVowels.java) is the Node host line for
line, and `instance.memory().pages()` stays constant across the loop. Pass a
different word with `-Dexec.args=Programming` (a single token — `exec:java`
splits on whitespace).

## 2. Component model: the canonical ABI does everything

With `--no-gc --component` the output is a component whose export is typed
`func(s: string) -> s32`. The canonical string ABI lowers the host's string into
the module's memory through a `cabi_realloc` the compiler emits, and the
generated `post-return` resets the bump heap after every call — so the host does
no allocation, no writing into `memory`, no arena bookkeeping:

```bash
npx -y @bytecodealliance/jco transpile count_vowels_component.wasm -o dist

node --input-type=module -e '
import { countVowels } from "./dist/count_vowels_component.js";
console.log("\"Hello, World!\" has", countVowels("Hello, World!"), "vowels");
'
# "Hello, World!" has 3 vowels
```

The kebab-case export surfaces in JS as `countVowels`. `wasmtime` runs it with
no flags at all:

```bash
wasmtime run --invoke 'count-vowels("Hello, World!")' count_vowels_component.wasm
# 3
```

`--emit-wit` prints the component's own type back out, and here the file comes
back byte-for-byte unchanged, parameter name `s` included:

```bash
git diff --exit-code count_vowels_component.wit && echo "the component IS the world"
```

Be precise about what that proves. The export line *cannot* come out disagreeing
with the world: the world produced the export directive, which produced the
component's function type, which is what gets printed back. The diff is a
regression check on rontolisp's type mapping, not on this program — what catches
a drifted program is `wit-export`, and it already fired at compile time.

What makes the round trip *byte*-exact is `--no-gc`: an adapter-free reactor
imports nothing, so the component's whole type is the one export. Drop it and
the same source builds a wasm-GC component whose real type runs to ~150 lines —
ten `wasi:*` imports and `export wasi:cli/run` wrapped around the same
`count-vowels`. Those imports are the half a hand-written world never states,
and `--emit-wit` is the only thing that reports them.

`jco transpile` read the types straight out of the `.wasm`, but the `.wit` is
that same contract **without the binary**: hand it to anyone generating bindings
from WIT — a wit-bindgen host embedding, or `jco types` for just the TypeScript
signatures — with no introspection step.

Endive cannot run this one yet (WASIp2 / the component model is ongoing work
there), so the Java host stays on the `--no-gc` core module.

## The Lisp is portable

`count-vowels` is an ordinary pure function, so the same source runs on the
interpreter, the JVM and the wasm-GC backend as a normal program. The
`wit-export` directive exports nothing outside a WASM build, but it is never
inert: every backend still checks the program against the world, so a plain
`java -jar $JAR count-vowels.lisp` catches a drifted `.wit` without compiling
anything.
