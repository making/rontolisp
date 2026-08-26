# wit/keyvalue -- one WIT interface, three stores behind it

[`wit/world/`](../world) implements a WIT world: the functions a program
*exports*. This is the other half -- the functions a program **calls**.
[`page-hits.lisp`](page-hits.lisp) is a page-view counter written against
[`wasi:keyvalue/store`](wit/keyvalue.wit) that never says where the pairs live:

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
wrote from the `.wit`. What they *reach* is decided elsewhere, by whoever binds a
**provider**:

| File | What it is |
| --- | --- |
| [`wit/keyvalue.wit`](wit/keyvalue.wit) | The interface: the real [wasi:keyvalue](https://github.com/WebAssembly/wasi-keyvalue) `store`, vendored verbatim |
| [`page-hits.lisp`](page-hits.lisp) | The program. It knows the WIT and nothing else |
| [`memory-store.lisp`](memory-store.lisp) | An implementation: a portable Lisp hash-table store, ~50 lines, ending in one `rontolisp:wit-provide` |
| [`java-store.lisp`](java-store.lisp) | The **same** interface over a real `java.util.LinkedHashMap`. Bound after the first, so it replaces it |
| [`page-hits-server.lisp`](page-hits-server.lisp) | The same counter as an HTTP server ([§6](#6-serve-it)) |

Run it on the interpreter and the Lisp store answers; compile to the JVM and the
Java store answers; compile to a **WASI component** and wasmtime's own
`wasi:keyvalue` implementation answers -- a host that has never heard of this
program. **The output is identical all three ways**, and that identity is the
point.

Commands below say `rontolisp`, the native binary; with the executable JAR it is
`java -jar ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar`. Run them from this
directory.

## 1. The interface

[`wit/keyvalue.wit`](wit/keyvalue.wit) is upstream's `store`, name for name --
not a subset, which is what lets the component below talk to a real host with no
adapter in between. It declares a `variant error`, a `record key-response`,
`open: func(identifier: string) -> result<bucket, error>` and a
`resource bucket` with `get`/`set`/`delete`/`exists`/`list-keys`.

`:package kv` puts the bindings in a package of their own. The names are
mechanical:

| WIT | Lisp |
| --- | --- |
| `open: func(identifier: string)` | `(kv:open identifier)` |
| `bucket.get`, a **resource method** | `(kv:bucket-get b key)` -- the handle comes first |
| a resource **constructor** | `bucket-new` |
| a resource **static** func `f` | `bucket-f` |

A method is prefixed with its resource so the Lisp-2 function namespace stays
unambiguous when two resources declare the same method; the receiver, implicit
in WIT, becomes the leading argument. Parameter names come across verbatim.

The types are rontolisp's settled WIT mapping:

| WIT | Lisp value |
| --- | --- |
| `result<bucket, error>` | the bucket handle -- and the **error arm signals** `rontolisp:wit-error` |
| `option<list<u8>>` | the value string, or `nil` when the key is absent |
| `list<u8>` | a string (bytes, one per character) |
| `option<u64>` | a number, or `nil` |
| `record key-response` | a keyword plist: `(:keys ("/index" ...) :cursor nil)` |
| a `resource` handle | an opaque integer -- pass it back, never interpret it |

There is no `unwrap` step and no error-code plumbing: the ok arm *is* the return
value, and a failure is a condition.

```lisp
(handler-case (kv:open "not-a-store-anyone-has")
  (rontolisp:wit-error (e)
    (format t "bad store: ~a~%" (rontolisp:wit-error-payload e))))
```

## 2. The store is user code -- rontolisp ships none

rontolisp knows how to **bind** a provider to a WIT interface. It does not know
what `wasi:keyvalue` is and ships no store for it, nor for any other interface:
a new host interface should cost a `.wit` file, not a change to the language.

A provider is an **ordinary Lisp callable**. It takes the bound function's Lisp
member name -- `"open"`, `"bucket-get"`, ... -- and then that function's
arguments, a resource method's handle included. That is the whole contract:

```lisp
;;; memory-store.lisp
(defun memory-store (member &rest args)
  (cond ((string= member "open")       (kv-open (nth 0 args)))
        ((string= member "bucket-get") (gethash (nth 1 args) (kv-bucket (nth 0 args))))
        ...))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'memory-store)
```

Nothing is wrapped on the way out: the ok arm of a `result` **is** the return
value, and the error arm is the provider signaling `rontolisp:wit-error` with
the WIT variant as its payload. `page-hits.lisp` pulls the file in with
`(require :kv-memory "memory-store.lisp")`.

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
/nope:             NIL
bad store:         NO-SUCH-STORE
seeded:            ("/a" "/b")
```

A `wasi:keyvalue` program normally needs a host before it can run at all. Here
it is a script.

## 4. On the JVM: swap the store, not the program

`java-store.lisp` is the same dispatch function over a real
`java.util.LinkedHashMap`, reached through
[`java:` interop](../../../doc/en/guides/java-interop.md):

```lisp
(defun java-store (member &rest args)
  (cond ((string= member "bucket-get")
         (java:call (java-bucket (nth 0 args)) "get" (nth 1 args)))
        ...))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'java-store)
```

`wit-provide` **replaces** whatever was bound, so requiring this file after
`memory-store.lisp` is the whole swap. `java:` interop is a JVM-and-interpreter
thing, which is why `page-hits.lisp` guards that one line with
`#+rontolisp-jvm` -- the only line in the program about a backend rather than
about page hits.

```console
$ rontolisp page-hits.lisp -o PageHits.class && java -cp . PageHits
;; [java store] open "" -> handle 500
;; [java store] set /index = 1
...
```

The Java store traces every call it serves. Strip those lines and the two runs
are the same report, character for character.

## 5. As a component -- against a store nobody here wrote

`--component` makes the program a WASI component that **imports
`wasi:keyvalue/store`**. There is no provider in it at all: the calls go out
through the canonical ABI, and whatever the host plugs in answers.

```console
$ rontolisp page-hits.lisp -o page-hits.wasm --component
$ wasmtime run -S keyvalue=y page-hits.wasm
```

Character for character the interpreter's report -- from a store written in
Rust, inside the runtime, by people who have never seen this program. The `.wit`
is the only thing the two ends share. Nothing in `page-hits.lisp` mentions a
component and neither store file is consulted: on the WASM backends a
`wit-provide` is *inert*, so one source compiles everywhere.

What crossed that boundary is the whole type mapping at once: a `resource`
handle, a `string`, a `list<u8>`, an `option`, a `bool`, a `record` holding a
`list<string>` and an `option<u64>`, and a `result` whose **error arm arrived as
a condition** and was caught by `handler-case`.

`--emit-wit` writes the component's real type next to it, with the imported
interface pruned to the functions the program actually calls. That is also how
you compose: a component importing `wasi:keyvalue/store` plugs into any
component exporting it, in any language, with
[`wac`](https://github.com/bytecodealliance/wac) -- the host need not be a
runtime built-in.

Each WIT function lowers into an **ordinary `defun`**, not a special call form,
so `#'kv:bucket-set` is a function value that `funcall`, `apply` and `mapcar`
take. That holds on the component too, where those calls are canonical-ABI
lowerings.

## 6. Serve it

[`page-hits-server.lisp`](page-hits-server.lisp) is the same counter behind
`rontolisp:http-handler`. The two halves need each other: a served component's
globals are **not** state, because a `wasi:http` host instantiates it afresh for
every request, so a hash-table counter reads back empty every time. Through a
store it works -- the store is the one thing that outlives the instance.

```lisp
(defun handle (env)
  (let* ((page (getf env :path-info))
         (bucket (kv:open *store*))
         (hits (record-hit bucket page)))
    (list 200 '(:content-type "text/plain")
          (list (format nil "~a -> ~a hit~:[s~;~]~%~%hits per page:~%~a"
                        page hits (= hits 1) (report bucket))))))

(rontolisp:http-handler 'handle 8080)
```

On the interpreter and the JVM the process outlives the requests, so the Lisp
store keeps the counts. As a component it exports `wasi:http/incoming-handler`
and imports `wasi:keyvalue/store`, and whose store answers is the host's
business:

```console
$ rontolisp page-hits-server.lisp -o server.wasm --component
$ wasmtime serve -S keyvalue=y server.wasm
```

wasmtime's key-value host is an **in-memory store it rebuilds per instance**, so
under `wasmtime serve` the tally starts over every request. The calls really do
cross into it -- seed one with `-S keyvalue-in-memory-data=/index=41` and the
first request answers 42 -- but the counts do not accumulate, and nothing in the
component can change that.

A host that links an **out-of-process** provider keeps them. wasmCloud does, and
[`.wash/config.yaml`](.wash/config.yaml) is the whole configuration: `wash dev`
compiles this directory, deploys the component and links it, and the counts
accumulate on `:8000`. That the component cannot tell the two hosts apart is the
point of the boundary.

## Limitations

Two WASM backends this example does not reach, both worth knowing before writing
a `.wit` of your own:

1. **Preview 1 (`-o out.wasm`) carries the flat set only.** A Preview 1 import
   is a bare core-WASM function with no component type to describe a richer
   shape, so only the integer scalars up to 32 bits, the float scalars, `bool`,
   `string`, `list<u8>` and resource handles cross. Every function of
   `wasi:keyvalue` returns a `result`, so this WIT is a compile error there,
   naming the line that cannot cross.

   `wit-import` *does* work on Preview 1 for an interface written within that
   set -- a WebGL binding, say, which is all handles and scalars. It lowers into
   one `rontolisp:wasm-import` per WIT function, byte-identically to the
   hand-written import block, and `--optimize` still shakes out what the program
   never calls. (A WIT label becomes a `createShader`-style field by default --
   the JavaScript convention, and what `jco` produces; `:field-style :kebab`
   keeps the label verbatim.)
2. **`--no-gc` rejects `wit-import` outright**: its contract is a plain MVP
   module that imports nothing at all.

On the interpreter and the JVM there is no type restriction: the boundary is an
ordinary Lisp call, so every representation in the mapping crosses (records as
plists, variants as tagged lists, enums as keywords). Only `stream` and `future`
are refused, having no rontolisp value on any backend yet.

Full reference:
[`rontolisp:wit-import`](../../../doc/en/reference/functions/rontolisp-wit-import.md),
[`rontolisp:wit-provide`](../../../doc/en/reference/functions/rontolisp-wit-provide.md).
