# count-vowels -- sharing a string with a host through Wasm memory

The rontolisp counterpart of the classic "share a string through Wasm memory"
host tutorial. A `count_vowels.wasm` receives a string by pointer and returns the
vowel count; here the module is written in Lisp
([`count-vowels.lisp`](count-vowels.lisp)) and driven from a pure-Java
[Endive](https://endive.run) host
([`src/main/java/CountVowels.java`](src/main/java/CountVowels.java)). Endive is
the successor to Chicory; its
[memory guide](https://endive.run/docs/core/memory)
covers the linear-memory API used here.

WebAssembly only understands integers and floats, so a string crosses the
boundary as a `(pointer, length)` pair of raw UTF-8 bytes living in the module's
linear memory. `(rontolisp:wasm-export 'count-vowels :as "count_vowels" :params
'(:string) :returns :int)` sets that up: the compiled module exports its `memory`
plus a bump allocator `__ronto_alloc(size)`, so the host reserves space, writes
the bytes, then calls `count_vowels(ptr, len)`. This is exactly the
alloc / writeString / call flow of the tutorial. There is no general
`dealloc` (`__ronto_alloc` is a bump allocator), but `count_vowels` returns a
scalar `:int`, so the wrapper auto-frees its per-call internal string copy on
return: **repeated calls on one instance no longer leak that copy**. The host is
still responsible for its own `__ronto_alloc` input buffer -- allocate it once and
reuse it across calls, discard the instance, or bracket it with the arena API
below.

Compiled with `--no-gc` the result is a plain MVP module: no wasm-GC, no WASI
imports, so **any** WebAssembly engine runs it -- including Endive, which has no
wasm-GC support.

## Build the module

```bash
JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar   # ./mvnw clean package first
java -jar $JAR count-vowels.lisp -o count_vowels.wasm --no-gc --optimize
```

The module exports five things:

```
count_vowels       : (i32 ptr, i32 len) -> i32      the vowel count
__ronto_alloc      : (i32 size)         -> i32 ptr  bump allocator
__ronto_alloc_mark : ()                 -> i32 mark heap-top snapshot (arena API)
__ronto_alloc_reset: (i32 mark)         -> ()       restore the heap top (arena API)
memory                                              the linear memory to write into
```

The last two are the **host arena API**: `__ronto_alloc_mark` returns the current
bump-allocator top and `__ronto_alloc_reset` restores it, so a long-lived host can
bracket its own allocations and give them back after each call (see
[Keeping a resident instance flat](#keeping-a-resident-instance-flat)).

## Run with Endive (Java host)

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

To count a different word, pass it as a program argument (the `exec:java` goal
splits `exec.args` on whitespace, so use a single token):

```bash
mvn -q exec:java -Dexec.args=Programming
# "Programming" has 3 vowels
```

The host reads `count_vowels.wasm` from this directory. `--no-gc` emits no imports,
so Endive instantiates the module with no import object at all -- the same
"instantiate, then call the exports" shape as the Endive tutorial.

## Run with Node (any Wasm engine)

Because the module is a plain reactor, a three-line JavaScript host works too --
`__ronto_alloc`, write the bytes into `memory.buffer`, call `count_vowels`:

```bash
node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("count_vowels.wasm"), {})).instance.exports;
  const bytes = Buffer.from("Hello, World!");
  const ptr = ex.__ronto_alloc(bytes.length);
  new Uint8Array(ex.memory.buffer, ptr, bytes.length).set(bytes);
  console.log(ex.count_vowels(ptr, bytes.length));   // => 3
})()'
```

## Keeping a resident instance flat

`__ronto_alloc` is a bump allocator that never frees, so a host that keeps one
instance alive and calls it in a loop -- allocating a fresh input buffer each time
-- grows its linear memory without bound. Because `count_vowels` returns a scalar
`:int`, its wrapper already frees the per-call *internal* string copy on return,
but the host's own input buffer (allocated *before* the call) still leaks.

The **arena API** closes that gap: snapshot the heap top with `__ronto_alloc_mark`
*before* allocating the input, then pop back to it with `__ronto_alloc_reset`
*after* reading the result. The input buffer -- and any wrapper scratch above the
mark -- is reclaimed, so the instance stays perfectly flat:

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
    const n = ex.count_vowels(ptr, b.length);      // scalar read out here
    ex.__ronto_alloc_reset(mark);                  // pop input + wrapper scratch
    return n;
  };
  const before = ex.memory.buffer.byteLength;
  for (let i = 0; i < 100000; i++) countVowels("Hello, World! " + i);
  console.log(before, "->", ex.memory.buffer.byteLength);   // 65536 -> 65536 (flat)
})()'
```

Drop the two `__ronto_alloc_mark` / `__ronto_alloc_reset` lines and the same loop
grows past 2 MB. The [Endive host](src/main/java/CountVowels.java) does the
identical thing in Java: `mark = instance.export("__ronto_alloc_mark").apply()[0]`
before the input `__ronto_alloc`,
`instance.export("__ronto_alloc_reset").apply(mark)` after reading the result,
watching `instance.memory().pages()` stay constant (the `resident 100000 calls:
memory 1 -> 1 pages` line above).

Two caveats -- the arena is a manual stack, not a garbage collector:

- Only ever reset to a mark taken **before** everything still live. Popping to a
  mark taken *after* data you still need frees that data.
- For a `:string`-**returning** export the wrapper does *not* auto-reset (the
  result is a live heap pointer). **Read the returned bytes out of memory before
  calling `__ronto_alloc_reset`** -- resetting first frees the string, and the next
  allocation overwrites it.

## The Lisp is portable

`count-vowels` is an ordinary pure function, so the same source also runs on the
interpreter, the JVM backend and the wasm-GC backend (add a
`(print (count-vowels "Hello, World!"))` and run it directly) -- the
`rontolisp:wasm-export` directive is a no-op everywhere except the Preview 1 core
module. Only the `--no-gc` build is the one these Java / Node hosts consume.
