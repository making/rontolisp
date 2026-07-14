# rontolisp:wit-provide

`(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)`

Binds the **implementation** of a WIT interface brought in with
[`rontolisp:wit-import`](rontolisp-wit-import.md). On the interpreter and the JVM
backend there is no WASM host to call, so every imported function dispatches
through the interface's *provider*; this is what supplies one. It returns the
interface id and **replaces** any provider already bound for that interface.

rontolisp ships **no provider for any interface**: it knows the provider
mechanism, not what `wasi:keyvalue` — or any other interface — *is*. An
implementation of a WIT interface is ordinary Lisp code, and this is how you
hand it in.

A provider is an ordinary Lisp callable taking the bound function's **Lisp member
name** (a string — `"open"`, `"bucket-get"`, the name the binding is spelled
with, not the raw WIT label) followed by that function's arguments. Here is a
complete one — a store in a hash table, which is all a store has to be:

```lisp
(defvar *rows* (make-hash-table :test #'equal))

(defun my-store (member &rest args)     ; ("bucket-set" bucket "visits" "41")
  (cond ((string= member "open") 1)     ; the bucket handle: any integer
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) *rows*) (nth 2 args))
         nil)
        ((string= member "bucket-get") (gethash (nth 1 args) *rows*))
        ((string= member "bucket-exists") (if (gethash (nth 1 args) *rows*) t nil))
        ((string= member "bucket-drop") nil)  ; the handle is gone; the rows stay
        (t (error 'rontolisp:wit-error :payload (list :other member)))))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store) ; => "wasi:keyvalue/store@0.2.0"
```

With that in the source, the `wasi:keyvalue` program of
[`wit-import`](rontolisp-wit-import.md) talks to `*rows*` — same
`(kv:bucket-get b "visits")` call sites, same `.wit`, and nothing in the program
that knows where the pairs live.

## Arguments

- The interface id, as a string: the fully-qualified id from the `.wit`
  (`"wasi:keyvalue/store@0.2.0"`). A `rontolisp:wit-import` dispatches on that
  canonical id whichever of the accepted spellings its `:interface` was given
  (`"wasi:keyvalue/store"` and the bare `store` name the same interface), so this
  one key binds the provider for all of them.
- The provider: any Lisp callable of `(member &rest args)` — a `#'name` function,
  a `lambda`, or anything else `funcall` accepts.

## The members a provider is asked for

One per function the interface declares, spelled as
[`wit-import` binds it](rontolisp-wit-import.md#what-gets-bound): `"open"`,
`"bucket-get"`, `"bucket-new"` for a constructor — plus, for each resource,
**`"<resource>-drop"`**, whose only argument is the handle. That last one is a
member no `.wit` declares: releasing a resource is a canonical built-in of the
component model rather than a function of the interface, so rontolisp names it
[`<resource>-drop`](rontolisp-wit-import.md#releasing-a-resource-resource-drop)
and dispatches it to the provider like any other member.

**What a drop *means* is the provider's decision, and only the provider's.** The
core knows that the program is done with a handle; it does not know what the
handle stood for. So a store that keeps its rows in a hash table forgets the
handle and keeps the rows (the `my-store` above answers `nil`, and that is a
complete implementation); one holding a JDBC connection or an open file closes
it; a provider whose handles cost nothing has nothing to release and simply
answers `nil` too. What a drop must **not** do is destroy the thing the handle
referred to: a handle is a *reference* to a store, never the store itself, so a
later `(kv:open "counts")` must still find every key that was written through
the dropped one.

Leave the member out and a drop falls into whatever fallback the provider has —
in `my-store` above, the `rontolisp:wit-error` clause. So a provider with nothing
to release should still answer `nil` for it rather than say nothing at all.

## rontolisp ships no providers

The core knows the provider **mechanism**. It does not know what `wasi:keyvalue`
is, and it ships no implementation of it — or of any other interface. That is
deliberate: a new host interface should cost a `.wit` file, not core code.
Calling a bound function before any provider is bound for its interface signals
[`rontolisp:wit-error`](#the-wit-error-condition) rather than reaching some
default:

```console
$ rontolisp counter.lisp
No provider is bound for the WIT interface wasi:keyvalue/store@0.2.0 -- bind one with rontolisp:wit-provide
```

Because a provider is *just a function*, a fake and the real thing are
interchangeable and the program cannot tell them apart. The
[`wit/keyvalue` example](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
is a page-view counter written against `wasi:keyvalue/store` with two stores
behind it: a portable in-memory Lisp one, and — on the JVM — one backed by a real
`java.util.LinkedHashMap` through [`java:` interop](../../guides/java-interop.md),
bound afterwards so that it replaces the first. The store changes; the
`(kv:bucket-set b "/index" "3")` call sites do not, and the counter's output is
identical either way. A real deployment swaps the map for Redis or a JDBC
connection the same way, in one line. Compiling to Preview 1 WASM instead makes
the **host** the provider, again with no change to the program.

## The `wit-error` condition

`rontolisp:wit-error` is the condition a WIT `result<T, E>`'s **error arm**
signals: the ok arm is the function's return value, the error arm is a condition,
on every backend. The provider signals it with the mapped `E` as its payload
(above: the `error` variant of `wasi:keyvalue`, a tagged list), and the caller
reads the payload back with `(rontolisp:wit-error-payload e)`:

```console
;;; The caller of an imported function, in the same program as the wit-import.
(handler-case (kv:bucket-delete *bucket* "visits")
  (rontolisp:wit-error (e)
    (print (rontolisp:wit-error-payload e))))   ; (:other "read-only store")
```

It is an ordinary condition class: `handler-case`, `ignore-errors` and
`unwind-protect` all work on it, and it is a subclass of `error`, so a bare
`(handler-case ... (error (e) ...))` catches it too.

## Backends

| Backend | Effect |
| --- | --- |
| interpreter | binds the provider; imported functions dispatch to it |
| JVM (`-o Prog.class`) | the same |
| Preview 1 WASM (`-o prog.wasm`) | a top-level form is **dropped** — the WASM host is the provider |
| `--component` | the same: dropped, the host (or a composed component) is the provider |
| `--no-gc` | it rejects `rontolisp:wit-import` itself |

Dropping the form rather than rejecting it is what lets **one source run on every
backend**: the `rontolisp:wit-provide` that backs the program on the interpreter
is simply inert once the host takes over.

## Limitations

- The interface id is matched as a **string**, so a provider bound under
  `"wasi:keyvalue/store"` does not serve calls dispatching to
  `"wasi:keyvalue/store@0.2.0"`. Spell it as the `wit-import` `:interface` does.
- A provider is global and unscoped: the last `rontolisp:wit-provide` for an
  interface wins, for the rest of the program.
- Nothing is marshalled or type-checked at the boundary on these backends — the
  provider is handed the Lisp values as they are, and its return value is handed
  back as it is. The [WIT type table](rontolisp-wit-import.md#supported-wit-types)
  is the contract to write it against.
- On a `rontolisp:wasm-import` declared by hand there is no provider to bind:
  `rontolisp:wit-provide` serves the interfaces `rontolisp:wit-import` binds.
