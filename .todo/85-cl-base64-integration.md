# cl-base64 integration (real library load via asdf:load-system)

**Status:** IN PROGRESS. `asdf:load-system :cl-base64` now *loads* on the
interpreter (system parses + all files eval), but no public function is callable
yet. Target library: cl-base64 v3.4 (2020-10-16, BSD), vendored candidate under
scratchpad `cl-base64-20201016-git/` (encode.lisp + decode.lisp + package.lisp).

This library is HARD, not the "one small residue" the old triage memory
suggested -- it hits **4 distinct language gaps**. Two small ones are already
fixed; three deeper ones remain. All were confirmed empirically (not just read).

## Done in this session

1. **`.asd` `:name` option tolerated** (`AsdfSystems.parseDefsystem`): added
   `:name` to the ignored-metadata case (cl-base64.asd uses `:name`; `:licence`
   was already tolerated). Safe, additive.
2. **`~<newline>` format directive** (line continuation) in
   `LispMacroExpander.FmtParser.dispatch`: default ignores the newline + following
   non-newline whitespace, `~@\n` keeps the newline, `~:\n` keeps the whitespace.
   Used by `define-base64-decoder` to build docstrings via `format nil` at
   macro-expansion time. Safe, additive. (1120 targeted tests still green.)

## Remaining blockers (all confirmed by running the interpreter)

### A. Macro-time function-name synthesis relies on CL symbol-name semantics [KEYSTONE]
`def-*-to-base64-*` and `define-base64-decoder` build EVERY public function name
at macro-expansion time, e.g.
`(intern (concatenate 'string (symbol-name input-type) (symbol-name :-to-base64-) (symbol-name output-type)))`
and `(intern (format nil "~A-~A-~A-~A" '#:base64 hose '#:to sink))`.

RontoLisp's `symbol-name` is case-preserving AND keeps the keyword `:` and the
gensym `#:` marker (documented deviation, `.kb/symbol-runtime-api.md`), so:
- `(symbol-name :string)` => `":string"` (CL: `"STRING"`)
- `(format nil "~A" :string)` => `"#:...:string"`-style junk (princ keeps colon)

=> generated name `:string:-to-base64-:string`, so `string-to-base64-string`
et al. are never defined. **Fix idea:** make `symbol-name` (and princ / `~A`)
strip a leading `:` (keyword) and leading `#:` (uninterned) prefix, matching CL
and the EXISTING `string`-of-keyword behavior (cl-who already strips the colon
for `(string :kw)` -- symbol-name is just inconsistent with it). Case stays
lowercase, which is self-consistent because the reader preserves case and the
`:export` names are lowercase too, so interned names match the driver's symbols.
**Risk: cross-cutting** -- `symbol-name` has pinning tests + is documented; verify
split-sequence / cl-utilities / cl-who still pass. This is the decision the user
should weigh in on before we change it.

### B. `(setf (schar s i) c)` / `(setf (char s i) c)` string mutation
Not supported even on the interpreter: `expandSetf` has no `schar`/`char` place
(`setf does not support place: schar`). encode.lisp builds its result by
`(make-string n)` + per-index `(setf (schar result i) ch)`; decode `:string`
sinks do the same. `LispString` is already a mutable buffer (cl-who work), so the
interpreter fix is: add a `schar`/`char`/`svref-on-string` setf place +
`%schar-set` primitive that mutates in place. **Compile path (JVM/WASM) is the
known-hard part** -- `char`/`schar`/`elt` currently lower to fresh strings; a
real in-place mutation ABI is needed (the residue that kept cl-base64 blocked
before). Interpreter-only is enough to *run* encode; all 4 backends need the
compiled ABI.

### C. Condition system in decode error paths
decode.lisp calls `signal`, `cerror`, and `(error 'bad-base64-character :input ...
:position ... :code ...)` against `define-condition` classes with slots/`:reader`/
`:report`. `signal`/`cerror` are undefined (`fboundp` => nil) and
`define-condition` is a lite no-op. Happy-path encode/decode never signals, BUT
the **compile path must still compile these defun bodies**, so lite stubs are
needed at minimum: `signal`/`cerror` as `%`-primitives (or macro lowerings) and
`(error 'symbol :initargs)` routed through the existing `make-condition`
`:format-control` path. Full condition system is `.todo/39`.

### D. (lower priority, verify after A-C) `locally`, `let/typed`+declare, `etypecase/unroll`
`#-sbcl` branch of `etypecase/unroll` expands to `(locally (declare (type ...)) body)`
-- `locally` is unsupported (0 impl files). `let/typed` is a user macro that emits
`(let ... (declare (type ...)) body)`; declare-in-let-body must be a no-op that
doesn't break. Only exercised by the `:string`-hose decoders. `make-array
:element-type '(signed-byte 8) :initial-element -1` + `(setf (aref dt i) v)` in
`make-decode-table` -- confirm `:initial-element` is honored and aref-setf works
on that array (needed for `+decode-table+`, hit at load time via `defconstant`).

## Suggested order
A (keystone, unlocks names) -> B interpreter (run encode) -> C lite stubs (compile
decode) -> D -> then JVM, then compile-path B (string setf ABI) for WASM/component.
Verify on all 4 backends; vendor under `src/test/resources/cl-base64/` (BSD, keep
COPYING), add `ClBase64E2eTest` + a ci-spec residue case for the new features.

## Meta
Old memory `asdf-library-candidates` said "interpreter alone might now load it;
recheck" -- rechecked: NO, name synthesis (A) blocks even the interpreter. Correct
that memory. The 2 landed fixes are worth keeping regardless of whether the full
integration proceeds.
