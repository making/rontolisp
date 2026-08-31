# A runtime `typep` designator does not resolve a `deftype` alias

Difficulty: Medium

Split out of `.todo/616`, which fixed the `make-array :element-type` half and
MEASURED this one. `(typep x ty)` with `ty` a VALUE that names a user
`deftype` answers `nil` where the literal spelling answers correctly:

```lisp
(deftype octet () '(unsigned-byte 8))
(print (typep 3 'octet))                    ; T on all four (literal: resolved)
(let ((ty 'octet)) (print (typep 3 ty)))    ; NIL on all four -- SBCL 2.2.9: T
(let ((ty 'octet)) (print (typep 300 ty)))  ; NIL on all four -- SBCL 2.2.9: NIL
(let ((ty 'octet)) (print (coerce 3 ty)))   ; "coerce: unsupported result type OCTET"
                                            ;   -- SBCL 2.2.9: 3
```

`coerce` with a computed result type is the same hole once more: its
fall-through arm is a computed `typep` (`expandComputedCoerce`), so it is
fixed by the same change. A `coerce` to an alias of a SEQUENCE type
(`(deftype str () 'string)`, `(coerce '(#\a) ty)`) additionally needs the head
dispatch above that arm to see the expansion, which `expandCoerce` has no
registry to do -- check it while here.

No backend disagrees with another: all four answer `nil`. This is an ANSI
conformance gap, not a cross-backend divergence, which is why it did not land
with 616.

## The fix, and the bill

Both dispatch shapes take the designator in one variable and could resolve it
in one place, beside `metaobjectNameNormalization`:

- `LispMacroExpander.expandRuntimeTypep` -- the interpreter's, re-expanded per
  call against the live registry.
- `LispMacroExpander.runtimeTypepDefun` -- the compile paths' `%typep-runtime`,
  built once per program. `%typep-compound-runtime` recurses back into it, so a
  sub-specifier naming an alias resolves too.

A working `cond`-per-alias version of exactly that was written and measured
while landing 616 (2026-08-31, raw wasm, `--optimize=size`):

| program | base | with the alias normalization |
|---|---|---|
| `array-operations` (`ql:quickload`, one `aops:zeros*`) | 94,336 | 104,411 (**+10.7%**) |
| a 2-alias program with no runtime typep | 62,963 | 63,059 (+0.15%) |

The cost is alexandria: it registers **43** `deftype` aliases -- its whole
`positive-fixnum` / `non-negative-double-float` zoo -- at ~220 bytes of arm
each, and a program that merely LOADS it carries all 43. 616's make-array half
escaped this by carrying only the aliases naming one of the six
`ArrayElementTypes` codes (the ones an arm can tell apart from `t`), so
array-operations came out byte-identical. **`typep` has no such narrowing**:
any of the 43 names a type `typep` decides differently from `nil`, so the full
table is the answer set.

+10.7% is a todo-612-scale bill (the inline `make-array` arms it rejected were
+32.6%) for a gap no backend disagrees about. Do not land the `cond` shape.

## What to try instead

The tree already has the cheaper shape for exactly this problem: a quoted DATA
table plus a scan, which is what `%typep-tag-table%` is and what
`chunkedTableForms` exists to emit (`a quoted constant compiles to construction
code proportional to its size`, and a table entry has no branch code of its
own). Sketch:

```lisp
(defvar %deftype-alias-table% '(((ALEXANDRIA::POSITIVE-FIXNUM POSITIVE-FIXNUM) (INTEGER 1 *)) ...))
(dolist (e %deftype-alias-table% nil)
  (if (member tn (car e)) (progn (setq tn (car (cdr e))) (return nil)) nil))
```

built beside `typepTagTableForms` and gated on the same `runtimeTypep` flag.
Measure it on the array-operations program above before deciding: if it is not
comfortably under the +10.7% the `cond` cost, the finding is that the gap is
not worth its bytes and this item closes with the measurement instead of the
change. The interpreter keeps the inline `cond` either way -- it re-expands per
call and has no injected defun to reach.

Behavior must be identical on all four backends: rows in `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` and a ci-spec case
whose expected text is SBCL 2.2.9's, plus the paragraph in
`.kb/array-literals.md` ("`typep` has the same hole and it does NOT come free
with this one") that currently records the gap and these numbers.
