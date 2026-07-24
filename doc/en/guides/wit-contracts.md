# WIT Contracts (`wit-export` / `wit-import`)

Two directives let a program's boundary come straight from a `.wit` file
someone else wrote (or that a binding generator produced):
**`rontolisp:wit-export`** implements a world, and **`rontolisp:wit-import`**
calls an interface. Neither adds a new lowering path — each is a typed
front-end for the manual [`wasm-export` / `wasm-import`](wasm-host-boundary.md)
machinery, plus per-backend implementations that let the same source run
everywhere (typed component-model exports under `--component`, provider
callbacks on the interpreter and the JVM, byte-identical Preview 1 imports).

## Implementing a WIT World (`wit-export`)

**`rontolisp:wit-export`** takes a world someone else wrote, and has the
program **implement** it:

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

There is no `:params '(:string) :returns :string` anywhere — the types come
from the world. That is the whole point: hand-written boundary types sit next
to a `.wit` that is generated separately, and the two drift until
`wasmtime --invoke` fails at run time. With `wit-export` **the WIT is the
single source of truth**:

- The world is the program's export list, so a hand-written
  `rontolisp:wasm-export` in the same program is a compile error.
- Every export must have a matching `defun` of the right arity, every WIT
  type must be one the boundary carries (`s32`, `s64`, `f64`, `bool`,
  `string`), and an `async func` in the world lifts that export with
  `:async t` (so an export that does I/O is declared async by the WIT
  instead of being guessed at). Each mismatch is a compile error naming the
  WIT file and line:
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`.
- The contract is checked on **every** backend: a plain `rontolisp greet.lisp`
  run (or a `-o Greet.class` build) verifies the world and exports nothing,
  so a drift is caught long before a WASM build.

The directive is a front-end for the machinery of the previous sections, not
a second export path: it lowers into exactly the `rontolisp:wasm-export`
directives a hand-written implementation would carry, so **the emitted
component is byte-identical** to that one — on the GC path and under
[`--no-gc --component`](wasm-nogc.md#compact-component-output---no-gc---component)
alike (the latter is the backend to pick when the world uses `s64`, which the
wasm-GC `i31ref` integers cannot hold).

Adding [`--emit-wit`](#emitting-the-wit-world---emit-wit) to the build writes
out the component's real type, and its export lines come back the way you
wrote them, parameter names included — the WIT's names ride through into the
component's function type. (A hand-written export names its parameters `p0`,
`p1`, ... unless it declares them itself with `:param-names '(who)`.)

```bash
rontolisp greet.lisp --component -o greet.wasm --emit-wit   # writes greet.wit
```

```text
export greet: func(who: string) -> string;
```

That line is a fixpoint, though, not a verdict: it is derived *from* the
world, so it cannot contradict it. The reason to emit anyway is the rest of
the file — the `wasi:*` imports and the `wasi:cli/run` export that the world
says nothing about, and that a host has to supply. `greet.wit` is 149 lines
around that one export. Two differences from the input are deliberate: the
`///` doc comments are gone, because a component's type does not store them
(`wasm-tools` cannot recover them either), and the emitted world is always
`package root:component; world root`. That is what a component's type *is*.

### Exporting an interface

Most WIT worlds export an **interface** rather than a bare function — the
idiomatic shape keeps the interface definition separate from the world:

```console
// wit/adder.wit
package docs:adder@0.1.0;

interface add {
  add: func(x: s32, y: s32) -> s32;
}

world adder {
  export add;
}
```

`wit-export` implements this the same way: it resolves `export add;` to the
`add` interface defined in the file and checks each of its functions against
the program. The component then genuinely exports the interface, so
`wasm-tools component wit` and a host see `docs:adder/add`, not a flattened
top-level function:

```console
;;; adder.lisp
(defun add (x y) (+ x y))

(rontolisp:wit-export "wit/adder.wit" :world adder)
```

```bash
rontolisp adder.lisp --component -o adder.wasm
wasmtime run -W gc=y --invoke 'add(20, 22)' adder.wasm
# 42
```

An inline `export ops: interface { ... }` works the same way, keyed by its
plain name, and `--emit-wit` reconstructs the interface — `export
docs:adder/add@0.1.0;` plus its `interface` definition — byte-for-byte the way
`wasm-tools` prints it.

Current limitations:

- Only the world's **export** side is bound. `import` items are ignored (a
  component's WASI imports come from the fixed adapter surface it is built
  on — [`--emit-wit`](#emitting-the-wit-world---emit-wit) is how you see
  them), and an inline `import name: func(...)` is rejected rather than
  silently dropped; the functions a program calls are bound from an
  interface with [`wit-import`](#importing-a-wit-interface-wit-import) (or
  declared by hand with `rontolisp:wasm-import`).
- A world exports freestanding functions or an interface **defined in the same
  file** (above); an export naming an interface the file does not define — a
  bare `wasi:*` reference — is an error, and a `rontolisp:http-handler`
  program cannot use a world at all (a serve-mode component's only export is
  `wasi:http/handler@0.3.0`).
- `:s-expr` has no WIT spelling, so an export passing an arbitrary
  s-expression across the boundary still needs a hand-written
  `rontolisp:wasm-export`.
- On the interpreter the directive is evaluated in order and sees only the
  functions defined so far, so put it at the end of the file.

## Scaffolding an Implementation (`--scaffold-wit`)

`--scaffold-wit` is the answer to "someone handed me a `.wit`, now what": it
generates the skeleton of an implementation instead of compiling one.

```bash
rontolisp --scaffold-wit wit/greeter.wit -o greet.lisp   # no -o: print to stdout
```

```console
;;;; Implementation of the WIT world 'greeter' (wit/greeter.wit).
;;;;
;;;; The world is the contract: the compiler checks every defun below against
;;;; it, so a renamed export, a changed arity or a changed type is a compile
;;;; error rather than a runtime surprise. Fill in the bodies; each one signals
;;;; until you do.

;;; Greet someone by name.
;;; WIT: greet: func(who: string) -> string
(defun greet (who)
  (error "greet is not implemented yet"))

(rontolisp:wit-export "wit/greeter.wit" :world greeter)
```

The parameters are named as the WIT names them, each export's WIT signature
is carried above its stub as the contract it must satisfy, and the `///` doc
comments become `;;;` comments. The stubs signal at **run** time, not compile
time, so the generated file compiles unchanged and the exports can be filled
in one at a time. A world exporting an interface scaffolds one stub per
interface function, so the separated shape above yields the same skeleton. Add
`--world NAME` when the `.wit` declares several worlds.

## Emitting the WIT World (`--emit-wit`)

Add `--emit-wit` to any `--component` build to also write the component's
WIT description next to the `.wasm` output — `-o sumsq.wasm --emit-wit`
writes `sumsq.wit`:

```bash
rontolisp sumsq.lisp --component -o sumsq.wasm --emit-wit
```

```text
// sumsq.wit (the world; the file also carries the referenced package
// definitions, so it is self-contained and parseable on its own)
package root:component;

world root {
  import wasi:cli/types@0.3.0;
  import wasi:cli/stdout@0.3.0;
  // ... the WASI imports of the build's blob variant ...

  export wasi:cli/run@0.3.0;
  export sumsquared: func(p0: s32, p1: s32) -> s32;
}
```

The text matches what `wasm-tools component wit sumsq.wasm` prints for the
same bytes, so it is exactly the component's real surface — but nothing needs
to introspect the binary anymore: hand the `.wit` straight to a binding
generator. For example, jco generates TypeScript typings from it without
touching the `.wasm`:

```bash
npx @bytecodealliance/jco types sumsq.wit -o types/
# types/sumsq.d.ts: export function sumsquared(p0: number, p1: number): number;
```

The world's imports follow the build variant (plain, `rontolisp:fetch`,
`rontolisp:tcp-*`, or `rontolisp:http-handler`; with
[`--no-gc --component`](wasm-nogc.md#compact-component-output---no-gc---component)
the world is import-free, or carries the `wasi:cli/stdout@0.3.0` import — and
`async func` exports — when the program prints), an `:async t` export is
rendered as `async func`, and a `rontolisp:http-handler` build exports
`wasi:http/handler@0.3.0` instead of `run`. `--emit-wit` without `--component`
is a compile error — a core module has no WIT-level surface to describe.

### What `--emit-wit` Is For

It answers different questions depending on where the export list came from.

**A program without a world** — exports written by hand with
`rontolisp:wasm-export`, or an `:s-expr` export, which has no WIT spelling at
all — has no `.wit` anywhere. `--emit-wit` is the only way to get one, exactly
as above.

**A program with a world** ([`wit-export`](#implementing-a-wit-world-wit-export))
has already written its exports down. What it has not written down is the
component's **imports**, and that is the larger half: `wit-export` reads only
the world's `export` items, because a component's WASI surface comes from the
fixed adapter blob the build links, not from the world. The 6-line
`wit/greeter.wit` of the [previous section](#implementing-a-wit-world-wit-export)
compiles to a component whose real type is **149 lines** — ten `wasi:*`
imports and `export wasi:cli/run@0.3.0` wrapped around the one `greet` you
declared. Let that same `greet` call `rontolisp:fetch` and the build silently
adds two more imports (`wasi:http/types`, `wasi:http/client`), for **216
lines**; `rontolisp:tcp-*` pulls in `wasi:sockets` the same way. Short of
installing `wasm-tools` and introspecting the binary, `--emit-wit` is the
only way to see what you actually built — and it is precisely what a host,
or `jco`, needs in order to *supply* those imports.

What `--emit-wit` is **not** — for a program that has a world — is a drift
check on that program. The export lines are a fixpoint by construction: the
world produces the `rontolisp:wasm-export` directives, those produce the
component's function types, and those are what is printed back out, over a
boundary type set (`s32`, `s64`, `f64`, `bool`, `string`) that maps one-to-one
in both directions. They cannot come out disagreeing with the world you
handed in. Re-emitting the `.wit` and diffing it in CI is therefore a
regression check on *rontolisp's* type mapping — cheap, and worth keeping —
not a check on your source. The thing that catches a drifted program is
`wit-export` itself, and it already runs on every backend, including a plain
interpreter run. This is transitional: once a world can also declare the
imports a program binds, the emitted WIT becomes a genuinely two-sided
contract.

## Importing a WIT Interface (`wit-import`)

`wit-export` is the export side of a WIT contract.
**`rontolisp:wit-import`** is the import side: it declares that the program
**calls** a WIT interface, and binds every function that interface declares
as an ordinary Lisp function — its name, its lambda list and its types all
taken from the `.wit`. It is a compile-time directive that lowers into forms
that already exist, and *what* it lowers to depends on the backend. That is
the whole point: **one WIT, a different implementation per backend, zero
source changes.**

```console
// wit/host.wit
package example:host@0.1.0;

interface math {
  /// Add two integers on the host.
  add-ints: func(a: s32, b: s32) -> s32;
}
```

```console
;;; main.lisp -- the directive comes FIRST: it defines the functions the rest of
;;; the file calls.
(rontolisp:wit-import "wit/host.wit" :interface "example:host/math@0.1.0")

(defun add10 (n) (add-ints n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

On Preview 1 WASM each WIT function becomes a
[`rontolisp:wasm-import`](wasm-host-boundary.md#importing-host-functions): the
import **module** is the interface's bare name (`math`, overridable with
`:from`) and the import **field** is the WIT label in camelCase (`addInts` —
the JavaScript convention, and what `jco` produces; `:field-style :kebab`
keeps the label verbatim). So the host is satisfied exactly as before — here
by another Lisp module that exports the function under that field name:

```console
;;; host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "addInts" :params '(:int :int) :returns :int)
```

```bash
rontolisp host.lisp -o host.wasm --no-wasi
rontolisp main.lisp -o main.wasm --no-wasi
wasmtime run -W gc --preload math=host.wasm --invoke add10 main.wasm 32
# 42
```

The module is **byte-identical** to the one the hand-written
`(rontolisp:wasm-import 'add-ints :from "math" :as "addInts" :params '(:int :int) :returns :int)`
produces — the directive is a typed front-end for that machinery, not a
second import path — and [`--optimize`](../compiling/wasm.md#optimize-tree-shaking)
still shakes out the imports the program never calls, so binding a 29-function
interface and using three of them costs nothing.

### Providers: the same source on the interpreter and the JVM

There is no WASM host on the interpreter or the JVM, so there each WIT
function becomes an ordinary `defun` that dispatches through the interface's
**provider**: a Lisp callable taking the bound function's Lisp member name (a
string) followed by that function's arguments.
[`rontolisp:wit-provide`](../reference/functions/rontolisp-wit-provide.md)
binds one — and rontolisp ships **no provider for any interface**. It knows
the provider mechanism; it does not know what `wasi:keyvalue` is. Implementing
a WIT interface is ordinary Lisp code:

```console
;;; counter.lisp -- wasi:keyvalue, against a store written in Lisp.
(rontolisp:wit-import "wit/store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)

(defvar *rows* (make-hash-table :test #'equal))

(defun my-store (member &rest args)
  (cond ((string= member "open") 1)              ; the bucket handle: any integer
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) *rows*) (nth 2 args))
         nil)
        ((string= member "bucket-get") (gethash (nth 1 args) *rows*))
        (t (error 'rontolisp:wit-error :payload (list :other member)))))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)

(defvar *bucket* (kv:open "counts"))

(kv:bucket-set *bucket* "visits" "41")
(print (kv:bucket-get *bucket* "visits"))   ; "41"
```

`:package kv` synthesizes the `defpackage` that exports the bindings, a WIT
`resource` method takes its handle as the first argument (`bucket.get`
becomes `(kv:bucket-get b "visits")`), and each binding is an ordinary
function, so `#'kv:bucket-get`, `funcall` and `mapcar` work on it. Calling
one with no provider bound signals `rontolisp:wit-error` — `No provider is
bound for the WIT interface wasi:keyvalue/store@0.2.0 -- bind one with
rontolisp:wit-provide` — rather than reaching some default.

The payoff is that a provider is *just a function*: swap the hash table above
for a real store — Redis, a file, a JDBC connection — and the code calling
`(kv:bucket-set b "visits" "41")` does not change. The
[`wit/keyvalue` example](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
runs one page-view counter over three of them (a portable Lisp store, a
`java.util.LinkedHashMap` one on the JVM, and wasmtime's own `wasi:keyvalue`
implementation as a component) with identical output. Compile the same
source to WASM instead and the **host** implements the interface: a top-level
`rontolisp:wit-provide` is then **dropped** (the host is the provider),
rather than being an error, precisely so that one source runs everywhere.

A WIT `result<T, E>` is not a value: the ok arm is the return value, and the
error arm signals the `rontolisp:wit-error` condition carrying the mapped
`E`, which `handler-case` catches and `rontolisp:wit-error-payload` unpacks.

### Components: the host is the provider (`--component`)

Compile the very same source with `--component` and the interface becomes a
real component-model **import**: the component declares it in its type, and
every bound function is `canon lower`ed into the core module, so the calls
go out through the canonical ABI. There is no provider inside the component
at all — **the host is the provider**, and any host (or any other component)
that exports the interface satisfies it. wasmtime implements `wasi:keyvalue`,
so a program written against it runs with no adapter and no rewriting:

```bash
rontolisp counter.lisp -o counter.wasm --component
wasmtime run -W gc=y -W exceptions=y \
    -S keyvalue=y counter.wasm
```

The canonical ABI is what marshals the rich types, so the component boundary
carries much more than the Preview 1 one: a `result` (whose error arm arrives
as a `rontolisp:wit-error` condition, caught with `handler-case`), an
`option`, a `record` (a keyword plist), a `variant`, an `enum`, a `tuple`, a
`list<T>`, a `list<u8>`, a `string`, a `bool`, and `resource` handles.

Everything but `list<T>` crosses **in both directions**, and an argument takes
exactly the shape the same type takes as a return value — so a value one call
hands you goes straight into the next:

```console
;;; wasi:http/types, imported and called: a variant argument, whose `other` case
;;; carries a string
(http:outgoing-request-set-method req :post)
(http:outgoing-request-set-method req '(:other . "PATCH"))
(http:outgoing-request-method req)                 ; => (:other . "PATCH")

;;; wasi:sockets/types: an enum argument, then a variant whose case payload is a
;;; record (a keyword plist) carrying a tuple (a positional list)
(let ((s (sock:tcp-socket-create :ipv4)))
  (sock:tcp-socket-bind s '(:ipv4 :port 0 :address (127 0 0 1))))
```

The one shape that still does not lower is a **`list<T>` argument**
(`list<u8>` does, as a byte string): an argument is flattened, and a list
would have to be written into linear memory as a canonical array instead. It
is a compile error naming the WIT line, and `flags` does not cross in either
direction yet.

One interface a component **cannot** bind is one it already imports for its
own WASI surface — and that surface grows with what the program uses
(`rontolisp:fetch` pulls in `wasi:http/types` and `wasi:http/client`, the
`rontolisp:tcp-*` built-ins pull in `wasi:sockets/types`). A component
cannot import the same interface twice, so that is a compile error too:
drive the interface through the WIT binding *instead of* the built-in, not
alongside it.

A component imports **only the functions the program actually calls** (there
is no core tree shaker on this path, so unused interface members are dropped
from the import itself; `--no-prune` keeps them all), and
[`--emit-wit`](#emitting-the-wit-world---emit-wit) writes that pruned
interface into the component's world — where `wasm-tools component wit`
agrees with it, byte for byte. A component that imports nothing is
byte-identical to one built before any of this existed.

That is also how components **compose**: a component that imports
`wasi:keyvalue/store` plugs into any component that exports it, in any
language, with [`wac`](https://github.com/bytecodealliance/wac). The host
does not have to be a runtime built-in.

### A served handler with a real store

A **served** component ([`rontolisp:http-handler`](http-handler.md) +
`--component`) imports user interfaces the same way: its imports are not only
the fixed `wasi:http` surface it exports through. That is what lets a handler
keep state at all — a `wasi:http` host instantiates the component **afresh
for every request**, so a global hash table reads back empty every time,
while a store lives outside it:

```bash
rontolisp page-hits-server.lisp -o server.wasm --component
wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
curl http://127.0.0.1:8080/index
```

Whether the counts then *survive* is the host's business, not the
component's: wasmtime's built-in key-value provider is an in-memory store it
rebuilds per instance (so, under `wasmtime serve`, per request), while a host
that links an out-of-process provider keeps them — on wasmCloud (`wash dev`)
the same component counts 1, 2, 3. The interfaces a served component may
*not* bind are the ones its own surface already imports: `wasi:http/types`,
`wasi:http/client`, `wasi:cli/types`, `wasi:cli/stdout`, `wasi:cli/stderr`,
`wasi:clocks/*` and `wasi:random/random`.

The full example is [`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/main/examples/wit/keyvalue).

### Releasing a resource (`<resource>-drop`)

A handle has to be given back, and **WIT declares no function for giving it
back**: releasing a resource is a canonical built-in of the component model,
not a member of the interface. So rontolisp names it — **`<resource>-drop`**,
one argument, the handle — symmetric with the `<resource>-new` a constructor
binds:

```console
(let ((bucket (kv:open "")))
  (kv:bucket-set bucket "visits" "41")
  (print (kv:bucket-get bucket "visits"))
  (kv:bucket-drop bucket))
```

It is bound **only when the program names it** (`--no-prune` and `--dynamic`
bind every resource's drop instead), which is why a component compiled before
drops existed comes out byte-identical — a WIT *function*, by contrast, is
bound whether the program calls it or not. On the interpreter and the JVM
the drop reaches the interface's provider as the member `"bucket-drop"`, so
what it *means* is the provider's decision: forget the handle, close the
connection, or answer `nil` because there is nothing to release. On Preview 1
it is a **no-op** — a handle there is an opaque integer the host handed over,
and rontolisp will not invent an import for a function the WIT never
declared. Under `--component` it becomes `canon resource.drop`, handing the
handle back to the host's own table.

This is not only about leaks. An interface may make dropping an
**obligation**: `wasi:http` requires an `outgoing-body`'s child
`output-stream` to be dropped before the body is finished, and traps if it
is not. And a drop releases the *reference*, never the thing behind it —
the store stays, and the next `kv:open` sees every key still in it.

Current limitations:

- `--no-gc` rejects the directive with a clear error: its contract is a plain
  MVP module that imports nothing at all.
- On the Preview 1 boundary only the types `rontolisp:wasm-import` can carry
  cross — the integer scalars up to 32 bits, the float scalars, `bool`,
  `string`, `list<u8>` and resource handles. A `record`, `option`, `result`
  or `s64` is a compile error naming the WIT file and line, even though
  `--component`, the interpreter and the JVM all bind it (the `wasi:keyvalue`
  program above is therefore a component or an interpreter/JVM program, not
  a Preview 1 one: its `result` arms keep it off that boundary). A core
  import is a bare host function, with no component type to describe a
  richer shape with. `stream` and `future` are rejected on every backend.
- Under `--component` a **`list<T>` argument** (other than `list<u8>`), and
  `flags` anywhere, is a compile error; a `list<T>` still crosses as a
  result.
- The directive binds an **interface**. A world's `import` items are still
  not read.
- It must appear at top level **before** the code that calls the interface —
  it is what defines the package and the bindings — which is the opposite of
  `wit-export`.

The [wit-import](../reference/functions/rontolisp-wit-import.md) and
[wit-provide](../reference/functions/rontolisp-wit-provide.md) reference
pages carry the full option list, the name-mapping rules and the WIT type
table.
