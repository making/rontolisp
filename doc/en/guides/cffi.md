# C Libraries (cffi)

rontolisp runs the **real, upstream [CFFI](https://cffi.common-lisp.dev/)** — the
library every C binding in the Common Lisp ecosystem is written against — not a
look-alike of its API. `(ql:quickload :cffi)` downloads the same release SBCL
would, loads its portable source unmodified, and gives you `cffi:defcfun`,
`cffi:defcstruct`, `cffi:defcallback` and the rest.

What rontolisp supplies is the one file every implementation has to write for
itself: CFFI's documented backend seam, the `cffi-sys` package, bound to the JVM's
foreign function API (no JNI, no bundled native library, no reflection). Everything
above that seam — the type system, `defcfun`'s argument walker, the enum and
bitfield layers, the translate/expand protocol — is upstream's code.

> **Where it runs.** `cffi` works under `java -jar rontolisp.jar`, in the REPL, in
> the `rontolisp` native binary, and in a compiled `-o Prog.class` / `-o app.jar`,
> which carry the binding inside the emitted class. Neither WASM backend has a
> foreign function API, so compiling such a program to a `.wasm` is a
> `Cannot compile: FFI:...` error, permanently.

## A C function in three lines

```console
CL-USER> (ql:quickload :cffi)
CL-USER> (cffi:defcfun "strlen" :long (s :string))
CL-USER> (strlen "hello, world")
12
```

`defcfun` names a C function and its types; the Lisp function it defines does the
marshalling. `:string` copies the Lisp string into foreign memory for the call and
frees it after, and a `:string` RETURN reads the NUL-terminated UTF-8 back.

A `defcfun` costs a **downcall handle**, which is built once per call SHAPE
(return type plus argument types) and reused for every symbol of that shape.
Building one is about 24 µs; calling it is about 0.5 µs. So the first call
through a new shape pays for the handle and every later call — through any
function of the same shape — does not, which is why a binding that defines a
hundred functions over a dozen shapes warms up in microseconds rather than
milliseconds.

For a one-off call there is `foreign-funcall`, which needs no definition:

```console
CL-USER> (cffi:foreign-funcall "getpid" :int)
30211
```

## A library, and the flat namespace

`define-foreign-library` names a library per platform and `use-foreign-library`
opens it; after that a plain `defcfun` finds its symbols.

```console
CL-USER> (cffi:define-foreign-library libsqlite
    (:darwin "libsqlite3.dylib")
    (t "libsqlite3.so.0"))
CL-USER> (cffi:use-foreign-library libsqlite)
CL-USER> (cffi:defcfun ("sqlite3_libversion" sqlite-version) :string)
CL-USER> (sqlite-version)
"3.45.1"
```

A `defcfun` never says which library its symbol came from, and every binding in the
ecosystem relies on that — CFFI calls it the *flat namespace*. The foreign function
API has none of its own (a lookup is per library, and the process's own lookup never
sees a library opened later), so the backend keeps the opened libraries in load
order and searches them. The effect is the one every binding assumes.

Which clause is chosen is decided by `*features*`, and it names the host: on macOS
`:darwin`, `:bsd` and `:arm64` (or `:x86-64`) sit beside `:unix`, on Linux `:linux`
does. That is what makes a `(:default "libsqlite3")` clause mean `libsqlite3.dylib`
on one machine and `libsqlite3.so` on the other, and on macOS it is also what puts
Homebrew's `/opt/homebrew/lib` on the fallback search path CFFI uses when the
system loader comes up empty. The names arrive with `trivial-features`, which
`cffi` depends on.

## Memory, types and pointers

`with-foreign-object` allocates for the extent of its body, `mem-ref` and `mem-aref`
read and write through a pointer, and `foreign-type-size` answers a C type's size.

```console
CL-USER> (cffi:with-foreign-object (tv :long 2)
    (cffi:foreign-funcall "gettimeofday" :pointer tv :pointer (cffi:null-pointer) :int)
    (cffi:mem-ref tv :long))
1787757356
CL-USER> (list (cffi:foreign-type-size :int) (cffi:foreign-type-size :pointer))
(4 8)
```

The C integer type names are their LP64 widths: `:long` and `:unsigned-long` are 8
bytes, as they are on every platform rontolisp's linker serves. `defctype` aliases a
type, and `:size` — CFFI's own alias for `size_t` — resolves, because rontolisp
announces `:64-bit`.

A pointer is its own kind of value, not an integer: `cffi:pointerp` answers `nil`
for `42`, and `make-pointer` / `pointer-address` convert in both directions.
`foreign-alloc` is `malloc` and `foreign-free` is `free` — foreign memory outlives
every Lisp scope, which is CFFI's own contract.

## Strings

```console
CL-USER> (cffi:with-foreign-string (s "hello")
    (cffi:foreign-string-to-lisp s))
"hello"
```

`foreign-string-alloc`, `foreign-string-free`, `lisp-string-to-foreign`,
`with-foreign-strings` and `with-foreign-pointer-as-string` are all there. The
encoding is UTF-8 by default; `:latin-1` and `:us-ascii` work for the octets they
can represent, and any other `:encoding` **signals** rather than handing back
mis-coded bytes (the same rule as the [`babel` shim](asdf-systems.md#built-in-shim-systems)).

## Structures, including by value

```console
CL-USER> (cffi:defcstruct timeval (tv-sec :long) (tv-usec :long))
CL-USER> (cffi:with-foreign-object (tv '(:struct timeval))
    (cffi:foreign-funcall "gettimeofday" :pointer tv :pointer (cffi:null-pointer) :int)
    (cffi:foreign-slot-value tv '(:struct timeval) 'tv-sec))
1787757356
```

Passing and returning a structure **by value** works with nothing extra installed:

```console
CL-USER> (cffi:defcstruct div-t (quot :int) (rem :int))
CL-USER> (cffi:defcfun ("div" c-div) (:struct div-t) (numer :int) (denom :int))
CL-USER> (c-div 17 5)
(QUOT 3 REM 2)
```

On other implementations that call signals "Unable to call structures by value
without cffi-libffi loaded" and offers to load a system built around a C library.
Here the foreign function API lays the structure out itself from the member types,
so the call is the ordinary one — and `cffi-libffi` is never needed. A structure
whose layout CFFI and the foreign function API do not agree on (a hand-written
`:offset`, a bitfield) is refused by name instead of being passed on a guess. A
nested structure is laid out flat, which is the same memory and the same call: a
rectangle of two points is four doubles.

The `rontolisp` **native binary** carries a bounded family of by-value shapes rather
than all of them, because it has to compile each one ahead of time; the family and
what falls outside it are in [In the native binary](#in-the-native-binary) below.

## Callbacks and variadic calls

`defcallback` turns a Lisp function into a C function pointer:

```console
CL-USER> (cffi:defcallback cmp :int ((a :pointer) (b :pointer))
    (- (cffi:mem-ref a :int) (cffi:mem-ref b :int)))
CL-USER> (cffi:with-foreign-object (arr :int 4)
    (loop for i from 0 for v in '(4 2 9 1) do (setf (cffi:mem-aref arr :int i) v))
    (cffi:foreign-funcall "qsort" :pointer arr :long 4 :long 4
                          :pointer (cffi:callback cmp) :void)
    (loop for i below 4 collect (cffi:mem-aref arr :int i)))
(1 2 4 9)
```

An error escaping a callback would unwind into the C frame above it and end the
process, so one never does: the message is printed and the callback answers zero of
its declared type. Redefining a callback answers a **new** address — a C side
already holding the old one keeps calling the old definition.

A variadic call is `foreign-funcall` with the extra arguments written out; CFFI
promotes them (`:float` to `:double`, `:char`/`:short` to `:int`) and the backend
marks where the variadic tail starts, which is what makes the call correct on
AArch64 and Apple silicon as well as x86-64.

```console
CL-USER> (cffi:with-foreign-pointer (buf 64)
    (cffi:foreign-funcall "snprintf" :pointer buf :long 64 :string "%s-%d"
                          :string "x" :int 7 :int)
    (cffi:foreign-string-to-lisp buf))
"x-7"
```

## C globals

`defcvar` names a C global, and the Lisp name then reads and writes like a
variable — it is a [symbol macro](../reference/special-forms/define-symbol-macro.md)
over the generated accessor, not a variable, so `setf` and `incf` go straight to the
C storage.

```console
CL-USER> (cffi:defcvar ("optind" *optind*) :int)
CL-USER> *optind*
1
CL-USER> (setf *optind* 7)
7
CL-USER> (cffi:pointerp (cffi:get-var-pointer '*optind*))
T
```

## Libraries that use CFFI

The point of running upstream CFFI is the libraries written against it. What
has actually been tried here:

| library | result |
|---|---|
| [**cl-sqlite**](https://common-lisp.net/project/cl-sqlite/) (`sqlite`) | **works.** `(ql:quickload "sqlite")` and you have a real database: `connect`, `execute-non-query`, `execute-to-list`, `execute-single`, `with-transaction`, prepared statements stepped by hand. There is no SQL engine bundled here — the `libsqlite3` on your machine is the engine. See [`examples/jvm/cffi-sqlite.lisp`](https://github.com/making/rontolisp/blob/develop/examples/jvm/cffi-sqlite.lisp). Interpreter and native binary; see the `defcenum` row below for why not a compiled class |
| **static-vectors** | **does not load, and cannot.** It is not a CFFI consumer but a second implementation seam: its `.asd` refuses an implementation its own list does not name, and past that it needs a per-implementation file supplying a vector whose storage is memory a pointer can be taken into. Nothing here has such an array. Allocate with `cffi:foreign-alloc` instead |
| **cl+ssl** | **loads, and is a usable HTTPS client**: the whole `defcvar`/`defcallback`/`defcstruct` surface of the largest binding in Quicklisp runs here, a real TLS handshake completes through cl+ssl's Lisp BIO — OpenSSL calling back into Lisp for every octet — and a `GET https://example.com/` sent over `(cl+ssl:make-ssl-client-stream ...)` reads back a real `HTTP/1.1 200 OK` response, on the interpreter and the native binary alike. Not one blocker met along the way was in CFFI: the last one was a rontolisp stream being indistinguishable from a small integer, which made cl+ssl's `(etypecase socket (integer …) (stream …))` BIO dispatch pick the wrong arm — fixed by giving an open stream its own dispatch instead of sharing an integer's representation. The bundled [`cl+ssl` shim](asdf-systems.md#built-in-shim-systems) over rontolisp's own TLS stays the default anyway: it needs no OpenSSL on the machine and works on the WASM component backend, where CFFI never will |

## What does not work

| | |
|---|---|
| `cffi-grovel` | Grovelling compiles and runs a C program to read the platform's headers, which needs a C toolchain at load time. A system naming it in `:defsystem-depends-on` is **refused with that sentence** rather than half-loaded. Most bindings do not grovel |
| `cffi-libffi` | Refused too, and for the opposite reason: structures by value already work (above), so there is nothing for it to add |
| `with-pointer-to-vector-data` | Copies **in and out** instead of pinning: the body sees a fresh foreign buffer, and what the C side wrote reaches the Lisp vector when the body returns, not before. A pointer kept past the body is dangling |
| `:long-double` | Not a foreign type here |

## In the native binary

A native image compiles a stub per foreign call **shape** ahead of time, and
`defcfun` invents shapes at run time, in your program — so the binary ships a
registered grid. Every narrow integer travels as its 64-bit carrier, and so does
every pointer and string — to the ABI a pointer and a 64-bit integer are the same
parameter — which collapses a C API's shapes to three carriers per parameter; the
grid then covers every pointer/integer call to arity 10, with `double` mixed in to
arity 4 and `float` to arity 2, at every return carrier, and the callback shapes to
arity 6. In practice a binding's fixed-arity calls just work.

A structure returned **by value** is the one thing that cannot be collapsed that way:
the ABI decides how to return it from the members themselves, so the member list is
part of the shape. The binary carries a bounded family of them instead — every
one- and two-member structure over the C scalar widths, plus the three- and
four-member ones whose members are all the same type — with a nested structure
counting flattened. The *arguments* of such a call still collapse, so `div`, `ldiv`
and `imaxdiv` are one registered shape, not three.

A call outside the grid — a narrow integer argument past the sixth, say, or a
structure with more members than the family carries — signals an error naming the one
`reachability-metadata.json` entry that would register it, so the fix is to add
that entry and rebuild the binary, or to run the program on `java -jar`, where any
shape binds.

## Where the pieces live

`(ql:quickload :cffi)` fetches upstream's release like any other system; three
bundled pieces make it load (see [Systems (asdf)](asdf-systems.md)):

- a replacement `cffi.asd` — upstream's own opens with
  `(error "Sorry, this Lisp is not yet supported")` for an implementation its list
  does not name, and ends in a `defmethod`, so it cannot be read as data;
- the `cffi-sys` backend, spliced in as the implementation component, so upstream's
  tree on disk is never edited;
- a substitute for `src/strings.lisp`, the one portable file that cannot load (it
  drives a babel code generator the [`babel` shim](asdf-systems.md#built-in-shim-systems)
  does not have). Its whole surface is reproduced over `babel:string-to-octets`.

Everything else — `package`, `sys-utils`, `utils`, `libraries`, `early-types`,
`types`, `enum`, `structures`, `functions`, `foreign-vars`, `features` — is
upstream's source, byte for byte.
