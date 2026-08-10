# wit/world -- someone handed me a `.wit`, now what

A [WIT](https://component-model.bytecodealliance.org/design/wit.html) world is a
language-independent contract: the functions a component must export, their
types, and which of them are async. The realistic starting point is therefore
not a Lisp file but a `.wit` someone hands you. This directory is that workflow,
end to end:

| Step | Command | File |
|---|---|---|
| the contract you were handed | -- | [`wit/analyzer.wit`](wit/analyzer.wit) |
| generate a skeleton from it | `rontolisp --scaffold-wit wit/analyzer.wit -o analyzer.lisp` | [`analyzer.lisp`](analyzer.lisp) |
| fill in the bodies | your editor | the same file |
| build and call it | `rontolisp analyzer.lisp --component -o analyzer.wasm` | `analyzer.wasm` |
| check the two never drift | the compiler, on every build | -- |
| print the component's own world back out | `--emit-wit` | `analyzer.wit` |

Commands say `rontolisp`, the native binary; with the executable JAR it is
`java -jar ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar`.

## 1. The world you were handed

```wit
package example:analyzer;

world analyzer {
  /// Count the words in the text. ...
  export word-count: func(text: string) -> s32;
  export longest-word: func(text: string) -> string;
  export is-palindrome: func(text: string) -> bool;
  /// Declared async because it performs I/O: a synchronous export may not block.
  export print-report: async func(text: string);
}
```

Four exports over three boundary types plus one resultless `async func`. Nothing
here is rontolisp-specific, and rontolisp implements the world under its real
name.

`print-report` is the interesting one. A component's exports are lifted
**synchronously** by default and a synchronous task may not block, so `print`,
`read` or `rontolisp:fetch` inside one traps at run time with *"cannot block a
synchronous task"*. Being async is a property of the **contract**, not of the
implementation -- which is why the world states it and an implementer never has
to guess. See
[Component-model function exports](../../../doc/en/guides/wasm-component.md#component-model-function-exports-wasm-export).

## 2. Scaffold the implementation

```bash
rontolisp --scaffold-wit wit/analyzer.wit -o analyzer.lisp
```

writes a runnable program implementing the world -- one stub per export:

```lisp
;;; Count the words in the text. A word is a run of characters separated by
;;; spaces; leading, trailing and repeated spaces do not count.
;;; WIT: word-count: func(text: string) -> s32
(defun word-count (text)
  (error "word-count is not implemented yet"))

;;; ... one per export ...

(rontolisp:wit-export "wit/analyzer.wit" :world analyzer)
```

Read what survived the trip. Every parameter is named the way the **WIT** names
it (`text`, not `p0`) -- those labels are part of the component's type, so the
host sees them. Every `///` doc comment came across as a `;;;` comment, and under
it a `;;; WIT:` line restates the signature the body must satisfy, types and
`async` included, because the Lisp itself would otherwise say nothing about the
contract. The `wit-export` directive comes **last**: on the interpreter it is an
ordinary form evaluated in order, so it can only check what is defined above it.

What is *not* there is a `rontolisp:wasm-export` with `:params`/`:returns`. The
types stay in the WIT; the directive says only *I implement that world*. There
is no second place for the signature to be written, so no second place for it to
drift.

Two things worth knowing before you touch the file:

- **It already compiles.** The stubs signal at *run* time, so the world can be
  filled in one export at a time and every intermediate state is a real
  component.
- Drop `-o` to print to stdout, and pass `--world NAME` when the `.wit` declares
  more than one.

Extra `defun`s are free: the world constrains only the functions it names.

## 3. Fill in the bodies

Replace each stub, leave the comments alone:

```lisp
;;; WIT: word-count: func(text: string) -> s32
(defun word-count (text)
  (length (split-words text)))
```

[`analyzer.lisp`](analyzer.lisp) is exactly the scaffold with its four bodies
written and two helpers added; the header, comments, `defun` headers and
directive are all still the generator's.

## 4. Build the component and call it

The component's exports are typed, so `wasmtime` calls them by name with
[WAVE](https://component-model.bytecodealliance.org/) values -- no linear-memory
pointer arithmetic, no `__ronto_alloc`:

```console
$ rontolisp analyzer.lisp --component -o analyzer.wasm
$ W="wasmtime run -W gc=y"

$ $W --invoke 'word-count("the quick brown fox jumps over the lazy dog")' analyzer.wasm
9
$ $W --invoke 'longest-word("the quick brown fox jumps over the lazy dog")' analyzer.wasm
"quick"
$ $W --invoke 'is-palindrome("A man, a plan, a canal: Panama")' analyzer.wasm
true
$ $W --invoke 'print-report("A man, a plan, a canal: Panama")' analyzer.wasm
text:         A man, a plan, a canal: Panama
words:        7
longest word: canal:
palindrome:   yes
()
```

Each result is printed in the WIT type the world declared, and `print-report`'s
absent result as `()` after the four lines it printed. Those lines are the async
lift working: the same `format` inside a *synchronous* export would have
trapped.

(`-W gc=y` because the full language compiles to a wasm-GC component.
[`count-vowels/`](../../count-vowels) stays inside the
[non-GC subset](../../../doc/en/guides/wasm-nogc.md#eligible-subset) and needs no
engine flags at all.)

## 5. Drift is a compile error, and it names the WIT line

The contract is checked on **every** build, not just when an export is called:

```console
$ rontolisp analyzer.lisp --component -o analyzer.wasm
wit/analyzer.wit:16: export 'is-palindrome' has no matching (defun is-palindrome ...) in the program

wit/analyzer.wit:12: export 'longest-word' declares 1 parameter(s), but (defun longest-word ...) takes 2
```

Name, arity, parameter types, result type, async-ness: all of it, all named
against the WIT line. The check runs on **every** backend, including the ones
that export nothing, so a plain `rontolisp analyzer.lisp` catches a drifted
world in a second without producing a component — which makes it a usable CI
check.

## 6. `--emit-wit`: the component's own world, printed back out

```bash
rontolisp analyzer.lisp --component -o analyzer.wasm --emit-wit   # also writes analyzer.wit
```

The four exports come back **exactly** as handed in -- names, the parameter name
`text`, types, and `print-report` still an `async func`. That is the round trip
worth having. The rest of the file is not the input, and differs in exactly two
ways:

1. **It is normalized to `package root:component; world root`.** A component's
   *type* has no package or world name of its own — that is what every tool
   prints. `example:analyzer` names the *contract*, not the artifact, and lives
   in `wit/analyzer.wit` only.
2. **It lists the WASI imports the build actually links**, which the
   hand-written world never stated: `wasi:cli/stdout` (what `print-report`'s
   `format` compiles to), `wasi:filesystem`, `wasi:clocks`, `wasi:random`, plus
   the `wasi:cli/run` export every rontolisp component carries. The author wrote
   a contract about *what the component does*; the emitted world additionally
   describes *what it needs from its host*, which is a fact about the build.

One thing cannot survive at all: the `///` doc comments. A component's type does
not store them — which is also why `--scaffold-wit` reads the `.wit` *text*
rather than introspecting a `.wasm`.

So `--emit-wit` is a consistency check of the export **surface** and a way to
hand a host the imports it must satisfy. It is not a copy of the input, and the
input remains the source of truth.

## What the boundary carries today

| WIT type | Lisp value |
|---|---|
| `s32` | an integer |
| `s64` | an integer (a `u64` value of 2^63 or more traps at the boundary) |
| `f64` | a float |
| `bool` | `t` / `nil` |
| `string` | a string |
| no result | the function's value is discarded |

Every other WIT type (`record`, `list`, `option`, `result`, resources, ...) is a
clear compile error at the export boundary today, naming the representation it
is settled to have once marshalling lands. A world's `import` items are ignored
— a component's WASI imports come from the adapter surface it is built on. Full
reference:
[`rontolisp:wit-export`](../../../doc/en/reference/functions/rontolisp-wit-export.md),
[Implementing a WIT World](../../../doc/en/guides/wit-contracts.md#implementing-a-wit-world-wit-export).
