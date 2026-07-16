# rontolisp:wit-export

`(rontolisp:wit-export "world.wit" :world name)`

Declares that the program **implements a WIT world**. The world's `export` items
are checked against the program's top-level `defun`s at compile time and lowered
into the [`rontolisp:wasm-export`](rontolisp-wasm-export.md) directives they
stand for, so the boundary types are never written by hand and the `.wit` file
and the compiled component cannot drift apart. The WIT is the single source of
truth: the world is the program's export list (a hand-written
`rontolisp:wasm-export` alongside it is an error), and the emitted component is
byte-identical to the one those hand-written directives would have produced. It
is a compile-time directive, not an ordinary function: on the **interpreter**
and **JVM** backends it runs the same contract check and then returns `nil`, so
the same source runs on every backend. See
[Implementing a WIT World](../../compiling/wasm.md#implementing-a-wit-world-wit-export)
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
wasmtime run -W gc=y --invoke 'greet("world")' greet.wasm
# "Hello, world!"
```

## Arguments

- The WIT file path, as a string. A relative path resolves against the directory
  of the source file that names it, like [`load`](load.md).
- `:world` — the world to implement, as a bare symbol (spelled the way WIT
  spells it) or a string. It may be omitted when the file declares exactly one
  world; when the file declares several, one must be named.

Everything else comes from the world: `rontolisp:wasm-export`'s `:params`,
`:param-names`, `:returns` and `:async` are all filled in from it, so the
`defun`s carry no boundary types at all.

## Supported WIT types

| WIT type | Boundary type | Lisp value |
| --- | --- | --- |
| `s32` | `:int` | an integer (31-bit signed range) |
| `s64` | `:long` | an integer; needs `--no-gc` (wasm-GC integers are `i31ref`) |
| `f64` | `:float` | a float |
| `bool` | `:bool` | `t` or `nil` |
| `string` | `:string` | a string |
| (no result) | `:void` | the function's value is discarded |

An `async func` in the world lifts the export with `:async t`, so blocking is
always legal inside it: I/O inside a sync export usually works too (the
asynchronous built-ins complete without blocking when the host accepts
immediately), but a host that reports BLOCKED would make it trap, and the
async lift removes that residual risk — the WIT states
which exports are async rather than leaving it to be guessed. Every other WIT
type (`record`, `list`, `option`, `result`, resources, ...) is a compile error at
the export boundary today; the error names the rontolisp representation the type
is settled to have, once marshalling it lands.

## What it checks

Every violation is a compile error naming the WIT file and the line of the
offending export:

- an export the world declares with no matching `defun` —
  `wit/greeter.wit:5: export 'greet' has no matching (defun greet ...) in the program`
- an arity mismatch —
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`
  (an exported function takes required parameters only: `&optional` / `&rest` /
  `&key` are rejected)
- a WIT type the export boundary does not carry, including `s64` on the wasm-GC
  backend —
  `calc.wit:4: export 'square': s64 (n) requires --no-gc (the wasm-GC backend's integers are i31ref)`
- an `async func` under `--no-gc --component`, whose adapter-free reactor has no
  async machinery
- an export name that is not a component-model label (lower-kebab-case words), a
  duplicate export, or the reserved name `run` (the component's `wasi:cli/run`
  entry point)
- a world with no exports, a `:world` the file does not declare, or an omitted
  `:world` when the file declares several

Because the world *is* the export list, so is mixing it with the hand-written
form: a `rontolisp:wasm-export` in a program that also has a
`rontolisp:wit-export`, and a `rontolisp:http-handler` together with a world (a
serve-mode component exports only `wasi:http/handler@0.3.0`).

## Limitations

- Only the world's **export** side is a contract. `import` items are ignored (a
  component's WASI imports come from the fixed adapter surface it is built on),
  and an inline `import name: func(...)` is rejected rather than silently
  dropped — the functions a program calls are bound from an interface with
  [`rontolisp:wit-import`](rontolisp-wit-import.md), or declared by hand with
  [`rontolisp:wasm-import`](rontolisp-wasm-import.md) (both Preview 1 only).
  The component you get therefore has a much larger type than the world you wrote:
  the 6-line world above compiles to a 149-line component type (ten `wasi:*`
  imports plus `export wasi:cli/run`), and calling `rontolisp:fetch` inside `greet`
  silently adds five more. `--emit-wit` is how you see it.
- Only plain function exports (`export name: func(...)`) are implemented; a world
  that exports an interface is an error.
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
