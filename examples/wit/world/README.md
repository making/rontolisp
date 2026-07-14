# wit/world -- someone handed me a `.wit`, now what

A [WIT](https://component-model.bytecodealliance.org/design/wit.html) world is a
language-independent contract: it names the functions a component must export,
their parameter and result types, and which of them are async. It says nothing
about the language you implement it in. So the realistic starting point is not a
Lisp file -- it is a `.wit` file someone hands you, and the question of what to do
with it.

This directory is that workflow, end to end:

| Step | Command | File |
|---|---|---|
| the contract you were handed | -- | [`wit/analyzer.wit`](wit/analyzer.wit) |
| generate a skeleton from it | `rontolisp --scaffold-wit wit/analyzer.wit -o analyzer.lisp` | [`analyzer.lisp`](analyzer.lisp) |
| fill in the bodies | your editor | the same file |
| build and call it | `rontolisp analyzer.lisp --component -o analyzer.wasm` | `analyzer.wasm` |
| check the two never drift | the compiler, on every build | -- |
| print the component's own world back out | `--emit-wit` | `analyzer.wit` |

The commands below say `rontolisp`, the native binary
(`./mvnw -Pnative clean package -DskipTests`). With the executable JAR
(`./mvnw clean package`) instead, `rontolisp` is
`java -jar ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar`.

## 1. The world you were handed

```wit
package example:analyzer;

/// A small text-analysis component. This file is the contract: it names the
/// functions a component must export, their parameter and result types, and
/// which of them are async. Any language with a WIT toolchain can implement it.
world analyzer {
  /// Count the words in the text. A word is a run of characters separated by
  /// spaces; leading, trailing and repeated spaces do not count.
  export word-count: func(text: string) -> s32;

  /// Return the longest word in the text, or the empty string when it has none.
  export longest-word: func(text: string) -> string;

  /// Report whether the text reads the same backwards, ignoring letter case and
  /// every character that is not a letter or a digit.
  export is-palindrome: func(text: string) -> bool;

  /// Print a human-readable report about the text to standard output. It is
  /// declared async because it performs I/O: a synchronous export may not block,
  /// so this is the one property the world has to state rather than let an
  /// implementer guess.
  export print-report: async func(text: string);
}
```

Four exports over three boundary types (`s32`, `string`, `bool`) plus one
resultless `async func`. Nothing here is rontolisp-specific: the package is
`example:analyzer` and the world is `analyzer`, not the `root:component` / `root`
a component's own type is normalized to, and rontolisp implements it under its
real name. The `///` comments are the documentation the author wrote, and they
are worth carrying into the implementation rather than re-reading the `.wit`
every time.

`print-report` is the interesting one. A component's exports are lifted
**synchronously** by default and a synchronous task may not block, so `print`,
`read` or `rontolisp:fetch` inside one traps at run time with *"cannot block a
synchronous task"*. Being async is a property of the **contract**, not of the
implementation -- which is precisely why the world states it, and why an
implementer never has to guess. See
[Component-model function exports](../../doc/en/compiling/wasm.md#component-model-function-exports-wasm-export).

## 2. Scaffold the implementation

`--scaffold-wit` reads a world and writes a runnable program implementing it:

```bash
rontolisp --scaffold-wit wit/analyzer.wit -o analyzer.lisp
```

Verbatim, that is:

```lisp
;;;; Implementation of the WIT world 'analyzer' (wit/analyzer.wit).
;;;;
;;;; The world is the contract: the compiler checks every defun below against
;;;; it, so a renamed export, a changed arity or a changed type is a compile
;;;; error rather than a runtime surprise. Fill in the bodies; each one signals
;;;; until you do.

;;; Count the words in the text. A word is a run of characters separated by
;;; spaces; leading, trailing and repeated spaces do not count.
;;; WIT: word-count: func(text: string) -> s32
(defun word-count (text)
  (error "word-count is not implemented yet"))

;;; Return the longest word in the text, or the empty string when it has none.
;;; WIT: longest-word: func(text: string) -> string
(defun longest-word (text)
  (error "longest-word is not implemented yet"))

;;; Report whether the text reads the same backwards, ignoring letter case and
;;; every character that is not a letter or a digit.
;;; WIT: is-palindrome: func(text: string) -> bool
(defun is-palindrome (text)
  (error "is-palindrome is not implemented yet"))

;;; Print a human-readable report about the text to standard output. It is
;;; declared async because it performs I/O: a synchronous export may not block,
;;; so this is the one property the world has to state rather than let an
;;; implementer guess.
;;; WIT: print-report: async func(text: string)
(defun print-report (text)
  (error "print-report is not implemented yet"))

(rontolisp:wit-export "wit/analyzer.wit" :world analyzer)
```

Read what survived the trip. Every parameter is named the way the **WIT** names
it (`text`, not `p0`) -- those labels are part of the component's type, so the
host sees them too. Every `///` doc comment came across as a `;;;` comment, and
under it a `;;; WIT:` line restates the signature the body must satisfy, types
and `async` included, because the Lisp itself is untyped and would otherwise say
nothing about the contract. And the `rontolisp:wit-export` directive comes
**last**: on the interpreter it is an ordinary form evaluated in order, so it can
only check the functions defined above it.

What is *not* there is a `rontolisp:wasm-export` with `:params`/`:returns`. The
types stay in the WIT; the directive says only *I implement that world*, and the
compiler lowers each of the world's exports into the export directive it stands
for. That is the whole point -- there is no second place for the signature to be
written, so there is no second place for it to drift.

Two things worth knowing before you touch the file:

- **It already compiles.** The stubs signal at *run* time, not at compile time,
  so the world can be filled in one export at a time and every intermediate state
  is a real component. `rontolisp analyzer.lisp --component -o analyzer.wasm` on
  the file above produces a component whose `word-count("hello")` traps with
  `word-count is not implemented yet`.
- Drop `-o` to print the skeleton to stdout instead, and pass `--world NAME` when
  the `.wit` declares more than one world.

Extra `defun`s are free: the world constrains only the functions it names, so the
exports can call any helper in the file (`analyzer.lisp` adds `split-words` and
`letters-and-digits`).

## 3. Fill in the bodies

Replace each stub, leave the comments alone. `word-count` becomes:

```lisp
;;; Count the words in the text. A word is a run of characters separated by
;;; spaces; leading, trailing and repeated spaces do not count.
;;; WIT: word-count: func(text: string) -> s32
(defun word-count (text)
  (length (split-words text)))
```

and the file's shape never changes. [`analyzer.lisp`](analyzer.lisp) is exactly
the scaffold above with its four bodies written and two helpers added -- the
header, the doc comments, the `;;; WIT:` lines, the `defun` headers and the
directive are all still the generator's.

## 4. Build the component and call it

```bash
rontolisp analyzer.lisp --component -o analyzer.wasm
```

The component's exports are typed, so `wasmtime` calls them by name with
[WAVE](https://component-model.bytecodealliance.org/) values -- no linear-memory
pointer arithmetic, no `__ronto_alloc`: the canonical ABI passes the string.

```bash
W="wasmtime run -W gc=y -W component-model-more-async-builtins=y"

$W --invoke 'word-count("the quick brown fox jumps over the lazy dog")' analyzer.wasm
# 9

$W --invoke 'longest-word("the quick brown fox jumps over the lazy dog")' analyzer.wasm
# "quick"

$W --invoke 'is-palindrome("A man, a plan, a canal: Panama")' analyzer.wasm
# true

$W --invoke 'is-palindrome("hello world")' analyzer.wasm
# false

$W --invoke 'print-report("A man, a plan, a canal: Panama")' analyzer.wasm
# text:         A man, a plan, a canal: Panama
# words:        7
# longest word: canal:
# palindrome:   yes
# ()
```

Each result is printed in the WIT type the world declared: `s32` as `9`, `string`
quoted, `bool` as `true`/`false`, and `print-report`'s absent result as `()`
after the four lines it printed. Those four lines are also the async lift
working: the same `format` inside a *synchronous* export would have trapped.

(`-W gc=y` because the full language compiles to a wasm-GC component --
`analyzer.lisp` conses lists and builds strings. The
[`count-vowels/`](../count-vowels) example stays inside the
[non-GC subset](../../doc/en/compiling/wasm.md#eligible-subset) and its component
needs no engine flags at all.)

## 5. Drift is a compile error, and it names the WIT line

The contract is checked on **every** build, not just when the export is finally
called. Rename `is-palindrome` -- keeping the world as it is -- and the build
stops with the file and line that asked for it:

```console
$ rontolisp analyzer.lisp --component -o analyzer.wasm
wit/analyzer.wit:16: export 'is-palindrome' has no matching (defun is-palindrome ...) in the program
```

Give `longest-word` a second parameter, and:

```console
$ rontolisp analyzer.lisp --component -o analyzer.wasm
wit/analyzer.wit:12: export 'longest-word' declares 1 parameter(s), but (defun longest-word ...) takes 2
```

Name, arity, parameter types, result type, async-ness: all of it, all named
against the WIT line. And the check is not a WASM-only affair -- it runs on
**every** backend, including the ones that export nothing at all, so a plain

```bash
rontolisp analyzer.lisp        # the interpreter: runs the file, exports nothing
rontolisp analyzer.lisp -o Analyzer.class
```

catches a drifted world in a second without producing a component. That makes
`rontolisp analyzer.lisp` a usable "does my implementation still match the
contract" check to put in CI.

## 6. `--emit-wit`: the component's own world, printed back out

Add `--emit-wit` and the build writes the world of the component it just produced
next to the `.wasm`:

```bash
rontolisp analyzer.lisp --component -o analyzer.wasm --emit-wit   # also writes analyzer.wit
```

```console
$ head -20 analyzer.wit
package root:component;

world root {
  import wasi:cli/types@0.3.0;
  import wasi:cli/stdout@0.3.0;
  import wasi:cli/stdin@0.3.0;
  import wasi:cli/environment@0.3.0;
  import wasi:clocks/system-clock@0.3.0;
  import wasi:clocks/monotonic-clock@0.3.0;
  import wasi:filesystem/types@0.3.0;
  import wasi:filesystem/preopens@0.3.0;
  import wasi:random/random@0.3.0;
  import wasi:cli/stderr@0.3.0;

  export wasi:cli/run@0.3.0;
  export word-count: func(text: string) -> s32;
  export longest-word: func(text: string) -> string;
  export is-palindrome: func(text: string) -> bool;
  export print-report: async func(text: string);
}
```

The four exports come back **exactly** as they were handed in -- names, parameter
name `text`, types, and `print-report` still an `async func`. That is the round
trip worth having: what the compiler was handed and what an engine sees are the
same four signatures.

The rest of the file is not the input, and should not be expected to be. It
differs in exactly two ways:

1. **It is normalized to `package root:component; world root`.** A component's
   *type* has no package or world name of its own -- `root:component`/`root` is
   what every tool (including `wasm-tools component wit`) prints for it. So
   `example:analyzer` / `analyzer` is a name for the *contract*, not for the
   artifact, and it lives in `wit/analyzer.wit` only.
2. **It lists the WASI imports the build actually links**, which the hand-written
   world never stated: `wasi:cli/stdout` (that is what `print-report`'s `format`
   compiles to), `wasi:filesystem`, `wasi:clocks`, `wasi:random`, plus the
   `wasi:cli/run` export every rontolisp component carries as its entry point.
   Below line 20 come the `package wasi:cli@0.3.0 { ... }` blocks those imports
   refer to. The author of `wit/analyzer.wit` wrote a contract about *what the
   component does*; the emitted world additionally describes *what the component
   needs from its host*, which is a fact about the build, not about the contract.

One thing cannot survive the trip at all: the `///` doc comments. A component's
type does not store them -- which is also why `--scaffold-wit` reads the `.wit`
*text* rather than introspecting a `.wasm`.

So `--emit-wit` on a program that implements a world is a consistency check of
the export **surface**, and a way to hand a host the imports it must satisfy. It
is not a copy of the input file, and the input file remains the source of truth.

## What the boundary carries today

| WIT type | Lisp value |
|---|---|
| `s32` | an integer |
| `s64` | an integer -- `--no-gc` only (wasm-GC integers are `i31ref`) |
| `f64` | a float |
| `bool` | `t` / `nil` |
| `string` | a string |
| no result | the function's value is discarded |

Every other WIT type (`record`, `list`, `option`, `result`, resources, ...) is a
clear compile error at the export boundary today, naming the representation it is
settled to have once marshalling lands. A world's `import` items are ignored -- a
component's WASI imports come from the adapter surface it is built on, as section
6 shows. Full reference:
[`rontolisp:wit-export`](../../doc/en/reference/functions/rontolisp-wit-export.md)
and [Implementing a WIT World](../../doc/en/compiling/wasm.md#implementing-a-wit-world-wit-export).
