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

> **Interpreter today.** `cffi` works under `java -jar rontolisp.jar` and in the
> REPL. Neither WASM backend has a foreign function API, so compiling such a program
> to a `.wasm` is a `Cannot compile: FFI:...` error, permanently. The native binary
> and the `-o Prog.class` output do not carry the binding yet.

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
`:offset`, a bitfield) is refused by name instead of being passed on a guess.

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

## What does not work

| | |
|---|---|
| `cffi-grovel` | Grovelling compiles and runs a C program to read the platform's headers, which needs a C toolchain at load time. A system naming it in `:defsystem-depends-on` is **refused with that sentence** rather than half-loaded. Most bindings do not grovel |
| `cffi-libffi` | Refused too, and for the opposite reason: structures by value already work (above), so there is nothing for it to add |
| `cffi:defcvar` | Not available — it expands into `define-symbol-macro`, which rontolisp does not have. Read the variable through `cffi:foreign-symbol-pointer` plus `cffi:mem-ref` instead |
| `with-pointer-to-vector-data` | Copies **in and out** instead of pinning: the body sees a fresh foreign buffer, and what the C side wrote reaches the Lisp vector when the body returns, not before. A pointer kept past the body is dangling |
| `:long-double` | Not a foreign type here |

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
