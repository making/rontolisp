# wit/keyvalue -- one WIT interface, two stores behind it

[`wit/world/`](../world) implements a WIT world: it is about the functions a
program *exports*. This one is the other half -- the functions a program
**calls**. [`page-hits.lisp`](page-hits.lisp) is a page-view counter written
against [`wasi:keyvalue/store`](wit/keyvalue.wit), and it never says where the
key-value pairs live:

```lisp
(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0"
                      :package kv)

(defun record-hit (bucket page)
  (let ((seen (kv:bucket-get bucket page)))
    (kv:bucket-set bucket page
                   (princ-to-string (+ 1 (if seen (parse-integer seen) 0))))))
```

`kv:bucket-get` and `kv:bucket-set` are ordinary Lisp functions the compiler
wrote from the `.wit`. What they *reach* is decided somewhere else entirely --
by whoever binds a **provider** for the interface. Four files, and only one of
them is the program:

| File | What it is |
| --- | --- |
| [`wit/keyvalue.wit`](wit/keyvalue.wit) | The interface. An honest subset of the real [wasi:keyvalue 0.2](https://github.com/WebAssembly/wasi-keyvalue) `store` |
| [`page-hits.lisp`](page-hits.lisp) | The program. It knows the WIT and nothing else |
| [`memory-store.lisp`](memory-store.lisp) | An implementation: a portable Lisp hash-table store, ~40 lines, ending in one `rontolisp:wit-provide` |
| [`java-store.lisp`](java-store.lisp) | The **same** interface over a real `java.util.LinkedHashMap`, through [`java:` interop](../../doc/en/guides/java-interop.md). Bound after the first, so it replaces it |

Run the program on the interpreter and the memory store answers; compile it to
the JVM and the Java store answers. **The program's own output is identical
either way** -- that identity is the whole point of the example.

The commands below say `rontolisp`, the native binary
(`./mvnw -Pnative clean package -DskipTests`). With the executable JAR
(`./mvnw clean package`) instead, `rontolisp` is
`java -jar ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar`. Run them from this
directory.

## 1. The interface

[`wit/keyvalue.wit`](wit/keyvalue.wit) is upstream's `store`, name for name,
minus two things: `list-keys` answers every key instead of paging with a cursor,
and the sibling `atomics` / `batch` interfaces are not here.

```wit
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
    list-keys: func() -> result<list<string>, error>;
  }

  open: func(identifier: string) -> result<bucket, error>;
}
```

`:package kv` puts the bindings in a package of their own, so the call sites read
`kv:open`, `kv:bucket-get`, ... The names are mechanical:

| WIT | Lisp |
| --- | --- |
| `open: func(identifier: string)` | `(kv:open identifier)` |
| `bucket.get`, a **resource method** | `(kv:bucket-get b key)` -- the handle comes first |
| `bucket.list-keys` | `(kv:bucket-list-keys b)` |
| a resource **constructor** | `bucket-new` |
| a resource **static** func `f` | `bucket-f` |

A resource method is prefixed with its resource (`bucket-get`, not `get`) so the
flat, Lisp-2 function namespace stays unambiguous when two resources declare the
same method; the receiver, which WIT leaves implicit, becomes the leading `self`
argument. Parameter names come across verbatim, so the lambda list is the WIT's.

And the types are rontolisp's settled WIT mapping:

| WIT | Lisp value |
| --- | --- |
| `result<bucket, error>` | the bucket handle -- and the **error arm signals** `rontolisp:wit-error` |
| `option<list<u8>>` | the value string, or `nil` when the key is absent |
| `list<u8>` | a string (bytes, one per character) |
| `list<string>` | a list of strings |
| a `resource` handle | an opaque integer -- pass it back, never interpret it |

There is no `unwrap` step and no error-code plumbing: the ok arm *is* the return
value, and a failure is a condition, so a store's errors are caught the way every
other rontolisp error is.

```lisp
(handler-case
    (kv:bucket-get 42 "/index")            ; a handle no store ever handed out
  (rontolisp:wit-error (e)
    (format t "bad handle:        ~a~%" (rontolisp:wit-error-payload e))))
```

## 2. The store is user code -- rontolisp ships none

rontolisp knows how to **bind** a provider to a WIT interface. It does not know
what `wasi:keyvalue` is, and it ships no store for it -- nor for any other
interface. That is deliberate: a new host interface should cost a `.wit` file,
not a change to the language.

So a store is something you write, and a provider is an **ordinary Lisp
callable**. It takes the bound function's Lisp member name -- a string, `"open"`
/ `"bucket-get"` / ... -- and then that function's arguments, a resource method's
handle included. That is the entire contract:

```lisp
;;; memory-store.lisp
(defun memory-store (member &rest args)
  (cond ((string= member "open")
         (kv-open (nth 0 args)))
        ((string= member "bucket-get")
         (gethash (nth 1 args) (kv-bucket (nth 0 args))))
        ...))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'memory-store)
```

Nothing is wrapped on the way out: the ok arm of a `result` **is** the return
value, and the error arm is the provider signaling `rontolisp:wit-error` with the
WIT variant as its payload (`memory-store.lisp` does exactly that for a handle it
never handed out). The whole file is ~40 lines of portable Lisp, and
[`page-hits.lisp`](page-hits.lisp) pulls it in with one line:

```lisp
(require :kv-memory "memory-store.lisp")
```

## 3. Run it on the interpreter

```console
$ rontolisp page-hits.lisp

hits per page:
  /docs = 1
  /index = 3
  /pricing = 2

/docs exists?      yes
/docs exists now?  no
keys:              ("/index" "/pricing")
/nope:             nil
bad handle:        :no-such-store
second bucket:     ("a" "b")
```

A `wasi:keyvalue` program normally needs a host before it can be run at all. Here
it is a script.

## 4. Run it on the JVM: swap the store, not the program

Now bind a different implementation of the same interface. `java-store.lisp` is
the same dispatch function over a real `java.util.LinkedHashMap`, reached through
[`java:` interop](../../doc/en/guides/java-interop.md):

```lisp
;;; java-store.lisp
(defun java-store (member &rest args)
  (cond ((string= member "open")
         ...
         (java:call *java-buckets* "put" handle (java:new "java.util.LinkedHashMap"))
         ...)
        ((string= member "bucket-get")
         (java:call (java-bucket (nth 0 args)) "get" (nth 1 args)))
        ...))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'java-store)
```

`rontolisp:wit-provide` **replaces** whatever was bound for the interface, so
requiring this file after `memory-store.lisp` is the whole swap. `java:` interop
is a JVM-and-interpreter thing, which is why `page-hits.lisp` guards that one
line -- and it is the *only* line in the program that is about a backend rather
than about page hits:

```lisp
#+rontolisp-jvm
(require :kv-java "java-store.lisp")
```

The Java store prints every call it serves, so the `;; [java store]` lines are the
proof that the very same `(kv:bucket-set ...)` now lands somewhere else:

```console
$ rontolisp page-hits.lisp -o PageHits.class && java -cp . PageHits
;; [java store] open "page-hits" -> handle 500
;; [java store] set /index = 1
;; [java store] set /pricing = 1
;; [java store] set /index = 2
;; [java store] set /docs = 1
;; [java store] set /index = 3
;; [java store] set /pricing = 2

hits per page:
  /docs = 1
  /index = 3
  /pricing = 2

/docs exists?      yes
;; [java store] delete /docs
/docs exists now?  no
keys:              ("/index" "/pricing")
/nope:             nil
bad handle:        :no-such-store
;; [java store] open "second-bucket" -> handle 501
;; [java store] set a = seeded
;; [java store] set b = seeded
second bucket:     ("a" "b")
```

Strip the trace lines and the two runs are the same report, character for
character. That is the payoff: develop against a fake, deploy against the real
thing, and never touch the program in between. A production store would swap
`java.util.LinkedHashMap` for a Redis client or a JDBC connection -- nothing else
in `java-store.lisp` would change, and nothing at all in `page-hits.lisp` would.

(On the WASM backends a `rontolisp:wit-provide` form is *dropped* rather than
rejected -- the host is the provider there -- so a program written this way still
compiles everywhere it can compile at all.)

## 5. Why the bindings are just `defun`s

Each WIT function lowers into an **ordinary `defun`**, not into some special call
form. So everything that works on a function works on them, with no extra wiring
-- `#'kv:bucket-set` is a function value, `funcall` and `apply` take it, `mapcar`
maps it, `eval` finds it:

```lisp
(let ((bucket (kv:open "second-bucket"))
      (set-key #'kv:bucket-set))
  (mapcar (lambda (key) (funcall set-key bucket key "seeded"))
          '("a" "b"))
  (format t "second bucket:     ~s~%" (sorted-keys bucket)))
```

Nothing about the boundary leaks into the call sites: they are Lisp calls, and
the WIT is the only thing that says what is on the other end.

## Limitations

**This example's backends are the interpreter and the JVM.** Two separate
reasons, and both are worth knowing before writing a `.wit` of your own:

1. **`--component` and `--no-gc` reject `wit-import` outright.**

   ```console
   $ rontolisp page-hits.lisp -o page-hits.wasm --component
   rontolisp:wit-import is not supported with --component yet: a component's imports
   need the canonical-ABI lower, which is not implemented. It works on the interpreter,
   the JVM backend and Preview 1 WASM (a plain -o out.wasm).

   $ rontolisp page-hits.lisp -o page-hits.wasm --no-gc
   rontolisp:wit-import is not supported with --no-gc: the scalar backend emits a plain
   MVP module with no imports
   ```

   The component one is the interesting gap: importing an interface into a
   component needs the canonical ABI's `lower`, which the export side
   ([`wit-export`](../world)) already has and the import side does not yet.

2. **A `result`/`option`-bearing interface does not cross the Preview 1 WASM
   import boundary either** -- so *this* WIT, unchanged, is a compile error there,
   naming the line that cannot cross:

   ```console
   $ rontolisp page-hits.lisp -o page-hits.wasm
   wit/keyvalue.wit:32: 'bucket-get': the WIT type of the result does not cross the
   Preview 1 WASM import boundary (supported: the integer scalars up to 32 bits, the
   float scalars, bool, string, list<u8> and resource handles). Its rontolisp
   representation is settled (RESULT), and the interpreter and the JVM backend bind
   it today -- only the WASM import boundary cannot marshal it yet
   ```

   `wit-import` *does* work on Preview 1 WASM (`-o out.wasm`): it lowers into one
   `rontolisp:wasm-import` per WIT function, byte-identically to the hand-written
   import block, and `--optimize` still shakes out the functions the program never
   calls. (The import *field* a WIT label becomes is `createShader` for
   `create-shader` by default -- the JavaScript convention, and what `jco` produces
   from a component; `:field-style :kebab` keeps the label verbatim instead.) But a
   Preview 1 import is a raw core-WASM function, so only the **flat** set crosses
   it: the integer scalars up to 32 bits, the float scalars, `bool`, `string`,
   `list<u8>` and resource handles. An interface written within that set -- a
   WebGL binding, say, which is all handles and scalars -- can bind on all three
   of interpreter, JVM and Preview 1 WASM from one `.wit`. `wasi:keyvalue`
   cannot, because every one of its functions returns a `result`.

On the interpreter and the JVM there is no such restriction: the boundary is an
ordinary Lisp call, so every representation in the mapping crosses (records as
plists, variants as tagged lists, enums as keywords, ...). Only `stream` and
`future` are refused, having no rontolisp value on any backend yet.

Full reference:
[`rontolisp:wit-import`](../../doc/en/reference/functions/rontolisp-wit-import.md),
[`rontolisp:wit-provide`](../../doc/en/reference/functions/rontolisp-wit-provide.md).
