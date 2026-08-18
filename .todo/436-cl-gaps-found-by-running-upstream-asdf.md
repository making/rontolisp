# 436. CL gaps found by running upstream ASDF on the interpreter

Difficulty: High

**These are NOT an ASDF-migration plan.** The decision (2026-08-18) is to keep
the lightweight shim and widen it per library, as we have been doing — see the
"Why this is a shim and not real ASDF" section of `.kb/asdf.md` for the spike
that decided it. What is left here is the by-product: plain Common Lisp defects
and holes that upstream ASDF 3.3.7 tripped over on the interpreter, none of
which are ASDF-specific. Any real library can hit them, and several already
explain shim work we keep redoing.

**Fill these PROACTIVELY, not on demand** (decision 2026-08-18). Waiting for a
library to trip over one is how they were found in the first place: each of them
surfaces as a confusing failure inside somebody else's code, days after the
feature that needed it was written, and the shim absorbs the blame. They are
also cheap relative to that: the list is closed, upstream ASDF is a ready-made
exerciser for all of it, and items 3-11 are ordinary missing surface.

Suggested order — each group is a landable change with its own tests, and the
groups are independent:

- **A. Items 1-2 (the bugs).** Do these first and separately: item 1 is a change
  to what a hash table IS, and it wants its own design pass, not a slot in a
  batch. Item 2 is small and self-contained; landing it first also makes item 1
  debuggable (a cyclic key stops being an unreadable stack trace).
- **B. Items 5, 6, 4, 3 (the portability surface).** The four that any real
  library can hit: computed stream options, `load` keywords, string designators,
  wild pathnames. Highest value per line of work.
- **C. Items 7-10 (the CLOS surface).** `reinitialize-instance` /
  `shared-initialize` first — items 7, 9, 10 are small once the initialization
  protocol is re-entrant.
- **D. Item 11 (missing names).** Mechanical, but read the entry before adding
  the package-mutation half: it is a documented non-goal and stays one.

Delete each entry as it lands; delete the file when the last one goes. Items 1
and 2 are real BUGS rather than missing surface; the rest are missing surface.

Each item must land on all four backends with the usual ladder
(interpreter -> JVM -> WASM -> ci-spec) unless the entry says otherwise.

## The two that are bugs

1. **Every hash table is keyed by the PRINTED representation of its key, and the
   test is always `EQUAL`** — `LispHashTable.get`/`put`/`remove` call
   `key.print()`, and there is no `eq`/`eql` table. Three consequences:
   - a CYCLIC value used as (or inside) a key does not terminate. Upstream ASDF
     keys its session cache on `` `(component-depends-on ,operation ,component) ``
     and a component's graph is cyclic (`parent` <-> `children`), so the spike
     died with a Java `StackOverflowError` inside `LispHashTable.put`. Any
     library that memoizes on a structure with a back-pointer does the same.
   - object IDENTITY cannot be a key: two distinct instances that print alike
     collide. A library that uses an `eq` table as an identity map (memoization,
     visited-sets, object registries) is silently wrong here, not slow.
   - lookup is O(size of the key's printed graph), on every get and put.
   `*print-circle*` does not exist either, so the printer cannot be the fix on
   its own. `.kb/hash-tables.md`.

2. **`print-object` is dispatched only for the TOP-LEVEL object.** An instance
   nested in a list or vector is rendered by the built-in renderer instead:
   with a `print-object` method on `c`, `(print i)` gives `#<C custom>` but
   `(print (list i))` gives `(#<C :X 1>)`, and `~S` behaves the same. A library
   that defines `print-object` to keep its objects readable (or finite) only
   gets it honoured when the object is printed alone. `.kb/clos.md`,
   `.kb/pretty-printer.md`.

## Missing surface, in the order upstream hit it

3. **`make-pathname :directory` rejects `:wild` and `:wild-inferiors`**
   (`MAKE-PATHNAME: unsupported :directory component :WILD-INFERIORS`), and with
   them `wild-pathname-p` / `translate-pathname` honouring the components. This
   is what `directory-files` / `subdirectories` / recursive traversal are built
   on upstream. `.kb/pathnames.md`, `.kb/directory-listing.md`.

4. **String designators.** `(string-trim "*" some-symbol)` signals
   `STRING-TRIM expects a string`; CL coerces symbols and characters through
   `string` in the whole string family. Sweep the family, not one function.

5. **`with-open-file` (and `open`) demand LITERAL keyword arguments** —
   `WITH-OPEN-FILE :element-type must be the literal 'character or
   '(unsigned-byte 8)`. The portable way a library opens a file is a wrapper
   that passes `:element-type` / `:external-format` / `:if-exists` /
   `:if-does-not-exist` as VARIABLES, which cannot compile here at all. Probably
   the most reusable item on this list.

6. **`cl:load` takes no keyword arguments** — `LOAD expects 1 argument, got 3`
   for `(apply 'load x keys)`. At minimum accept (and may ignore) `:verbose` /
   `:print` / `:if-does-not-exist` / `:external-format`.

7. **`change-class` demands a literal quoted class name**; a computed class is
   `CHANGE-CLASS requires a literal quoted class name`.

8. **`reinitialize-instance` is registered in `PackageRegistry.CL_SYMBOLS` but
   not implemented.** `shared-initialize` and `slot-makunbound` are registered
   the same way — a name being in `CL_SYMBOLS` is NOT evidence it works, so grep
   before trusting the set. The spike's stand-in was a Lisp defun over
   `c2mop:class-slots` / `c2mop:slot-definition-initargs` (both work once
   `(asdf:load-system :closer-mop)` has run), which shows the shape but skips
   `shared-initialize` and the `:after` methods.

9. **`defclass` slot options `:writer (setf f)` and `:allocation :class`** are
   both hard errors.

10. **Type specifiers `standard-class` / `built-in-class`** in `typecase` /
    `etypecase` (`Unsupported type specifier: STANDARD-CLASS`).

11. **Missing names**: `with-compilation-unit` (a `progn` wrapper is a legitimate
    implementation — there is no `compile-file` — but it has to exist),
    `*load-verbose*` / `*load-print*` (unbound), `user-homedir-pathname`,
    `make-package`, `do-symbols`, `copy-symbol`, `invoke-debugger`,
    `remove-method`, `package-nicknames`, `rename-package`, `delete-package`,
    `unuse-package`, `shadow`, `shadowing-import`, `unintern`, `file-stream`,
    `synonym-stream`, `readtable`, `most-positive-fixnum`, `compile-file` +
    `compile-file-pathname`. The runtime package-mutation half of that list is a
    documented non-goal (`.kb/symbol-runtime-api.md`) — do not add it without a
    library that needs it. `(setf (symbol-value v) x)` / `set` is `.todo/367`.

## How these were found

`.kb/asdf.md`, "Why this is a shim and not real ASDF", carries the reproduction
recipe. Re-running it is the cheapest way to find the NEXT gap after fixing one:
the loader is an ordered work list of what actually blocks. Note that the
interpreter expands macros lazily, so a `with-open-file` / `change-class` error
surfaces at CALL time, not at load — a file that loads clean is not clean.
