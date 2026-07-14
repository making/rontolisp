# rontolisp:wit-import

`(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)`

Declares that the program **calls a WIT interface**. Every function the interface
declares is bound as an ordinary Lisp function, with its name, its lambda list
and its types taken from the `.wit` file — the mirror image of
[`rontolisp:wit-export`](rontolisp-wit-export.md), and, like it, a compile-time
directive that **lowers into forms that already exist** rather than a new call
path. What it lowers to depends on the backend, and that is the whole point:
**one WIT, a different implementation per backend, zero source changes**. On the
**interpreter** and the **JVM** each binding becomes a `defun` dispatching
through a *provider* — an ordinary Lisp callable you bind with
[`rontolisp:wit-provide`](rontolisp-wit-provide.md); on **Preview 1 WASM** it
becomes a [`rontolisp:wasm-import`](rontolisp-wasm-import.md); and under
**`--component`** the interface becomes a real component-model **import**, whose
functions are `canon lower`ed into the module — so the provider is the *host*,
and the component composes with anyone who exports that interface. See
[Importing a WIT Interface](../../compiling/wasm.md#importing-a-wit-interface-wit-import)
for the full guide.

Because the directive reads a `.wit` file from disk, the example is shown
statically:

```console
// wit/store.wit -- an excerpt of the real wasi:keyvalue/store@0.2.0
package wasi:keyvalue@0.2.0;

interface store {
  variant error {
    no-such-store,
    access-denied,
    other(string),
  }

  resource bucket {
    get: func(key: string) -> result<option<list<u8>>, error>;
    set: func(key: string, value: list<u8>) -> result<_, error>;
    delete: func(key: string) -> result<_, error>;
    exists: func(key: string) -> result<bool, error>;
  }

  open: func(identifier: string) -> result<bucket, error>;
}
```

```console
;;; counter.lisp -- the directive comes FIRST: it defines the kv package and the
;;; functions the rest of the file calls.
(rontolisp:wit-import "wit/store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)

;;; What those functions CALL is a provider -- ordinary Lisp code, and yours to
;;; write. rontolisp ships none: it knows the mechanism, not the interface.
(defvar *rows* (make-hash-table :test #'equal))

(defun my-store (member &rest args)
  (cond ((string= member "open") 1)              ; the bucket handle: any integer
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) *rows*) (nth 2 args))
         nil)
        ((string= member "bucket-get") (gethash (nth 1 args) *rows*))
        ((string= member "bucket-exists") (if (gethash (nth 1 args) *rows*) t nil))
        (t (error 'rontolisp:wit-error :payload (list :other member)))))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)

(defvar *bucket* (kv:open "counts"))

(kv:bucket-set *bucket* "visits" "41")
(print (kv:bucket-get *bucket* "visits"))
(print (kv:bucket-exists *bucket* "missing"))
```

```bash
rontolisp counter.lisp                     # the provider bound above
# "41"
# nil
rontolisp counter.lisp -o Counter.class && java Counter
# "41"
# nil
```

Nothing is bound by hand: `kv:bucket-get`, and its `(self key)` lambda list, come
from the WIT. What those functions *call* is the one thing a `.wit` cannot say —
so it is a **provider**, and rontolisp ships **none, for any interface**. It
knows the provider mechanism; it does not know what `wasi:keyvalue` is. An
implementation of a WIT interface is therefore ordinary Lisp code, like
`my-store` above, and swapping that hash table for a real store is one line the
program never sees.
[`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
is exactly that: one page-view counter, with a portable in-memory Lisp store
behind it on the interpreter, a `java.util.LinkedHashMap` one on the JVM, and —
compiled with `--component` — **wasmtime's own `wasi:keyvalue` implementation**, a
host that has never heard of the program. The output is identical all three ways.

## Arguments

- The WIT file path, as a string. A relative path resolves against the directory
  of the source file that names it, like [`load`](load.md).
- `:interface` — the interface to bind (required). Written as the fully-qualified
  id (`"wasi:keyvalue/store@0.2.0"`), the id without its version
  (`"wasi:keyvalue/store"`), or the bare interface name (`store`) when the file
  defines it only once. A string or a bare symbol.
- `:package` — the Lisp package the bindings land in (`kv:open`, `kv:bucket-get`).
  A `defpackage` exporting them is synthesized, so no `defpackage` is written by
  hand. Omitted, the names land in the current package.
- `:from` — the Preview 1 WASM import module name. Defaults to the interface's
  bare name (`store`). Ignored on the other backends (a component imports the
  interface under its fully-qualified id, which is not renameable).
- `:field-style` — how a WIT label is spelled as a Preview 1 import **field**:
  `:camel` (the default — `create-shader` becomes `createShader`, the JavaScript
  convention and what `jco` produces) or `:kebab` (the label verbatim). Ignored
  on the other backends.

## What gets bound

| WIT | Lisp function | Call |
| --- | --- | --- |
| `open: func(identifier: string) -> ...` | `kv:open` | `(kv:open "counts")` |
| `resource bucket` method `get: func(key: string) -> ...` | `kv:bucket-get` | `(kv:bucket-get b "visits")` |
| `resource bucket` `constructor(...)` | `kv:bucket-new` | `(kv:bucket-new ...)` |
| `resource bucket` `static func from-name` | `kv:bucket-from-name` | `(kv:bucket-from-name "x")` |

A resource member is prefixed with the resource, so two resources may declare
the same method without colliding in the flat Lisp-2 function namespace, and a
**method takes the handle as its first argument** (`self`, which WIT leaves
implicit). The other parameters are named exactly as the WIT names them, and a
resource itself is an opaque integer handle. Each binding is an **ordinary
function**, so `#'kv:bucket-get`, `funcall`, `mapcar` and `eval` work on it with
no extra wiring.

## How it lowers

| Backend | The directive becomes |
| --- | --- |
| interpreter | one `defun` per WIT function, dispatching through the interface's provider |
| JVM (`-o Prog.class`) | the same `defun`s, compiled |
| Preview 1 WASM (`-o prog.wasm`) | one [`rontolisp:wasm-import`](rontolisp-wasm-import.md) per WIT function |
| `--component` | a component-model **instance import** of the interface, each function `canon lower`ed into the core module |
| `--no-gc` | a compile error (its MVP module imports nothing) |

On Preview 1 the module is **byte-identical** to the hand-written equivalent, and
[`--optimize`](../../compiling/wasm.md#optimize-tree-shaking) still shakes out the
imports the program never calls:

```console
;;; What (rontolisp:wit-import "wit/host.wit" :interface "example:host/math@0.1.0")
;;; lowers to on Preview 1 WASM, for `add-ints: func(a: s32, b: s32) -> s32`:
(rontolisp:wasm-import 'add-ints :from "math" :as "addInts"
                       :params '(:int :int) :returns :int)
```

Under `--component` the interface becomes an instance import of the component,
and each bound function a `canon lower`ed core import. A component **only imports
the functions the program actually calls** (the component path has no core tree
shaker, so unused interface members are dropped from the import instead;
`--no-prune` keeps them all), and [`--emit-wit`](../../compiling/wasm.md) writes
that pruned interface into the component's world — where `wasm-tools component
wit` agrees with it, byte for byte. An import-free component is unchanged.

```bash
rontolisp counter.lisp -o counter.wasm --component
wasmtime run -W gc=y -W exceptions=y -W component-model-more-async-builtins=y \
    -S keyvalue=y counter.wasm             # the HOST is the provider
```

## Providers

On the interpreter and the JVM there is no host, so the call goes to a
**provider**: an ordinary Lisp callable taking the bound function's Lisp member
name (a **string** — `"open"`, `"bucket-get"`) followed by that function's
arguments. [`rontolisp:wit-provide`](rontolisp-wit-provide.md) binds one:

```console
(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)
```

- **rontolisp ships no provider for any interface.** Binding an interface's
  functions is the language's business; implementing them is yours. A new host
  interface therefore costs a `.wit` file and one `rontolisp:wit-provide`, not
  core code.
- Calling a bound function with **no provider bound** for its interface signals
  [`rontolisp:wit-error`](rontolisp-wit-provide.md#the-wit-error-condition):
  `No provider is bound for the WIT interface wasi:keyvalue/store@0.2.0 -- bind one with rontolisp:wit-provide`.
- `rontolisp:wit-provide` **replaces** the interface's provider, so a fake store
  can be swapped for the real one — one line, in a file of its own if you like,
  and no call site changes.
- On the **WASM** backends the host supplies the imports, so a top-level
  `rontolisp:wit-provide` is **dropped** (inert), not an error — one source runs
  everywhere.

## Errors (`result<T, E>`)

A WIT `result<T, E>` is not a value: the **ok arm is the function's return
value**, and the **error arm signals `rontolisp:wit-error`** carrying the mapped
`E` as its payload (the settled mapping, on every backend). The provider is what
signals it; a caller handles it with `handler-case` and reads the payload with
[`rontolisp:wit-error-payload`](rontolisp-wit-provide.md#the-wit-error-condition).

That is the RETURN direction — the only one a `result` had until now. A `result`
you **pass in** cannot signal anything (an argument has to *say* which arm it
is), so it keeps its arm: the envelope cons `(:ok . V)` / `(:error . E)`, which
is exactly the shape a returned `result` has before the ok arm is unwrapped. The
asymmetry is one-way and deliberate: **unwrapped on the way out, wrapped on the
way in** — so a value one call returns can be passed straight into the next.

## Supported WIT types

The boundary is three-tiered. On the **interpreter and the JVM** the call is an
ordinary Lisp call, so every representation crosses — the table is the contract
the provider is written against, not a marshaller. On the **Preview 1 WASM**
boundary only the flat set `rontolisp:wasm-import` can carry crosses: a core
import is a bare host function, with no component type to describe a richer shape
with. Under **`--component`** the canonical ABI marshals the rich types, so a
`record`, `variant`, `enum`, `option`, `tuple` or `result` crosses **in both
directions** — as an argument as well as a result. Two do not: `flags` (in
neither direction), and a `list<T>` **as an argument** (`list<u8>` crosses, as a
byte string). Anything unsupported is a compile error naming the WIT file and
line.

| WIT type | Lisp value | Preview 1 | `--component` |
| --- | --- | --- | --- |
| `s8` `s16` `s32` `u8` `u16` `u32` | an integer | `:int` | yes |
| `s64` `u64` | an integer | no | yes |
| `f32` `f64` | a float | `:float` | yes |
| `bool` | `t` / `nil` | `:bool` | yes |
| `string` | a string | `:string` | yes |
| `char` | a character | no | yes |
| `list<u8>` | a string of raw bytes (one per char) | `:string` | yes |
| `list<T>` | a proper list | no | result only |
| `tuple<...>` | a proper list, positional | no | yes |
| `option<T>` | the value, or `nil` | no | yes |
| `result<T, E>` | returned: the ok value, the error arm signals `rontolisp:wit-error`; passed: the `(:ok . V)` / `(:error . E)` envelope | no | yes |
| `record` | a keyword plist | no | yes |
| `enum` | a keyword | no | yes |
| `variant` | a keyword, or `(keyword . payload)` | no | yes |
| `flags` | a list of keywords | no | no |
| `resource`, `borrow<R>`, `own<R>` | an opaque integer handle | `:int` | yes |
| `stream`, `future` | — | no | no |

`stream` and `future` have no rontolisp value on any backend (they need
language-level async), so they are rejected everywhere.

### Rich values as arguments

An argument takes **exactly the shape the same type takes as a return value**, so
a value one call hands you can be passed straight into the next:

```console
;;; a variant: the case keyword, or (keyword . payload) when the case carries one
(http:outgoing-request-set-method req :post)
(http:outgoing-request-set-method req '(:other . "PATCH"))
(http:outgoing-request-method req)                 ; => (:other . "PATCH")

;;; an enum: a keyword
(sock:tcp-socket-create :ipv4)

;;; a record is a keyword plist, a tuple a positional list -- here both, inside a
;;; variant case's payload
(sock:tcp-socket-bind s '(:ipv4 :port 0 :address (127 0 0 1)))

;;; a result ARGUMENT is the (:ok . V) / (:error . E) envelope -- the same shape a
;;; result RESULT has before the ok arm is unwrapped. A payload-less arm may also
;;; be written as the bare keyword.
(cli:exit '(:error))
(cli:exit :ok)
```

A keyword that names no case of the variant is a **type error**: on the WASM
backends it traps, exactly as every other type error does there (`(+ 1 "a")`
included); on the interpreter and the JVM it simply reaches the provider, which
decides what to make of it.

## Limitations

- `--no-gc` rejects the directive with a clear error: its contract is a plain MVP
  module that imports nothing at all.
- On the Preview 1 boundary only the flat set above crosses; a `record`,
  `option`, `result` or `s64` is a compile error naming the WIT file and line —
  `wit/store.wit:12: 'bucket-get': the WIT type of the result does not cross the Preview 1 WASM import boundary, which carries the flat set (...)` —
  even though `--component`, the interpreter and the JVM all bind it. The
  `wasi:keyvalue` example above is therefore a component (or an interpreter/JVM)
  program, not a Preview 1 one: its `result` arms keep it off that boundary.
- Under `--component` a **`list<T>` argument** (other than `list<u8>`) is a
  compile error, though the same type crosses as a *result*: an argument is
  flattened, and a list would have to be written into linear memory as a canonical
  array instead. `flags` does not cross in either direction yet.
- Under `--component` the interface must not be one the component **already
  imports for its own WASI surface** (which grows with what the program uses:
  `rontolisp:fetch` adds `wasi:http` and `wasi:io`, the `rontolisp:tcp-*`
  built-ins add `wasi:sockets/types`). A component cannot import the same
  interface twice, so this is a compile error naming it — drive the interface
  through the WIT binding *instead of* the built-in, not alongside it.
- A component cannot combine `wit-import` with
  [`rontolisp:http-handler`](rontolisp-http-handler.md) (serve mode): a served
  component's imports are the fixed `wasi:http` surface.
- The directive must appear at **top level, before the code that calls the
  interface** (the opposite of [`wit-export`](rontolisp-wit-export.md), which
  must come last): it is what defines the package and the bindings. A directive
  below its call sites is a `No such package: kv` error on every backend.
- Prefer `:package`. Without it the bindings land in the current package, where a
  WIT label that collides with a `cl` name (`open`, `close`, `delete`, ...)
  resolves inconsistently across backends — the interpreter takes the binding,
  the JVM backend takes the `cl` function.
- Only an **interface** can be bound. A world's `import` items are not read (a
  component's WASI imports come from the fixed adapter surface it is built on).
- A resource handle is opaque and is never released by rontolisp — there is no
  `drop`; the interface's own release function, if it declares one, is bound like
  any other member.
- On the WASM backend the 7-parameter arity limit applies to a binding like any
  other function, counting a method's leading `self`.
- Instantiating the compiled Preview 1 module requires the host to provide every
  import that survives tree shaking: `wasmtime run` needs a
  `--preload <module>=<file>.wasm`, and a JavaScript host passes an import
  object.
