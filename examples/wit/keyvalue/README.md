# wit/keyvalue -- one WIT interface, three stores behind it

[`wit/world/`](../world) implements a WIT world: it is about the functions a
program *exports*. This one is the other half -- the functions a program
**calls**. [`page-hits.lisp`](page-hits.lisp) is a page-view counter written
against [`wasi:keyvalue/store`](wit/keyvalue.wit), and it never says where the
key-value pairs live:

```lisp
(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0-draft"
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
| [`wit/keyvalue.wit`](wit/keyvalue.wit) | The interface: the real [wasi:keyvalue](https://github.com/WebAssembly/wasi-keyvalue) `store`, vendored verbatim |
| [`page-hits.lisp`](page-hits.lisp) | The program. It knows the WIT and nothing else |
| [`memory-store.lisp`](memory-store.lisp) | An implementation: a portable Lisp hash-table store, ~50 lines, ending in one `rontolisp:wit-provide` |
| [`java-store.lisp`](java-store.lisp) | The **same** interface over a real `java.util.LinkedHashMap`, through [`java:` interop](../../doc/en/guides/java-interop.md). Bound after the first, so it replaces it |
| [`page-hits-server.lisp`](page-hits-server.lisp) | The same counter as an **HTTP server** ([§7](#7-serve-it----an-http-server-whose-state-is-somebody-elses)): a served component's state has to live in a store, because its globals do not survive a request |

Run the program on the interpreter and the Lisp store answers; compile it to the
JVM and the Java store answers; compile it to a **WASI component** and
**wasmtime's own `wasi:keyvalue` implementation** answers -- a host that has never
heard of this program. **The program's own output is identical all three ways**,
and that identity is the whole point of the example.

The commands below say `rontolisp`, the native binary
(`./mvnw -Pnative clean package -DskipTests`). With the executable JAR
(`./mvnw clean package`) instead, `rontolisp` is
`java -jar ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar`. Run them from this
directory.

## 1. The interface

[`wit/keyvalue.wit`](wit/keyvalue.wit) is upstream's `store`, name for name. Not
a subset, not a simplification -- the real thing, which is what lets the component
below talk to a real host with no adapter in between.

```wit
package wasi:keyvalue@0.2.0-draft;

interface store {
  variant error {
    no-such-store,
    access-denied,
    other(string),
  }

  record key-response {
    keys: list<string>,
    cursor: option<u64>,
  }

  open: func(identifier: string) -> result<bucket, error>;

  resource bucket {
    get: func(key: string) -> result<option<list<u8>>, error>;
    set: func(key: string, value: list<u8>) -> result<_, error>;
    delete: func(key: string) -> result<_, error>;
    exists: func(key: string) -> result<bool, error>;
    list-keys: func(cursor: option<u64>) -> result<key-response, error>;
  }
}
```

`:package kv` puts the bindings in a package of their own, so the call sites read
`kv:open`, `kv:bucket-get`, ... The names are mechanical:

| WIT | Lisp |
| --- | --- |
| `open: func(identifier: string)` | `(kv:open identifier)` |
| `bucket.get`, a **resource method** | `(kv:bucket-get b key)` -- the handle comes first |
| `bucket.list-keys` | `(kv:bucket-list-keys b cursor)` |
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
| `option<u64>` | a number, or `nil` -- so a cursor-less `list-keys` passes `nil` |
| `record key-response` | a keyword plist: `(:keys ("/index" ...) :cursor nil)` |
| a `resource` handle | an opaque integer -- pass it back, never interpret it |

There is no `unwrap` step and no error-code plumbing: the ok arm *is* the return
value, and a failure is a condition, so a store's errors are caught the way every
other rontolisp error is.

```lisp
(handler-case
    (kv:open "not-a-store-anyone-has")
  (rontolisp:wit-error (e)
    (format t "bad store:         ~a~%" (rontolisp:wit-error-payload e))))
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

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'memory-store)
```

Nothing is wrapped on the way out: the ok arm of a `result` **is** the return
value, and the error arm is the provider signaling `rontolisp:wit-error` with the
WIT variant as its payload. The whole file is ~50 lines of portable Lisp, and
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
bad store:         no-such-store
seeded:            ("/a" "/b")
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

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'java-store)
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
;; [java store] open "" -> handle 500
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
bad store:         no-such-store
;; [java store] set /a = seeded
;; [java store] set /b = seeded
seeded:            ("/a" "/b")
```

Strip the trace lines and the two runs are the same report, character for
character.

## 5. Run it as a component -- against a store nobody here wrote

Compile it with `--component` and the program becomes a WASI component that
**imports `wasi:keyvalue/store`**. There is no provider in it at all: the calls go
out through the component model's canonical ABI, and whatever the host plugs in
answers them. wasmtime ships an implementation, so this is the whole ceremony:

```console
$ rontolisp page-hits.lisp -o page-hits.wasm --component
$ wasmtime run -W gc=y -W exceptions=y \
      -S keyvalue=y page-hits.wasm

hits per page:
  /docs = 1
  /index = 3
  /pricing = 2

/docs exists?      yes
/docs exists now?  no
keys:              ("/index" "/pricing")
/nope:             nil
bad store:         no-such-store
seeded:            ("/a" "/b")
```

Character for character the interpreter's report -- from a store written in Rust,
inside the runtime, by people who have never seen this program. The `.wit` is the
only thing the two ends share.

Nothing in `page-hits.lisp` mentions a component, and neither store file is
consulted: on the WASM backends a `rontolisp:wit-provide` is *inert* (the host is
the provider), so the same source compiles and runs everywhere.

What actually crossed that boundary is worth spelling out, because it is the
whole of the type mapping at once: a `resource` handle, a `string`, a
`list<u8>`, an `option`, a `bool`, a `record` holding a `list<string>` and an
`option<u64>`, and a `result` whose **error arm arrived as a condition** and was
caught by `handler-case`.

`--emit-wit` writes the component's real type next to it, and the imported
interface is in there -- pruned to the functions the program actually calls:

```console
$ rontolisp page-hits.lisp -o page-hits.wasm --component --emit-wit
$ grep -A 2 keyvalue page-hits.wit
  import wasi:keyvalue/store@0.2.0-draft;
```

That is also how you compose: a component that *imports* `wasi:keyvalue/store` can
be plugged into any component that *exports* it, in any language, with
[`wac`](https://github.com/bytecodealliance/wac) -- the host does not have to be a
runtime built-in.

## 6. Why the bindings are just `defun`s

Each WIT function lowers into an **ordinary `defun`**, not into some special call
form. So everything that works on a function works on them, with no extra wiring
-- `#'kv:bucket-set` is a function value, `funcall` and `apply` take it, `mapcar`
maps it, `eval` finds it:

```lisp
(let ((bucket (kv:open *store*))
      (set-key #'kv:bucket-set))
  (mapcar (lambda (key) (funcall set-key bucket key "seeded"))
          '("/a" "/b"))
  ...)
```

Nothing about the boundary leaks into the call sites: they are Lisp calls, and
the WIT is the only thing that says what is on the other end. That holds on the
component too, where those calls are canonical-ABI lowerings.

## 7. Serve it -- an HTTP server whose state is somebody else's

[`page-hits-server.lisp`](page-hits-server.lisp) is the same counter behind an
HTTP server: `rontolisp:http-handler` for the requests,
`rontolisp:wit-import` for the store. Every request records a hit for its own
path and answers the tally.

The two halves need each other. A served component's globals are **not** state: a
`wasi:http` host instantiates it afresh for every request, so a hash table reads
back empty every time and a page-hit counter simply cannot be written that way.
Through a store it can -- and the store is the one thing that outlives the
instance.

```lisp
(defun handle (request)
  (let* ((page (getf request :path))
         (bucket (kv:open *store*))
         (hits (record-hit bucket page)))
    (list :status 200
          :headers (list (cons "content-type" "text/plain"))
          :body (format nil "~a -> ~a hit~:[s~;~]~%~%hits per page:~%~a"
                        page hits (= hits 1) (report bucket)))))

(rontolisp:http-handler 'handle 8080)
```

On the interpreter (and, with `-o Server.class`, on the JVM) the process outlives
the requests, so the Lisp store beside the program keeps the counts:

```console
$ rontolisp page-hits-server.lisp &
$ curl http://127.0.0.1:8080/index
/index -> 1 hit

hits per page:
/index = 1
$ curl http://127.0.0.1:8080/index
/index -> 2 hits

hits per page:
/index = 2
```

As a **component** it exports `wasi:http/incoming-handler` and imports
`wasi:keyvalue/store` -- and now *whose* store answers, and whether it survives,
is the host's business:

```console
$ rontolisp page-hits-server.lisp -o server.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
```

wasmtime's key-value host is an **in-memory store it rebuilds per instance** --
so under `wasmtime serve` (a fresh instance per request) the tally starts over
every time. The calls really do cross into it (seed one with
`-S keyvalue-in-memory-data=/index=41` and the first request answers 42), but the
counts do not accumulate, and nothing in the component can change that.

A host that links an **out-of-process** provider keeps them. wasmCloud does, and
[`.wash/config.yaml`](.wash/config.yaml) is the whole configuration -- `wash dev`
compiles this directory with rontolisp, deploys the component and links it:

```console
$ wash dev                      # serves on :8000
$ curl http://127.0.0.1:8000/index
/index -> 1 hit

hits per page:
/index = 1
$ curl http://127.0.0.1:8000/index
/index -> 2 hits

hits per page:
/index = 2
```

Same component, same source, a store that outlives the instance. That the
component cannot tell the two hosts apart is the point of the boundary.

## Limitations

**The two WASM backends this example does not reach**, and why -- both worth
knowing before writing a `.wit` of your own:

1. **Preview 1 WASM (`-o out.wasm`) carries the flat set only.** A Preview 1
   import is a bare core-WASM function: there is no component type with which to
   describe a richer shape to the host, so only the integer scalars up to 32
   bits, the float scalars, `bool`, `string`, `list<u8>` and resource handles
   cross it. Every function of `wasi:keyvalue` returns a `result`, so this WIT is
   a compile error there, naming the line that cannot cross:

   ```console
   $ rontolisp page-hits.lisp -o page-hits.wasm
   wit/keyvalue.wit:36: 'open': the WIT type of the result does not cross the Preview 1
   WASM import boundary, which carries the flat set (the integer scalars up to 32 bits,
   the float scalars, bool, string, list<u8> and resource handles). Its rontolisp
   representation is settled (RESULT): compile with --component, where the canonical ABI
   marshals it, or run on the interpreter or the JVM backend, which bind it through a
   provider
   ```

   `wit-import` *does* work on Preview 1 for an interface written within that set
   -- a WebGL binding, say, which is all handles and scalars. It lowers into one
   `rontolisp:wasm-import` per WIT function, byte-identically to the hand-written
   import block, and `--optimize` still shakes out the functions the program never
   calls. (The import *field* a WIT label becomes is `createShader` for
   `create-shader` by default -- the JavaScript convention, and what `jco` produces
   from a component; `:field-style :kebab` keeps the label verbatim instead.)

2. **`--no-gc` rejects `wit-import` outright**: its contract is a plain MVP module
   that imports nothing at all.

   ```console
   $ rontolisp page-hits.lisp -o page-hits.wasm --no-gc
   rontolisp:wit-import is not supported with --no-gc: the scalar backend emits a plain
   MVP module with no imports
   ```

On the interpreter and the JVM there is no type restriction at all: the boundary
is an ordinary Lisp call, so every representation in the mapping crosses (records
as plists, variants as tagged lists, enums as keywords, ...). Only `stream` and
`future` are refused, having no rontolisp value on any backend yet.

Full reference:
[`rontolisp:wit-import`](../../doc/en/reference/functions/rontolisp-wit-import.md),
[`rontolisp:wit-provide`](../../doc/en/reference/functions/rontolisp-wit-provide.md).
