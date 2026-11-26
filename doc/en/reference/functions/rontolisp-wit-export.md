# rontolisp:wit-export

`(rontolisp:wit-export "world.wit" :world name)`

Declares that the program **implements a WIT world**. The world's `export` items
are checked against the program's top-level `defun`s at compile time and lowered
into the [`rontolisp:wasm-export`](rontolisp-wasm-export.md) directives they
stand for, so the boundary types are never written by hand and the `.wit` file
and the compiled component cannot drift apart. The WIT is the single source of
truth: the world is the program's export list (a hand-written
`rontolisp:wasm-export` alongside it is an error), and for a world of
[flat types](#supported-wit-types) that defines no type of its own the emitted
component is byte-identical to the one those hand-written directives would have
produced. It
is a compile-time directive, not an ordinary function: on the **interpreter**
and **JVM** backends it runs the same contract check and then returns `nil`, so
the same source runs on every backend. See
[Implementing a WIT World](../../guides/wit-contracts.md#implementing-a-wit-world-wit-export)
for the full guide.

Because the directive reads a `.wit` file from disk, the example is shown
statically:

```console
// wit/greeter.wit
package example:greeter;

world greeter {
  /// Greet someone by name.
  export greet: func(who: string) -> string;
}
```

```console
;;; greet.lisp -- the directive comes last: on the interpreter it sees only the
;;; functions defined so far.
(defun greet (who)
  (concatenate 'string "Hello, " who "!"))

(rontolisp:wit-export "wit/greeter.wit" :world greeter)
```

```bash
rontolisp greet.lisp --component -o greet.wasm
wasmtime run --invoke 'greet("world")' greet.wasm
# "Hello, world!"
```

## Arguments

- The WIT file path, as a string. A relative path resolves against the directory
  of the source file that names it, like [`load`](load.md). It may name a WIT
  package directory, read as
  [`rontolisp:wit-import`](rontolisp-wit-import.md#arguments) reads one.
- `:world` — the world to implement, as a bare symbol (spelled the way WIT
  spells it) or a string. It may be omitted when the file declares exactly one
  world; when the file declares several, one must be named. For a package
  directory, the worlds counted and searched are the package's own, not those of
  its `deps/`.
- `:reactor` — `t` makes the program a reactor, whose entry points are the
  world's exports: a `--component` build lifts no `wasi:cli/run`, and the first
  call of an export runs the program's top level, once, before its own body, so
  an export a host calls directly reads what the top level set
  ([A reactor](../../guides/wit-contracts.md#a-reactor-reactor-t)). Without it a
  host that calls an export never runs the top level. A WASI command module
  (`-o out.wasm` alone, or `--native`) refuses it; `--no-wasi` and `--no-gc`
  outputs are reactors already. The interpreter and the JVM ignore it.
- `:octets` — `t` hands a `list<u8>` parameter over as the octets it holds, an
  `(unsigned-byte 8)` vector, instead of the string they decode to as UTF-8
  ([`rontolisp:wit-import`](rontolisp-wit-import.md#arguments)'s option, for the
  other direction).
- `:envelope` — `t` says a function whose WIT result is a `result` answers it as
  `(:ok . value)` or `(:error . value)` itself, instead of the ok value with
  `rontolisp:wit-error` signalled for the error arm
  ([Compound types](#compound-types)). The Clojure front end's `rontolisp.wit/export`
  and the Java front end's `@WitExport` pass it.

Everything else comes from the world: `rontolisp:wasm-export`'s `:params`,
`:param-names`, `:returns` and `:async` are all filled in from it, so the
`defun`s carry no boundary types at all.

## Supported WIT types

| WIT type | Boundary type | Lisp value |
| --- | --- | --- |
| `s8` `s16` `s32` | `:s8` `:s16` `:s32` | an integer |
| `u8` `u16` `u32` | `:u8` `:u16` `:u32` | an integer |
| `s64` `u64` | `:s64` `:u64` | an integer; a `u64` value of 2^63 or more traps at the boundary |
| `f64` | `:float` | a float |
| `bool` | `:bool` | `t` or `nil` |
| `string` | `:string` | a string |
| (no result) | `:void` | the function's value is discarded |

The whole fixed-width integer family crosses, so the canonical component-model
tutorial world compiles unedited:

```console
// wit/adder.wit
package docs:adder@0.1.0;

interface add {
  add: func(x: u32, y: u32) -> u32;
}

world adder {
  export add;
}
```

Each type carries its own range exactly, or the call traps — a negative returned
through `u32` is refused rather than delivered as `4294967295`. The component
model has no integer subtyping, so this is not cosmetic: a component that lifted
`u32` as `s32` would be rejected against its own world by
`wasm-tools component targets`, by `jco`, and by any `bindgen`-based host.

An `async func` in the world lifts the export with `:async t`, so blocking is
always legal inside it: I/O inside a sync export usually works too (the
asynchronous built-ins complete without blocking when the host accepts
immediately), but a host that reports BLOCKED would make it trap, and the
async lift removes that residual risk — the WIT states
which exports are async rather than leaving it to be guessed.

### Compound types

A component's export (`--component`) carries the other WIT types too, each as
the Lisp value [`rontolisp:wit-import`](rontolisp-wit-import.md) gives it:

| WIT type | Lisp value |
| --- | --- |
| `record` | a keyword plist, `(:x 1 :y 2)` |
| `enum` | a keyword, `:green` |
| `flags` | a list of the keywords of the labels that are set, `(:read :write)` |
| `variant` | the case's keyword, or `(keyword . payload)` when the case carries one |
| `tuple` | a list |
| `list<T>` | a list |
| `list<u8>` | a string, or an `(unsigned-byte 8)` vector; a parameter arrives as a string, or as the vector under `:octets t` |
| `option<T>` | the value, or `nil` for none |
| `result<T, E>` as the function's result | the value is the ok arm; the error arm is a `rontolisp:wit-error` signalled with the payload |
| `result<T, E>` anywhere else | `(:ok . value)` or `(:error . value)` |
| `f32` `char` | a float, a character |

```console
// wit/shapes.wit
package example:shapes@0.1.0;

interface geometry {
  record point { x: s32, y: s32 }
  variant shape { circle(f64), rect(point), dot }

  flip: func(p: point) -> point;
  area: func(s: shape) -> result<f64, string>;
}

world shapes {
  export geometry;
}
```

```console
(defun flip (p) (list :x (getf p :y) :y (getf p :x)))

(defun area (s)
  (cond ((eq s :dot) (error 'rontolisp:wit-error :payload "a dot has no area"))
        ((eq (car s) :circle) (* 3.0d0 (cdr s) (cdr s)))
        (t (float (* (getf (cdr s) :x) (getf (cdr s) :y)) 1.0d0))))

(rontolisp:wit-export "wit/shapes.wit" :reactor t)
```

```bash
rontolisp shapes.lisp --component -o shapes.wasm
wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm
# {x: 2, y: 1}
wasmtime run --invoke 'area(dot)' shapes.wasm
# err("a dot has no area")
```

A function whose answer is a `result` returns the ok value and signals
`rontolisp:wit-error` for the error arm, as a
[provider](rontolisp-wit-provide.md) does; any other condition it leaves
uncaught traps the call. A value the declared type cannot state traps too: a
`u8` field given 300, a keyword that names no case or no label, a `list<T>`
answered with anything but a list.

The component declares every type its world and its exported interfaces define,
an alias of a flat type (`type id = u32`) among them, and exports an interface
of types alone as those types; `--no-gc --component` does the same for the flat
types it carries. The component is therefore the world's
(`wasm-tools component targets world.wit out.wasm` accepts it), `--emit-wit`
prints the types back, and another component can import the interface
([`examples/wit/shapes`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes)
composes two).

A type taken from another interface with `use` is declared as the using scope's
own, under the name the clause gives it, and the component imports nothing for
it: two value types of one shape are one type in the component model. A type
the copied one holds comes with it -- an alias as what it names, a record, a
variant or an enum under its own name:

```console
interface geometry {
  record point { x: s32, y: s32 }
  record line { a: point, b: point }
}

interface shapes {
  use geometry.{line};            // declares line, and point with it
  length: func(l: line) -> f64;
}
```

A world's types are imports of the component, so a `use` in the world names
such a type itself (`use geometry.{line, point};`). A name the scope already
gives another type is refused at the clause; rename it there
(`point as corner`).

Not carried, each a compile error at the export's WIT line: a resource handle,
a `stream`, a `future`, and a compound type in an `async func`; an
exported interface that declares a `resource` is refused as a whole. A WASI
module (`-o out.wasm` alone) and `--no-gc` export the flat types alone and
refuse a world with any other.

A `--no-wasi` module exports them too, all but `flags`. Its core export takes the canonical ABI's
core values, and the [`--emit-js-glue`](../../guides/wasm-host-boundary.md#wit-interfaces-beyond-the-flat-set)
file lowers and lifts them: each export is an entry point under its label in
camelCase, called with the JavaScript values a host import is handed (an object
for a record, `{ tag, val }` for a variant, `undefined` for none, a BigInt for
64 bits). A `result` answer returns its ok value and throws the error arm as the
glue's `WitError`. `--reentrant` refuses such an export.

```js
import { instantiate, WitError } from "./shapes.js";

const lisp = instantiate(new WebAssembly.Module(bytes));
lisp.flip({ x: 1, y: 2 }); // { x: 2, y: 1 }
try {
  lisp.area({ tag: "dot" });
} catch (error) {
  if (error instanceof WitError) console.log(error.payload); // a dot has no area
}
```

## What it checks

Every violation is a compile error naming the WIT file and the line of the
offending export:

- an export the world declares with no matching `defun` —
  `wit/greeter.wit:5: export 'greet' has no matching (defun greet ...) in the program`
- an arity mismatch —
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`
  (an exported function takes required parameters only: `&optional` / `&rest` /
  `&key` are rejected)
- a WIT type the export does not carry: beyond the flat types on a WASI module
  or under `--no-gc`, and a handle, a `stream` or a `future` anywhere
- an exported interface that declares a `resource`, and a `use` clause whose
  type holds one the scope has no name for (a world's) or whose name the scope
  gives another type
- an export name that is not a component-model label (lower-kebab-case words), a
  duplicate export (refused by the WIT reader first, in `wasm-tools component
  wit`'s words: `` export `a` conflicts with prior func of same name ``), or
  the reserved name `run` (the component's `wasi:cli/run`
  entry point)
- a world with no exports, a `:world` the file does not declare, or an omitted
  `:world` when the file declares several

Because the world *is* the export list, so is mixing it with the hand-written
form: a `rontolisp:wasm-export` in a program that also has a
`rontolisp:wit-export`, and a `rontolisp:http-handler` together with a world (a
serve-mode component exports only `wasi:http/handler@0.3.0`).

## Limitations

- Only the world's **export** side is a contract. `import` items are ignored (a
  component's WASI imports come from the build, not from the world),
  and an inline `import name: func(...)` is rejected rather than silently
  dropped — the functions a program calls are bound from an interface with
  [`rontolisp:wit-import`](rontolisp-wit-import.md), or declared by hand with
  [`rontolisp:wasm-import`](rontolisp-wasm-import.md) (both Preview 1 only).
  The component you get therefore has a much larger type than the world you wrote:
  the 6-line world above compiles to a 149-line component type (ten `wasi:*`
  imports plus `export wasi:cli/run`), and calling `rontolisp:fetch` inside `greet`
  silently adds five more. `--emit-wit` is how you see it.
- A world exports freestanding functions or an **interface defined in the same
  file**: `export add;` referencing an in-file `interface add { ... }`, or an
  inline `export ops: interface { ... }`, is implemented member by member and
  produces a real `docs:adder/add` instance export (see
  [Exporting an interface](../../guides/wit-contracts.md#exporting-an-interface)).
  An export naming an interface the file does not define (a bare `wasi:*`
  reference) is still an error.
- Two exports of one label -- a function of the world and an interface's, or two
  interfaces' -- are both the `defun` the label spells, and a component exports
  each: `wasmtime run --invoke 'docs:adder/add.add@0.1.0(20, 22)'` calls the
  interface's, a bare `add(20, 22)` the world's. A core module (Preview 1,
  `--no-wasi`) exports a function by its label alone and refuses them.
- `:s-expr` has no WIT spelling, so an export carrying an arbitrary s-expression
  still needs a hand-written
  [`rontolisp:wasm-export`](rontolisp-wasm-export.md) — and therefore a program
  without a world.
- On the **interpreter** the directive is an ordinary form evaluated in order, so
  it sees only the functions defined **so far**: put it at the end of the file.
  (The compile path collects every top-level `defun` first, so there the position
  does not matter.)
- Adding `--emit-wit` writes the component's real type back out, and its export
  lines reproduce the world handed in, parameter names included — but by
  construction, not by coincidence: the world *is* what those lines are derived
  from, so they cannot disagree with it. Emitting is worth it for the import side,
  not as a check on your program (that is `wit-export`'s own job, on every
  backend). Two deliberate differences from the input file: the `///` doc comments
  are gone (a component's type does not store them), and the emitted world is
  always `package root:component; world root`.
