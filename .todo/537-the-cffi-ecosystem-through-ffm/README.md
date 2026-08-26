# The CFFI feasibility spike (2026-08-26)

Throwaway probes kept for reproducibility, NOT project code: they are outside `src/`, are
not in the reactor, are not formatted, and nothing builds or tests them. They exist so the
claims in `../537-the-cffi-ecosystem-through-ffm.md` and its children can be re-derived.

The question the spike answers: **can rontolisp run the real, upstream CFFI -- the library
the Common Lisp ecosystem's C bindings are written against -- rather than a look-alike of
its API?** Answer: yes, on both counts that matter. Upstream's portable layers load
essentially unmodified, and its documented backend seam (the `cffi-sys` package, ~30
names) maps onto `java.lang.foreign` with nothing missing.

## The files

| file | question it answers |
| --- | --- |
| `Spike.java` | Does one dynamic C calling convention fit on FFM? Signature strings decided at run time, `libm`/`libsqlite3` opened at run time, `:string` in and out, a data symbol read (`defcvar`), out-parameters, a run-time `StructLayout` with named slots, an upcall through `qsort`, varargs through `snprintf`, `errno` capture, and the cost of building a downcall handle per call vs caching it |
| `NativeSpike.java` + `native-spike-reachability-metadata.json` | What of that survives `native-image`? |
| `cffi.asd` | the `.asd` as it had to be edited: the unsupported-implementation `(error ...)`, the trailing `defmethod version-satisfies`, the `test-op` clauses, plus `:rontolisp-features (:64-bit)` and the added `(:file "cffi-rontolisp" :if-feature :rontolisp)` |
| `cffi-rontolisp.lisp` | the `cffi-sys` backend, written against `java:` interop rather than a real binding -- the spike's whole point is that nothing in Java had to change to get this far |
| `cffi-strings-substitute.lisp` | a stand-in for upstream `src/strings.lisp`, the ONE portable file that does not load (it drives babel's `instantiate-concrete-mappings` code generator, which the babel shim does not have) |
| `use.lisp` | the end-to-end demonstration: the real `cffi:` API, ending in a live sqlite3 session |

## Re-running it

```bash
cp -r ~/.rontolisp/quicklisp/software/cffi-20260101-git /tmp/cffi
cp cffi.asd /tmp/cffi/ && cp cffi-rontolisp.lisp /tmp/cffi/src/
cp cffi-strings-substitute.lisp /tmp/cffi/src/strings.lisp
sed -i "s/(asdf:operate 'asdf:load-op 'cffi-libffi)/(error \"no libffi\")/" /tmp/cffi/src/functions.lisp
sed -i 's/babel:simple-unicode-string/babel::simple-unicode-string/' /tmp/cffi/src/strings.lisp
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar use.lisp \
  --system-path "/tmp/cffi:$HOME/.rontolisp/quicklisp/software/alexandria-20241012-git"

java --enable-native-access=ALL-UNNAMED Spike.java
```

## What `use.lisp` prints, through the real upstream CFFI

```
strlen        = 12                 ; defcfun over the process's own symbols
cos(0.0)      = 1.0                ; define-foreign-library + use-foreign-library + a renamed defcfun
sqlite ver    = 3.45.1             ; a third-party library, :string return
getpid        = 2351204            ; foreign-funcall, the anonymous form
gettimeofday  = 1787752088         ; with-foreign-object + mem-ref, an out parameter
type sizes    = int 4 pointer 8 my-size 8   ; defctype + foreign-type-size
string trip   = hello              ; with-foreign-string + foreign-string-to-lisp
struct slot   = 1787752088         ; defcstruct + foreign-slot-value
sqlite open   = rc 0               ; a live sqlite3 session, open/exec/errmsg/close
sqlite exec   = rc 0
sqlite errmsg = not an error
```

## What the load probe found

Loaded UNMODIFIED, in this order: `package`, `sys-utils`, `utils`, `libraries`,
`early-types`, `types`, `enum`, `structures`, `functions`, `foreign-vars`, `features` --
the whole type system, the parse-method protocol, the enum/bitfield layer, the struct
layer, `defcfun`'s argument walker, and the CLOS-heavy `translate-to-foreign` /
`expand-to-foreign-dyn` machinery. `types.lisp` reached its 1073rd line on the first try.

Only these stopped it:

1. `(defctype :size #+64-bit :uint64 #+32-bit :uint32)` -- the last two forms of
   `types.lisp`. rontolisp declares neither feature, so the form loses its base type and
   cffi's own `check-type` fires. `:rontolisp-features (:64-bit)` in the `.asd` clears it.
2. `strings.lisp` needs `babel:simple-unicode-string` / `unicode-string` / `unicode-char`
   / `babel::string-get` / `string-set` and then babel's `instantiate-concrete-mappings`
   macro; the shim has none of them.
3. `functions.lisp` reads `asdf:operate` inside a restart body that never runs; the symbol
   is not external in rontolisp's `asdf`.
4. `cl:subsetp` does not exist here at all (`define-foreign-library` calls it).

## The three numbers

- **A downcall handle must be cached by shape.** Building one per call: ~24 µs. Calling a
  cached one: ~0.5 µs (10k iterations, `cos`, Oracle GraalVM 25.0.4, this machine).
- **Native image, registered shape:** works, and the descriptor may still be *built* at run
  time from strings -- registration is per leaf shape, not per call site. Opening a library
  with `SymbolLookup.libraryLookup` at run time works too.
- **Native image, unregistered shape:** `MissingForeignRegistrationError: Cannot perform
  downcall with leaf type (long,int)int`. Catchable, and the message names the shape -- so
  the binary can signal a Lisp error that says exactly which metadata entry is missing.
  An upcall additionally needs the callback target registered for *reflection*, not just
  its `foreign.upcalls` shape.

## What the spike did NOT cover

`defcallback` through the Lisp backend (upcalls are proven in `Spike.java` and
`NativeSpike.java`, not wired into `cffi-rontolisp.lisp`); varargs from the cffi side
(proven in `Spike.java` via `Linker.Option.firstVariadicArg`); structures by value
(upstream needs `cffi-libffi` for these -- FFM does them natively, so rontolisp can
implement `*foreign-structures-by-value*` and never need libffi); `cffi-grovel` (it
compiles and runs a C program to read headers -- out of scope); `:long-double`.
