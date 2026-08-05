# First-class builtin wrappers are 68% of a WASM module the moment anything funcalls

Difficulty: High

Split out of `.todo/259` (WASM serve throughput), whose measurements produced the
numbers below. 259's own work -- the serve GC pre-grow and enabling the core tree
shaker under `--component` -- is done; this is what remained.

## The measurement

A trivial serve component (`(defun handle (env) (list 200 '(:content-type "text/plain")
(list "Hello " (getf env :path-info)))) (rontolisp:http-handler 'handle)`), compiled
`--component`, is **714,633 bytes**. Its core module's code section (665,179 B over
589 functions) breaks down as:

| part | functions | bytes | share of code |
|---|---|---|---|
| runtime helpers (indices 0..180) | 181 | 122,418 | 18% |
| spliced `RONTOLISP::%HTTP-*` / wit defuns | 30 | 57,808 | 9% |
| **`BuiltinFunctionWrappers` first-class wrappers** | **339** | **484,052** | **73%** |

`TANH`, `VECTOR-POP`, `UNION`, `WRITE-TO-STRING`, ... -- one wrapper defun per
built-in, for a handler that takes none of them as a value.

`--optimize` cannot touch them, and that is by design: the Clack model `funcall`s
the handler, so the arity-dispatch bodies are live, and each dispatch body contains
a real `call` to EVERY registered function (that is exactly what keeps
`eval`/`apply`/`#'name` honest). The shaker sees a live edge and keeps all 339. Same
program without the funcall -- `(print "hi")` as a plain component -- shakes from
357 KB to 29 KB, which is the size this ought to be.

## Why it matters beyond bytes

Module size is instantiation cost, and a served component is instantiated every 128
requests (`.kb/tcp-sockets.md`). With todo-259's pre-grow fix in place, `_start` is
what is left: at `--max-instance-reuse-count 1` the 714 KB native component serves
1708 rps while the 1.88 MB clack one serves 1030 -- a 2.6x size difference costing
2.6 ms per instantiation. It is also the whole cold-start story on a
one-instance-per-request host (wasmCloud).

## Status (2026-08-05): the mechanism landed, this program still does not benefit

The generalized gate exists -- `.kb/optimize-dead-code-elimination.md`, "The
funcall-dispatch gate". It works at the DISPATCH level rather than by suppressing
wrapper emission, which reaches the same end: a wrapper the program never takes as a
value gets no ladder case, so `--optimize` shakes it out along with every other
call-only function. Measured **-49.3%** on an `md5` program.

It does NOT yet help the serve component this item is about: **685,933 -> 660,153
(-3.8%)**, and all of that is the unrelated `_ub_read` change. The gate bails there,
for the reason `.todo/261` documents (the spliced runtime `format` renderer resolves
the `~/name/` directive's target by name and funcalls it, so any function may be
reached). Finish 261 and this item's numbers should follow; keep this file until the
serve figure is actually re-measured.

The prediction below about a naive scan was right, and the way out was the third
bullet rather than the first: collect the materialized funcIds DURING Pass 2 instead
of scanning the source program at all.

## The shape of the fix

The seam already exists: `wrapperExcludes` in `WasmLispCompiler` (~line 1866) and
`JvmLispCompiler` (~line 736), plus `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`
-- a handful of wrappers are ALREADY gated on the program taking the operator as a
first-class value. Generalizing that gate to all of them is the fix.

**It is not a one-liner, and a naive version fails immediately** (verified): gating on
`BuiltinFunctionWrappers.referencesFunctionValue` over the raw program dies with
`Cannot compile: IDENTITY` and `the function %SEQ-STRING is undefined` on the serve
program, because the SPLICED libraries and later expansion passes reference wrappers
that a pre-expansion textual scan does not see. What it needs is what
`LibraryDefunPruner` already does for library defuns (`.kb/library-defun-pruning.md`):

- reachability over a `PackageResolver.resolveProgram` copy, so package spellings match;
- the same carve-out semantics (any occurrence, including quoted data and string
  literals, counts as a reference);
- the synthesized-reference edges (a macro expansion that emits `#'identity` /
  `%seq-string` AFTER the gate runs);
- a bail-out when the program can reach a wrapper by name at run time at all --
  `eval`, `load`, `read`, `intern`-driven `funcall`, `symbol-function`, `--dynamic`.

Doing it as a widening of `LibraryDefunPruner` (one reachability engine, wrappers as
one more prunable set) is likelier to stay correct than a second scanner.

## Acceptance

- All four backends (the wrappers are a JVM concern too -- `JvmLispCompiler` has the
  identical `wrapperExcludes` seam and the same 1:1 wrapper-per-builtin cost).
- `WasmTreeShakerCorpusTest` / `JvmClassShakerCorpusTest` over the whole ci-spec
  corpus stay green -- that corpus is the feature catalogue and WILL exercise the
  dynamic paths the bail-out has to cover.
- Report the new size for the trivial serve component and for
  `examples/asdf/clack-hello.lisp`; re-run 259's serve benchmark at
  `--max-instance-reuse-count` 1 / 128 to show the instantiation saving.
