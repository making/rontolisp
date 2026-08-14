# `uiop` is 26 of 434 externals, and its sub-packages do not exist

Difficulty: High

**This is the umbrella item for uiop coverage: items 354-365 each fill one
sub-package and all of them depend on the skeleton built here.** Land this
first; the rest are then independent and can be taken in any order.

## Where we are

The coverage target is uiop **3.3.7**, the release the built-in Quicklisp client
fetches (`~/.rontolisp/quicklisp/software/uiop-3.3.7/`). `uiop` is
`uiop/driver`, a `:use-reexport` of 15 sub-packages; excluding the three
implementation-conditional exports (`use-ecl-byte-compiler-p` on clasp/ecl,
`probe-posix` on mcl, `sb-grovel-unknown-constant-condition` on sbcl) it is
**434 external symbols**.

rontolisp's `uiop` holds **26** of them, plus three names real uiop does not
export (`namestring`, imported from `cl` -- upstream inherits it, so that one is
right; `when-let` / `when-let*`, which are alexandria's and stay as deliberate
extras). Three of the 26 resolve but have no definition at all.

| | count |
|---|---|
| target externals (uiop 3.3.7, portable) | 434 |
| present and working | 23 |
| present but signal *undefined function* (`os-unix-p`, `os-macosx-p`, `run-program`) | 3 |
| absent from the package | 408 |

Two different failures reach the user, and neither says "not supported":

```console
$ rontolisp -e '(uiop:string-prefix-p "ab" "abc")'
error: The symbol STRING-PREFIX-P is not external in the UIOP package (use UIOP::STRING-PREFIX-P)
$ rontolisp -e '(uiop:os-unix-p)'
Unhandled condition: The function UIOP:OS-UNIX-P is undefined
```

The sub-packages are absent too -- `(:import-from :uiop/utility ...)`, which
real libraries write, is a READ error (`No such package: UIOP/UTILITY`). Only
`uiop/image` exists, added ad hoc when lack-middleware-backtrace needed it.

There is also a cross-backend hole inside the 23: `uiop:merge-pathnames*` is a
real function **on the interpreter only**. Compiled, a call with non-literal
arguments becomes `The function UIOP:MERGE-PATHNAMES* is undefined` on the JVM
and on both WASM backends.

## The fix

**1. Register the 15 sub-packages, and make `uiop` a re-export of them.**
Each sub-package owns its own externals; `uiop` records every member as an
IMPORT redirecting to its home package. `PackageRegistry` already does exactly
this for `closer-common-lisp` -- reuse that mechanism rather than inventing a
`:use-reexport` notion. A library naming either spelling then reaches the SAME
symbol, which is why `uiop/image:print-condition-backtrace` was done this way
already.

**2. Make the target DATA, not a Java literal.** Check in
`uiop-exports.txt` (`<sub-package>\t<symbol>`, extracted from the pinned 3.3.7
sources) and build the package tables from it. Then add `UiopCoverageTest`,
which is the gate every other item reports to:

- every listed symbol is external in `uiop` AND in its home sub-package;
- every listed symbol is fbound or bound -- **no undefined-function residue**;
- it prints per-sub-package coverage, so the number is measurable rather than
  asserted.

**3. Give "we cannot do that here" a name.** `uiop:not-implemented-error` and
`uiop:parameter-error` are upstream's own mechanism and are the first two
symbols to land (they live in `uiop/utility`, item 354, but every other item
needs them). Anything rontolisp genuinely cannot do -- `dump-image`,
`launch-program` on WASM, logical pathnames -- signals `not-implemented-error`
with the operation named. `The function UIOP:X is undefined` must not survive
anywhere.

**4. Move uiop out of the prelude's scattered defuns into a Lisp-source
library.** Today the 23 working names are split between `LispPreludeLibrary`
string bodies, `PackageRegistry` tables and `LispMacroExpander`'s
`expandUiopStubCall`, whose default arm is "every other uiop call is an error".
That does not scale to 434. Give uiop `.lisp` resources on the
`usocket.lisp` / `closer-mop.lisp` pattern, one per sub-package, so bodies can
stay close to upstream and `LibraryDefunPruner` drops what a program does not
call (`.kb/library-defun-pruning.md` -- this is what keeps 400 definitions from
costing artifact size). `expandUiopStubCall` keeps only its real folds
(`file-exists-p` -> `probe-file`, `namestring` / `native-namestring` ->
`namestring`) and loses the error arm.

**5. All four backends, or the same error on all four.** Every uiop function
either runs on the interpreter, the JVM and both WASM backends, or signals
`not-implemented-error` identically on all four. `merge-pathnames*` is the
existing violation and is fixed by step 4 (a Lisp-source definition compiles);
each item below must not add another.

**6. Decide the doc shape BEFORE the first batch.** 408 per-operator pages in
two languages is not the answer. The proposal: one `reference/uiop/<sub-package>.md`
page per sub-package carrying a row per symbol (the existing "uiop Package
Functions" table in `reference/functions.md` grows into it), with per-operator
detail pages only for names a user program actually calls. Every item below
assumes this; if it is rejected, they all get bigger and this item must say so.

**7. `.kb/uiop.md`** -- new: the sub-package layout, the inventory resource and
its test, the `not-implemented-error` rule, and the list of what is deliberately
lite. Add the line to `.kb/README.md`.

## Non-goals

- The three implementation-conditional exports.
- `when-let` / `when-let*` stay (alexandria names, not uiop's); document them as
  rontolisp extras rather than deleting them.
- ASDF itself. `uiop` is the target; `asdf:` stays the subset `.kb/asdf.md`
  describes.

## Gate

`UiopCoverageTest` exists and passes with 15 sub-packages registered, the
inventory checked in, and `not-implemented-error` / `parameter-error` real. Its
printed coverage line reads 26/434 the day this lands -- the point is that the
number is now real and the remaining items move it.
