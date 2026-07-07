# count-vowels -- sharing a string with a host through Wasm memory

The rontolisp counterpart of Chicory's
[Using Memory to share data](https://chicory.dev/docs/usage/memory) tutorial. In
that tutorial a Rust `count_vowels.wasm` receives a string by pointer and returns
the vowel count; here the module is written in Lisp
([`count-vowels.lisp`](count-vowels.lisp)) and driven from a pure-Java
[Chicory](https://chicory.dev) host ([`CountVowels.java`](CountVowels.java)).

WebAssembly only understands integers and floats, so a string crosses the
boundary as a `(pointer, length)` pair of raw UTF-8 bytes living in the module's
linear memory. `(rontolisp:wasm-export 'count-vowels :as "count_vowels" :params
'(:string) :returns :int)` sets that up: the compiled module exports its `memory`
plus a bump allocator `__ronto_alloc(size)`, so the host reserves space, writes
the bytes, then calls `count_vowels(ptr, len)`. This is exactly the
alloc / writeString / call flow of the Chicory tutorial. (There is no `dealloc`:
`__ronto_alloc` is a bump allocator that never frees -- the instance is discarded
instead.)

Compiled with `--no-gc` the result is a plain MVP module: no wasm-GC, no WASI
imports, so **any** WebAssembly engine runs it -- including Chicory, which has no
wasm-GC support.

## Build

```bash
JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar   # ./mvnw clean package first
java -jar $JAR count-vowels.lisp -o count_vowels.wasm --no-gc --optimize
```

The module exports exactly three things:

```
count_vowels : (i32 ptr, i32 len) -> i32     the vowel count
__ronto_alloc: (i32 size)         -> i32 ptr  bump allocator
memory                                        the linear memory to write into
```

## Run with Chicory (Java host)

[`CountVowels.java`](CountVowels.java) is a [jbang](https://jbang.dev) script --
its `//DEPS` header pulls in the Chicory runtime, so no build file is needed:

```bash
jbang CountVowels.java "Hello, World!"
# "Hello, World!" has 3 vowels
```

Without jbang, compile it against the Chicory `runtime` jar on the classpath and
run `CountVowels` from this directory (it reads `count_vowels.wasm` from the
current directory).

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

## The Lisp is portable

`count-vowels` is an ordinary pure function, so the same source also runs on the
interpreter, the JVM backend and the wasm-GC backend (add a
`(print (count-vowels "Hello, World!"))` and run it directly) -- the
`rontolisp:wasm-export` directive is a no-op everywhere except the Preview 1 core
module. Only the `--no-gc` build is Chicory-specific.
