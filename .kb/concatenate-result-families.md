# `concatenate` — the three result families (one contract, four backends)

User-facing behavior: `doc/{en,ja}/reference/functions/concatenate.md`.

`compiler/ConcatenateForms` is the ONE home of the contract. Both halves live
there so the interpreter and the compilers cannot drift:

- `resultSpec(designator, closRegistry)` — the shared normalizer over an
  EVALUATED result-type designator (quote already stripped), returning a
  `ResultSpec(family, intWidth)`. A symbol or a compound spec's head maps to
  `STRING` (`string`/`simple-string`/`base-string`/`simple-base-string`), `LIST`
  (`list`/`cons`) or `VECTOR` (`vector`/`simple-vector`/`array`/`simple-array`/
  `bit-vector`/`simple-bit-vector`), with package-qualified spellings normalized
  through their member name. `resultFamily` is the family-only view of the same
  call, kept for the callers that do not care about the element type.
- `intWidth` is the PACKED result family (see below): 8/16/32 when the vector
  designator spells `(unsigned-byte 8|16|32)`, 0 otherwise.
- `expand(cons, normalizeArguments)` — the compile-path lowering, called from the
  `CONCATENATE` case of `Jvm/WasmExprCompiler`. String family → the nested binary
  `%string-concat` chain (a lone argument concatenates with `""`, so the result is
  always fresh), each argument that is not a literal string wrapped in
  `(%seq-string arg)` when `normalizeArguments` is set (see below). List family →
  `(append (coerce a 'list) (coerce b 'list) ... nil)`; the trailing `nil` is what
  makes `append` copy the LAST argument too. General vector family → that same
  list, wrapped in `(coerce ... 'vector)`; packed vector family → that same list,
  wrapped in `(%seq-int-vector ... width)`. Nothing new is emitted per backend:
  the lowering is entirely over primitives both compilers already have.
- `literalResultSpec(typeForm)` / `literalResultFamily(typeForm)` — `expand`'s
  entry (and `--no-gc`'s check): normalizes the type argument AS WRITTEN, i.e.
  only a literal `(quote ...)`.

The interpreter keeps its Java builtin (`Environment`, `LispNames.CONCATENATE`)
and resolves the designator through the same `resultSpec`, so it also accepts a
COMPUTED result type — the one deliberate interpreter-only extra (a compiler has
to resolve the family statically; `coerce` has the same split and the reference
pages say so).

## The vector family keeps an `(unsigned-byte 8|16|32)` element type (todo-262)

`'(vector (unsigned-byte 8))` / `'(simple-array (unsigned-byte 8) (*))` builds
the PACKED representation `make-array` already gives
(`.kb/packed-integer-vectors.md`), NOT a general vector. Element types used to be
dropped here on the grounds that "rontolisp vectors are generic"; that stopped
being true when todo-194 added packed integer vectors, and the gap was a wrong
answer rather than a lite approximation — ANSI requires the result to BE of the
requested type, `md5:md5sum-sequence`'s `etypecase` has a
`(simple-array (unsigned-byte 8) (*))` arm and no general-vector one, and
cl-postgres' `md5-password` feeds it exactly this `concatenate`. That is what
made 7 of 13 `ClPostgresE2eTest` legs fail with `ETYPECASE: no clause matches
#(...)`.

- **`%seq-int-vector`** (`LispNames.SEQ_INT_VECTOR`, a `cl` internal) is
  `(lambda (seq w) ...)`: `(coerce seq 'list)`, then one of three literal
  `(make-array (length l) :element-type '(unsigned-byte 8|16|32))` allocations
  (the element type has to be a LITERAL for every backend's recognizer to pick
  the packed representation, so a runtime width dispatches onto three
  allocations rather than passing the designator along), then a `do` loop
  filling with `%aset`. A `BuiltinFunctionWrappers` entry, i.e. an ordinary
  injected defun, so NO backend needed a new primitive. It is a CALL for the
  reason `%seq-string` is (`.kb/wasm-function-body-size.md`), and it additionally
  walks the element list LINEARLY — the equivalent inline
  `(make-array n :element-type ... :initial-contents list)` indexes its contents
  with `elt`, which is O(n) on a list.
- **The injection is gated** on `ConcatenateForms.needsSeqIntVector(program)` OR
  a `#'concatenate` reference (the wrapper calls it too). Unlike the
  `%seq-string` gate this one cannot be outrun by a codegen-time expansion:
  nothing the compiler generates concatenates into a packed element type
  (`format`, `with-output-to-string` and the string-stream builders all emit the
  `'string` family), so `expand` needs no "helper available" flag.
- **The JVM's `usesIntArray` gate is forced on** by the same flag: that gate is a
  source scan over the PROGRAM, and the helper's `make-array` calls live in the
  wrapper, which the scan never sees. wasm-GC needs no such forcing — its packed
  array types and `_iv_set` are unconditional.
- Which spellings carry an element type is a SHAPE rule, not a position rule:
  `(vector T ...)`, `(array T ...)` and `(simple-array T ...)` lead with the
  element type, while `(simple-vector SIZE)` and the bit-vector spellings carry a
  SIZE there. Reading position 1 unconditionally would turn esrap's
  `(simple-vector 41)` into a specialized request — the same trap
  `LispMacroExpander`'s `typep` array arm documents.
- An unsupported width (`(unsigned-byte 4)`, `(signed-byte 8)`) and every
  non-integer element type stay the general vector, so nothing that used to work
  changes shape.
- The interpreter's builtin builds the `LispIntVector` directly (it never goes
  through `expand`), sharing `Environment.packedIntVector` with its own
  `%seq-int-vector`.

Re-evaluation trigger: `coerce` and `map` still DROP the element type
(`expandCoerce` collapses a compound spec to its head), so
`(coerce list '(vector (unsigned-byte 8)))` is a general vector. That is a
divergence from `concatenate` and it survives only because no library exercised
it — the shared builder to reuse when one does is `%seq-int-vector`.

## A user deftype alias resolves through the class registry (todo-256)

fast-http's multipart parser concatenates into `'simple-byte-vector`, its own
`(deftype simple-byte-vector (&optional (len '*)) `(simple-array (unsigned-byte
8) (,len)))`. `resultFamily(designator, closRegistry)` therefore resolves a
designator (or compound-spec head) that names none of the built-in members
through `ClosRegistry.findDeftype`, transitively (alias-of-alias, depth-capped).
Every entry point grew the registry parameter: `literalResultFamily`, `expand`
(both compilers pass `ctx.closRegistry` at their CONCATENATE case) and
`needsSeqString` (so a deftype alias of the STRING family gates `%seq-string`
in too — the WASM compiler now runs that scan AFTER `expandTopLevelDefinitions`,
the same slot the JVM always used, so the registry is populated). The
registry-less overloads remain for the codegen-time expansions (`format`,
`with-output-to-string`), which only ever build the built-in spellings.

What makes the parameterized deftype resolvable at all: the interpreter's
`foldDeftype` registers the body evaluated with defaulted parameters, and on
the compile paths `UserMacroExpander` replaces the form with the equivalent
zero-parameter deftype that `expandTopLevelDefinitions` registers — both
pre-existed this work. The interpreter's builtin is re-registered WITH the
evaluator's registry (`Environment.concatenateBuiltin(closRegistry)` in the
`LispEvaluator` constructor); the `createGlobal` default stays registry-less.

The `#'concatenate` wrapper is deliberately NOT alias-aware: its dispatch is a
runtime `member` over the designator with no registry at run time, and every
real call site that goes through it (http-body's
`(apply #'concatenate '(simple-array (unsigned-byte 8) (*)) ...)`) spells a
built-in family head. Re-evaluation trigger: a library `apply`-ing
`#'concatenate` onto a deftype-alias designator would need the alias table
baked into the wrapper at injection time.

## The string family takes any character sequence (`%seq-string`, todo-202)

`(concatenate 'string "a" '(#\b #\c) #(#\d) nil "e")` is `"abcde"` on every
backend, and `nil` — the empty list — is the case real code leans on: s-sql's
`expand-table-name` builds `"CREATE TABLE person"` as
`(concatenate 'string (unless tableset "TABLE ") (to-sql-name name))`. An element
that is not a character is an error, not a silent `princ`.

The string family used to take STRINGS only, because it lowers straight to
`%string-concat` (whose operands are strings; a marked mutable character vector
normalizes first, a cons list does not). Widening it the obvious way — an inline
`(coerce arg 'string)` per argument — was rejected then and is still rejected:
it plants two loops per argument at every `concatenate 'string` site, and
`.kb/wasm-function-body-size.md` is why one emitted body must not grow without
bound. What landed instead is the alternative that file named: ONE cheap helper,
called once per argument.

- **`%seq-string`** (`LispNames.SEQ_STRING`, a `cl` internal) is
  `(lambda (x) (if (stringp x) x (coerce x 'string)))` — a
  `BuiltinFunctionWrappers` entry, i.e. an ordinary injected defun, so NO backend
  needed a new primitive: the `coerce` loop is emitted once, inside it. The fast
  path is a single `stringp` test. The interpreter has the equivalent Java
  builtin, and its `concatenate` walks elements directly.
- **The injection is gated** on `ConcatenateForms.needsSeqString(program)`: true
  when the PROGRAM ITSELF writes a `(concatenate 'string ...)` with an argument
  that is not a literal string. The flag rides on `Ctx.usesSeqString` in both
  compilers (and must be copied by `WasmAsyncEmit.freshCtx`, which also builds
  the synchronous top level), and `expand` only wraps when it is set. That is not
  an optimization but a correctness constraint on the gate: `LispMacroExpander`
  emits `(concatenate 'string ...)` of its own during CODEGEN — `format`,
  `with-output-to-string`, the string-stream builders — long after the scan, and
  those operands are strings the expansion just built. Wrapping them would call a
  helper the gate did not inject; not wrapping them keeps every program that
  never wrote a widening `concatenate` byte-identical.
- `#'concatenate`'s wrapper spells the same contract inline
  (`(if (stringp x) x (coerce x 'string))` inside its fold) rather than calling
  `%seq-string`, because its own injection is gated separately.
- `--no-gc` is unchanged: `NoGcWasmCompiler.compileConcatenate` builds strings in
  linear memory and never goes through `expand`.

## `#'concatenate` as a first-class value

`BuiltinFunctionWrappers.concatenateWrapper` is a
`(lambda (type &rest seqs) ...)` in `REFERENCE_GATED_FUNCTIONS`, so it is
injected only when the program takes `(function concatenate)` — ordinary programs
stay byte-identical. Its result type is a RUNTIME value, so the family dispatch
is re-done with `member` over the designator (its `car` for a compound spec) and
mirrors `expand` arm for arm: `%string-concat` fold for strings,
`(coerce x 'list)` accumulation for list/vector, an `error` for anything else.
This is what makes ironclad's HKDF
`(apply #'concatenate '(vector (unsigned-byte 8)) blocks)` work
(`.kb/asdf.md`).

Its vector arm honours the PACKED element type too (todo-262): `(cadr type)` is
compared to each of the three `(unsigned-byte N)` lists with `equal`, and a hit
calls `%seq-int-vector` instead of `(coerce ... 'vector)`. One test per width
covers every head without reading the spec's shape — a `(simple-vector 41)` size
sits in the same slot but can never be `equal` to a two-element list. This is
deliberately NOT the alias-awareness gap above: an element type is spelled
literally at the call site (http-body's own
`(apply #'concatenate '(simple-array (unsigned-byte 8) (*)) ...)`), and a
designator that means a packed vector in call position must not mean a general
one through `apply`.

## `coerce` re-uses the same runtime dispatch

`coerce`'s own result type may be computed too (`(coerce seq type)` with `type`
in a variable — `alexandria:copy-sequence`/`coercef`/`median`). It was FLOAT-ONLY
until `.todo/219`; `LispMacroExpander.expandComputedCoerce` now dispatches on the
designator's head over the same families this file describes, with each arm being
the SAME body the literal path emits (`coerceToListBody`/`coerceToVectorBody`/
`coerceToStringBody`, extracted for exactly that reason), plus `t` as the
identity. So a computed result type can never mean something a literal one does
not. The shape is `concatenateWrapper`'s above, not a second design.

## `--no-gc`

`NoGcWasmCompiler.compileConcatenate` builds strings in linear memory and that
backend has neither cons cells nor a general array type, so a non-string family
is now a compile error naming the offending designator instead of silently
producing a string (`.kb/no-gc-scalar-wasm.md`).

## Pinning

`ci-spec.yaml` `concatenate-result-families` (all four backends, including the
mixed-sequence string family) and `concatenate-packed-element-type`,
`LispEvaluatorTest#evalConcatenate*` (incl.
`evalConcatenateResolvesADeftypeAliasResultType`, both deftype shapes,
`evalConcatenateKeepsThePackedElementType`,
`evalConcatenateAliasResultTypeKeepsThePackedElementType` and
`evalSeqIntVectorHelper`),
`JvmLispCompilerTest#compileAndRunConcatenate*` (incl.
`compileAndRunConcatenateWithADeftypeAliasResultType`,
`compileAndRunConcatenateKeepsThePackedElementType` and
`compileAndRunConcatenateAsAFunctionValueKeepsThePackedElementType`) +
`compileConcatenateWithComputedResultTypeFails`,
`WasmLispCompilerIntegrationTest#concatenateBuildsListAndVectorResultTypes` +
`#concatenateResolvesADeftypeAliasResultType` +
`#concatenateKeepsThePackedElementType`,
`IroncladE2eTest` (the HKDF vector, end to end on four backends), and the
`LackEcosystemE2eTest` lack legs (fast-http's `'simple-byte-vector`, the
parameterized shape end to end).
