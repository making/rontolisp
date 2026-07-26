# An ASDF-spliced third-party tree is never tree-shaken

`LibraryDefunPruner` prunes only rontolisp's own bundled libraries (linalg, vec,
json, url, prelude): `prunableNames()` is built from exactly those five, so every
definition a real library splices in is a ROOT. It also drags rontolisp's own
libraries along with it -- a dead third-party defun that mentions `linalg:norm`
keeps `linalg:norm` alive. `--optimize` cannot compensate: the WASM and JVM shakers
root at exports/start and follow `call` edges, and every compiled Lisp defun is
reachable from the funcall dispatcher, so a dead defun is never dropped there.
Measured: the reduction `--optimize` achieves is the same with and without the dead
defuns present.

## Measured cost (2026-07-26)

Program: `(asdf:load-system :cl-ppcre)` + one `scan` call, vendored cl-ppcre.

| version | wasm | .class | JVM constant pool |
| --- | --- | --- | --- |
| baseline | 1,551,115 | 2,043,610 | 6,522 |
| 13 statically dead top-level forms removed by hand | 1,504,414 (**-3.0%**) | 1,977,430 (**-3.2%**) | 6,305 (-3.3%) |

Static reachability over the loadable trees, using the pruner's own carve-out
(any symbol occurrence anywhere, plus string literals containing the name):

| system | top-level definitions | unreachable | source lines |
| --- | --- | --- | --- |
| cl-ppcre | 302 | 18 (6%) | 280 / 5,786 (5%) |
| cl-postgres | 180 | 42 (23%) | 533 / 2,644 (20%) |
| ironclad slice | 148 | 51 (34%) | 415 / 1,792 (23%) |

(9 of cl-ppcre's 18 are `defmacro`s, which `UserMacroExpander` already removes, so
the effective figure there is 13 forms -- which is the -3.0% row above.)

## What it takes

1. **Provenance.** `LoadInliner` does not mark which spliced forms came from a
   system, so the pruner cannot tell a library definition from a user one. Thread an
   index/marker through to `LibraryDefunPruner.prune`. The CLI call site
   (`RontoLispCli`) does not move.
2. **Carve-out cost.** `collectReferences` loops `for (String name : prunable)
   value.contains(name)` over every string literal. The prunable set grows from ~230
   names to ~3,000, and `CompileTimePathnameFolder` bakes 18,000-character chunks, so
   this becomes O(names x total literal bytes). Needs a trie / Aho-Corasick.
3. **A conservative line on what stays a root.** `defclass`/`defgeneric`/`defmethod`
   /`defstruct` expand INSIDE the backends, after the pruner runs, so pruning them
   would mean duplicating the CLOS method-selection rules in the pruner -- leave them
   roots. Same for `defsetf`/`define-setf-expander`. Keep `defvar`/`defparameter`
   roots too: a third-party init routinely registers into a table
   (`(setf (gethash oid *sql-readtable*) ...)`), which the current prunable set never
   does.
4. **The symbol-spelling hazard, in both directions.** `cl-postgres/protocol.lisp`
   dispatches through `(intern (string '#:make-ssl-client-stream) :cl+ssl)`, whose
   `LispSymbol` name is `"#:MAKE-SSL-CLIENT-STREAM"` -- an exact-name match misses it
   (prunes too much). But allowing a suffix match makes every `defpackage` `:export`
   keyword anchor the whole API, which collapses the dead-code figures above from
   6/23/34% to 3/4/11% (prunes almost nothing). This is the real design decision.

## Provenance of this item

Split out of `.todo/179` phase 6, which proposed a much narrower rule -- "a top-level
`let` whose body's only definitions are dead is itself dead" -- on the premise that
the pruner already knew uax-15's `get-illegal-char-list` was unreachable. It did not
and could not; the rule was measured at 771 bytes across every loadable library and
rejected, with the reasoning recorded in `.kb/library-defun-pruning.md`. The premise
underneath it -- pruning third-party trees at all -- is what has the numbers.
