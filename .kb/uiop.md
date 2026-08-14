# uiop: the sub-package bundle, the inventory, and `not-implemented-error`

**Invariant: no `uiop` name may reach a caller as `The function UIOP:X is
undefined`.** uiop 3.3.7 exports 429 symbols; rontolisp implements a growing
subset and every remaining one signals `uiop:not-implemented-error` naming the
operation, identically on all four backends. The coverage target is DATA
(`uiop-exports.txt`), not a Java literal, and `UiopCoverageTest` is the gate
every uiop item reports to.

This file supersedes the uiop paragraphs in `.kb/asdf.md`, which describe the
pre-bundle "mostly a package stub" arrangement.

## The 15 sub-packages

Upstream's `uiop` IS `uiop/driver`: a `:use-reexport` of 15 sub-packages
(`uiop/package`, `uiop/package-local-nicknames`, `uiop/package*`,
`uiop/utility`, `uiop/version`, `uiop/os`, `uiop/pathname`, `uiop/filesystem`,
`uiop/stream`, `uiop/image`, `uiop/launch-program`, `uiop/run-program`,
`uiop/lisp-build`, `uiop/configuration`, `uiop/backward-driver`). A library may
name either spelling -- `lack-middleware-backtrace` writes
`(:import-from :uiop/image :print-condition-backtrace)`, which is a READ error
when the package is absent, and `uiop/image` was registered ad hoc for exactly
that before the bundle existed.

`PackageRegistry` registers all 15 from `UiopExports`, using the
`closer-common-lisp` mechanism rather than inventing a `:use-reexport` notion:

- each sub-package OWNS the members its own inventory rows list and EXPORTS them;
- a member a second sub-package also exports is an IMPORT REDIRECT to the home
  one (upstream's `uiop/backward-driver` re-exports five `uiop/configuration`
  names, and `*output-translation-function*` is exported by both
  `uiop/pathname` and `uiop/lisp-build`);
- `uiop` itself owns nothing from the inventory: every one of its 429 externals
  is an import redirect to the home sub-package.

**The consequence that drives everything else: the HOME spelling is canonical.**
`PackageResolver` rewrites a `uiop:getenv` occurrence to `UIOP/OS:GETENV`, so
that is the name a definition of it must carry, the name a switch label must
match, and the name that appears in an error message. `UiopExports.qualified`
composes it and `UiopExports.denotes(pkg, member, X)` recognizes BOTH spellings
-- a pass that runs on either side of resolution needs the second (the backends'
dispatch gate runs after, `GenericDispatchNarrowing` before). Every hard-coded
qualified name in `LispNames` (`UIOP_GETENV`, `UIOP_SYMBOL_CALL`,
`UIOP_IF_LET_QUALIFIED`, `UIOP_WITH_DEPRECATION_QUALIFIED`,
`UIOP_WITH_TEMPORARY_FILE_QUALIFIED`) is a switch-label constant and therefore
cannot be computed; `UiopCoverageTest.theHardCodedQualifiedNamesAgreeWithTheInventory`
pins each against the table.

## The inventory: `uiop-exports.txt`

`src/main/resources/am/ik/rontolisp/uiop-exports.txt`, read by
`am.ik.rontolisp.UiopExports` (root package, so `PackageRegistry` and
`eval.UiopLibrary` can both use it without either depending on the other).

Format: `<sub-package> TAB <symbol> TAB <kind>`, `#` comments, ONE ROW PER
EXPORT. **435 rows / 429 distinct symbols / 15 sub-packages.** Rows are in
upstream LOAD order and alphabetical within a sub-package, so the FIRST row for
a symbol names its home and every later row is a redirect -- the file is
self-describing and the tables are built by reading it top to bottom.

`kind` is upstream's DEFINITION form (`function`, `macro`, `variable`,
`constant`, `condition`, `class`, `type`, `+`-joined when a name is defined
twice -- `not-implemented-error` is a condition AND the function that signals
it). It is a third column beyond what `.todo/353` specified because a stub
cannot be the right SHAPE without it: a `defun` for a name upstream defines with
`defvar` would satisfy `fboundp` and answer the wrong predicate forever.

**Regenerating it** (only when the pinned uiop version moves). The extractor
reads the `define-package` forms out of
`~/.rontolisp/quicklisp/software/uiop-<version>/` with a PORTABLE feature set --
`*features*` bound to `(:package-local-nicknames)` only, so the three
implementation-conditional exports (`use-ecl-byte-compiler-p` on clasp/ecl,
`probe-posix` on mcl, `sb-grovel-unknown-constant-condition` on sbcl) are absent
-- and joins each symbol with the definition form found by scanning the same
files for `defun`/`defmacro`/`defvar`/`define-condition`/... and for
`:reader`/`:accessor` slot options. `.todo/353` quoted 434 externals; the real
figures are the two above (that count summed the per-sub-package export lists
without deduplicating the six names two sub-packages both export).

## `eval.UiopLibrary`: one home for every definition

Real definitions live in `uiop-<sub-package>.lisp` resources next to the class
(currently `utility`, `pathname`, `filesystem`, `stream`, `image`), in canonical
shape. Everything the inventory lists that no resource defines gets a stub
SYNTHESIZED from its kind:

| kind | stub |
|---|---|
| `function`, `macro` | `(defun NAME (&rest %uiop-stub-args) (uiop:not-implemented-error "NAME"))` |
| `variable`, `constant` | `(defvar NAME nil)` |
| `condition` | `(define-condition NAME (error) ())` |
| `class` | `(defclass NAME () ())` |
| `type` | `(deftype NAME () t)` |

Three groups are deliberately NOT stubbed, because something else defines them
and a stub would shadow it: `JAVA_DEFINED` (`getenv`, `symbol-call`,
`add-package-local-nickname` -- Java built-ins the compilers lower or wrap),
`LispMacroExpander.hasUiopMacroExpansion` (`if-let`, `with-temporary-file`,
`with-deprecation`, `define-package`), and -- separately -- the members that
ALSO carry a fold (`file-exists-p`, `native-namestring`), which do have a Lisp
definition here so `#'uiop:file-exists-p` is a value like any other.

**Filling a name in is a one-line change**: add the real definition to the
matching `.lisp` resource and the stub disappears on its own, because a name the
resources define is never stubbed. `UiopCoverageTest.printCoverage` then moves.

Stub caveats, deliberate and documented rather than silent:

- a `variable` stub is bound to `nil`, which for most of them (`*image-dumped-p*`,
  `*lisp-interaction*`, ...) is also upstream's default but for some is not --
  the item that implements the sub-package sets the real value;
- a `constant` gets a `defvar`, not a `defconstant`: the value is a placeholder,
  and pinning a placeholder as a constant only makes the real definition a
  redefinition;
- a `condition`/`class` stub is flat (`(error)` as the only superclass) where
  upstream has a hierarchy (`compile-file-error` inherits `compile-condition`);
- a `macro` stub is a `defun` so the name is `fboundp` and usable as a value,
  but the CALL FORM never reaches it: `LispMacroExpander.expandUnimplementedUiopMacro`
  lowers it to `not-implemented-error` with its argument forms DROPPED. A macro
  that does nothing must not evaluate what it was handed -- an unimplemented
  `(uiop:with-upgradability () (defun f ...))` would otherwise define `f` before
  signalling. The evaluator and both compilers share that one expansion, which
  is what makes the four backends agree.

## Selection, not pruning

`UiopLibrary.process` prepends only the definitions the program reaches,
computed to a fixpoint on a `PackageResolver.resolveProgram` copy (so a
`uiop:name` occurrence is matched as the home symbol it denotes). One
surface-form rule, mirroring `LispPreludeLibrary.referencedBySurfaceForm`:
`uiop:with-temporary-file`'s expansion runs inside the expression compilers,
long after this pass, and reaches `ensure-directory-pathname` /
`default-temporary-directory` / `delete-file-if-exists` through the prelude's
`%temp-file-name`, so seeing the surface form selects those three.

**uiop is NOT in `LibraryDefunPruner`'s prunable set** (the usocket precedent).
`.todo/353` proposed splicing all 429 and letting the pruner shake them out;
selecting up front is the same saving one step earlier, and it avoids paying a
full resolution pass over 429 definitions in every uiop-using program. Revisit
only if a case appears where a SELECTED definition is provably unreachable.

**`LispPreludeLibrary.process` CALLS `UiopLibrary.process` first** rather than
sitting beside it in the pipeline. The two are mutually dependent -- a uiop body
calls `namestring`/`pathname`/`merge-pathnames`/`directory`/`%dir-namestring`
here, and the prelude's `%temp-file-name` calls uiop back -- so they are one
pass with a fixed order, and a pipeline that ran only the prelude would
reintroduce exactly the "undefined function" this library exists to abolish.
Re-running it is a no-op: a definition the program already carries is not
spliced again (`UiopLibraryTest.aSecondRunSplicesNothingMore`).

The interpreter lazy-loads ONE name's forms on first resolution
(`LispEvaluator.loadUiopDefinition`), reachable from both the function and the
variable lookup. It loads the `not-implemented-error` pair first whatever the
name is: every stub signals it, and a quoted condition name is not a function
resolution, so nothing else would trigger its load.

## What moved, and why `merge-pathnames*` was the tell

Before this, the 23 working members were split across `LispPreludeLibrary`
string bodies, `PackageRegistry` tables, Java built-ins in `LispEvaluator`, and
`expandUiopStubCall`, whose default arm was "every other uiop call is an error".
`uiop:merge-pathnames*` was the visible cost: a Java built-in on the interpreter
only, so a call with non-literal arguments was
`The function UIOP:MERGE-PATHNAMES* is undefined` on the JVM and both WASM
backends. It is Lisp source now (`uiop-pathname.lisp`, over `cl:merge-pathnames`,
whose rontolisp definition already implements the same rule), and so are
`file-exists-p` and `native-namestring`, which used to be Java-side for the same
reason. `expandUiopStubCall` kept only its real folds and lost the error arm.

**Every uiop function either runs on all four backends or signals
`not-implemented-error` identically on all four.** That is the acceptance
criterion for items 354-365; `merge-pathnames*` was the one violation and it is
gone.

## Documentation shape

One page, `doc/{en,ja}/reference/uiop.md`, with a section per sub-package
concern: the model, a coverage table over the 15, the implemented-member table
(with links to the existing per-operator pages), and what an unimplemented
member signals. `reference/functions.md` keeps a pointer only. When a
sub-package's section outgrows the page -- an item landing 30-70 real members --
it moves to `reference/uiop/<sub-package>.md`, which is `.todo/353`'s proposal
arrived at when it pays. Per-operator detail pages stay for names a user program
actually calls, not for all 429.

## Tests

- `UiopCoverageTest` -- the gate: every listed symbol external in `uiop` AND in
  its row's sub-package; every listed symbol defined (`fboundp` for a function
  or macro, `boundp` for a variable, a registered type otherwise); the
  hard-coded `LispNames` spellings agree with the inventory; the unimplemented
  member signals naming the operation; the unimplemented MACRO does not evaluate
  its forms; and the printed per-sub-package coverage.
- `UiopLibraryTest` -- selection: both spellings select the one definition, the
  fixpoint, the stub dragging in the condition it signals, the
  `with-temporary-file` surface rule, idempotence, the already-defined guard,
  and that the prelude pass drives this one.

## Deliberate extras (`uiop` owns them; the inventory does not list them)

- `uiop:namestring` -- upstream only INHERITS CL's through
  `(:use :uiop/common-lisp)`, so `uiop:namestring` would not read there. Kept
  external and imported from `cl`, so both spellings name the one prelude
  function.
- `uiop:when-let` / `uiop:when-let*` -- alexandria's names, not uiop's (real
  uiop exports `if-let` only). Kept because programs already spell them.
- `uiop::get-pathname-defaults` -- internal in real UIOP too, answering `""`.
