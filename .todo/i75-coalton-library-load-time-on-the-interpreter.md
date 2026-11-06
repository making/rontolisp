# i75. coalton's library load: from 60 minutes toward SBCL's minute

Difficulty: High

Measured 2026-11-05 (`.kb/asdf.md`, "Libraries that load", the coalton entry): `(ql:quickload "coalton")` on
the interpreter takes 60 minutes on a box under load 3-4; SBCL 2.2.9 compiles and loads the same tree in 61 s.
Each `coalton-toplevel` runs coalton's typechecker and codegen at macro-expansion time, interpreted, then the
interpreter evaluates what they generated. The compile paths (`.todo/i73`) run the same typechecker in the
macro-time image, so this cost reaches them too once they get past their early stops.

What is known:

- coalton's `reduce-context` maps the whole value environment through `fset:image` for every binding group, so
  a file costs more the later it comes: `seq.lisp` 503 s, `hashmap.lisp` 425 s, `list.lisp` 333 s.
- Otherwise the time is the interpreter itself: no frame above `evalCons` over 11% (JFR).
- Already done (`.todo/i58`): the dispatcher chain memo (`.kb/clos.md`, "The interpreter's chain memo"), the
  per-site expansion memos without a bound, a native `equalp`, no call frames without a SWANK server.

Not measured:

- How the same files split on SBCL: whether `reduce-context` dominates there as well (an algorithmic cost both
  pay) or is out of proportion here (a cost of fset's operations, or of what they dispatch to, on this
  interpreter). Measure per file on both before choosing.
- What inside `fset:image` and the functions it reaches is slow here: generic dispatch on fset's `compare`,
  struct access, closures, `equalp`, allocation.

The work: find where the interpreter is out of proportion and fix it generally, so that other libraries gain too,
not with a coalton- or fset-specific path. Trace progress with the `asdf:perform :before` hook in `.todo/i58`'s
removed file (`git show 1d38c8226~:.todo/i58-coalton-standard-library-load-past-math-complex.md`). Record the per-file
numbers, ours and SBCL's, in `.kb/asdf.md`. Done when the load time drops by a large factor and what remains is
explained by measurement.
