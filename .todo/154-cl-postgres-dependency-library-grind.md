# cl-postgres dependency libraries as REAL sources: remaining grind

Parent: `.todo/115` (cl-postgres driver). Design line: `.todo/147` (shims only
for portability-layer libraries; grow missing CL features instead). Goal state
for the `:depends-on ("md5" "split-sequence" "ironclad" "cl-base64" "uax-15")`
chain, plus the transitive `cl-ppcre` (via uax-15; cl-postgres itself has one
`cl-ppcre:scan` UUID check in data-types.lisp).

## Status (2026-07-18)

- `split-sequence` -- REAL, done (todo-054).
- `cl-base64` -- REAL, done (todo-085).
- `md5` -- REAL, DONE this session on the interpreter + JVM (`Md5E2eTest`,
  `examples/asdf/md5-demo.lisp`, RFC 1321 vectors + incremental API +
  `md5sum-string` UTF-8 via the flexi-streams shim's new `string-to-octets`).
  WASM excluded: the MD5 working state is unsigned 32-bit arithmetic beyond
  the `i31` fixnum range (same wall as int8/OID -- see the WASM-bignum idea
  below).
- `cl-ppcre` -- IN PROGRESS. Gates cleared so far: defpackage `:shadow`
  (real resolver support), `make-sequence`, `array-total-size-limit`,
  `define-condition :default-initargs`, defstruct docstrings + lite BOA
  constructors + struct names as defmethod specializers, defgeneric
  `(declare ...)` options. Cleared 2026-07-18 (second session): `&environment`
  in macro lambda lists (stripped, bound to nil), `get-setf-expansion` (a
  prelude defun with a `values` tail -- the mvb spill channel destructures it,
  no new syntactic producer needed), 2-arg `constantp`, `simple-string-p`
  (lowered to `stringp`), `psetf` (parallel, place subforms hoisted to temps),
  `(setf (aref s i) c)` on mutable strings (interpreter `%aset`/
  `%row-major-aset` accept a LispString cell), local `(declare (special x))`
  (collected PESSIMISTICALLY program-wide by `SpecialVarCollector`, which now
  recurses; `special` clause head matched package-insensitively -- the
  convert.lisp state-threading idiom; lambda AND macro parameters named like
  specials now bind dynamically in the interpreter, since symbol reads consult
  the dynamic store first), CLOS slot readers/accessors are now METHODS of a
  generic (same reader name over different slot positions in unrelated
  classes -- `len` in str/repetition/lookbehind/filter -- plus merging with a
  plain `defmethod len (void)`; write side = a `%setf-` writer generic under
  the SETF_FUNCTION_MARKER convention), `initialize-instance :after` (the
  first user method synthesizes an identity default primary; make-instance
  hoists initargs to temps and calls the generic), position-ambiguous
  `slot-value`/`with-slots` falls back to the reader generic, `subst`
  (prelude defun), format `~?` (recursive format via the runtime renderer).
  **DONE 2026-07-18 (third session): cl-ppcre v2.1.2 RUNS ON THE INTERPRETER**
  (`ClPpcreE2eTest`, vendored `src/test/resources/cl-ppcre`,
  `examples/asdf/cl-ppcre-demo.lisp`): scan (register bounds), scan-to-strings,
  split, regex-replace(-all), all-matches(-as-strings), count-matches,
  do-scans/do-matches macros, register-groups-bind, quote-meta-chars,
  parse-tree regexes, `(?i)`. The final gates: NAMED `block`/`return-from`
  (interpreter-native BlockReturnSignal caught by the matching block; defun
  bodies = a block named after the function, defmethod bodies = the generic's
  name; `%block` is transparent to the named signal so returns cross loops;
  compilers keep the lite name-dropping rewrite, `block` lowers to `%block` —
  which is why the compile backends are still EXCLUDED for cl-ppcre),
  `char>`/`char>=`/`char/=`/`char-equal`, `copy-tree`/`search` (prelude),
  `(map 'vector ...)`, `coerce 'simple-string`, `(setf (subseq ...))`,
  typep with a bare-spelled class name in-package (descendantTags needed the
  REGISTERED class name), special-named params/let dual-bind (dynamic push +
  lexical define, so closures capture — reg-num/end-string), and the
  pure-config-setter walk seeing through the new block wrapper (cl-who
  regression). `loop named` remains unsupported (unneeded:
  `*use-bmh-matchers*` defaults nil → the search path).
  **DONE 2026-07-19 (fourth session): cl-ppcre v2.1.2 RUNS ON ALL FOUR
  BACKENDS** (`ClPpcreE2eTest` fully enabled). The compile-path gates cleared:
  LEXICAL named `block`/`return-from` (`%fn-block` function boundary +
  name-keyed goto/br targets, `.kb/do-return-block.md`), `loop named`,
  flet/labels locals get their CL block, special-let DUAL-BIND (dynamic set +
  captured lexical; dynamic-first reads; setq writes both) + exit restores
  (`.kb/dynamic-special-variables.md`), the `UserMacroExpander` double-resolution
  fix (`evalResolved`) + shadowed-CL-name requalify (the `:shadow defconstant`
  infinite expansion), `apply #'f` physical direct calls past
  `MAX_CALLABLE_ARITY`, closure-over-let defuns/defmethods (global-closure
  `setq` + call-through-variable), `#*` bit-vector literals, sequence-general
  `make-array :initial-contents`, `nthcdr` past-end nil, cold-path lowering for
  cross-function `go` and unsupported format directives, `adjustable-array-p`
  non-array nil, and the new prelude entries (`alphanumericp`, `sxhash`, `sbit`,
  `both-case-p`, `get`, `find-class`, the introspection stubs,
  `make-load-form-saving-slots`) + `print-unreadable-object` /
  `with-package-iterator` macros. `cl-ppcre:split` now unblocks uax-15
  (its OWN extra gates listed below).
- `uax-15` -- blocked on cl-ppcre (`cl-ppcre:split` at load time), then
  needs: `asdf:find-system` + `asdf:system-source-directory` +
  `uiop:merge-pathnames*` + `make-pathname` (load-time data-file path
  resolution in precomputed-tables.lisp), and load-time
  `with-open-file :external-format :UTF-8` reads of `unicode-15-data/*.txt`
  (verify the keyword-value acceptance and per-line `read-line` perf over
  ~34k-line UnicodeData.txt). Loop shapes look supported (destructuring
  `for (a b c) in` works -- md5 exercised it).
- `ironclad` -- real loading judged INFEASIBLE under the current
  architecture: `ironclad.asd` is executable code (defclass on
  cl-source-file, a defmacro generating defsystems, uiop/format at parse
  time), which contradicts the mini-ASDF "parse `.asd` as plain data"
  invariant (`.kb/asdf.md`); the 129-file source tree also carries per-impl
  vops/MOP. Unless the ASDF subset grows real evaluation, the JDK-backed
  crypto shim strategy (frozen `cl-postgres-wip` branch, M2) remains the
  route -- consistent with `.todo/147` only if treated as a
  "cannot support rontolisp from its side" case; revisit when scram.lisp
  becomes the active gate.

## Related infrastructure idea (not scoped here)

WASM unsigned-32/bignum arithmetic: md5 (and int8/OID values in cl-postgres)
need exact integers past `i31`. Today they silently wrap into negative i31
values on the WASM backends. A boxed-i64 (or double-backed) overflow path
would unlock md5 on WASM and remove the `Md5E2eTest` backend exclusion.
