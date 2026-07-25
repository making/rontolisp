# A struct instance neither prints nor reads as `#S(...)`

`(make-point :x 1 :y 2)` prints as `(%STRUCT-POINT 1 2)`, and `#S(POINT :X 1 :Y 2)`
in source is a read error. Both are documented as out of scope, but nothing about
the design forbids them.

## Decision (settled with the repo owner, 2026-07-24)

The chosen shape is the **real instance object type**, not the tagged list and not a
self-describing plist. Instances become a first-class value (`consp`/`listp` = nil),
which is the CL-faithful endgame and unifies printing, reading, `class-of`, jzon and
CLOS. The owner also opted **in** to runtime `read`/`read-from-string`/`load` of
`#S(...)` (scope item 3) in the same pass.

Two of the three blockers `.todo/171` originally cited against the object type do NOT
survive inspection: both handler-case compilers hand an AST to the ordinary
expression compiler (a small AST edit, no bytecode/wasm work), and `%class-slot-defs`
takes a class DESIGNATOR SYMBOL so jzon's stringify is representation-independent. The
one real blocker -- `consp` on an instance is documented and used (`gray.lisp:23`,
`usocket.lisp:55`, the ci-spec/`DocExamplesTest`-pinned `(nth 1 e)` on a caught
condition) -- is exactly what the owner's approval covers. Retiring `consp` on
instances is IN scope; those call sites are rewritten in the flip.

### Corrections to the original premises (verified against `target/rontolisp`)

- The printed tag prefix is LOWER-case: `(%struct-POINT 1 "hi")`, not `(%STRUCT-...)`.
- `#S(...)` is not a read error today but a SILENT MISREAD: `(print '(a #S(P :X 1) b))`
  prints `(A #S (P :X 1) B)`. Landing the lexer branch closes a wrong-data hole; no
  program can rely on a diagnostic.
- A duplicate `#S` slot is leftmost-wins (CLHS 3.4.1.4), NOT a read error. An unknown
  slot is a program-error from the constructor, not a reader-error. Only an unknown
  struct NAME and an odd arg list are genuine reader errors.
- No ci-spec `defstruct-*` case prints an instance, so those expectations do NOT move;
  new pinning cases are ADDED instead.
- The reader upcases, so a source-written `'%struct-POINT` reads as `%STRUCT-POINT`
  and can never forge an instance -- tests spell tags with the `|...|` escape.

## Progress

### DONE -- Phase 0 (layouts, no behaviour change)

- `LispLayout` (record: `tag`, `printName`, `kind` STRUCT|CLASS, `slotNames`,
  `initforms`) + `LispLayout.java`.
- `ClosRegistry`: `layoutsByTag` map, `registerStruct(name, slotBaseNames, initforms)`
  (was `registerStruct(name)`), `registerClass` now also bakes a layout, and
  `findLayoutByTag` / `findStructLayout` / `findClassLayout` / `layouts()`.
- `expandDefstruct` moved the `registerStruct` call below the slot loop so the layout
  carries the ordered slot names + initforms.
- Fixed a real crash first (failing test then fix): `(defstruct empty)` threw a
  `ClassCastException` on an empty `&key` list -- `LispEvaluatorTest#defstructWithNoSlotsBuildsAnInstance`.
- Deleted the dead `expandTopLevelDefstructs` and its 2-arg `expandDefstruct` overload;
  the live entry point is `expandTopLevelDefinitions`. Fixed the two `.kb` references.

### DONE -- Phase 1 (the object type + the six primitives + `#S`/`#<` printing, all UNUSED by the expander yet)

- `LispInstance` (layout + `LispVal[]` slots) added to the `LispVal` sealed `permits`;
  self-evaluating arm in `LispEvaluator.eval`. Prints `#S(NAME :SLOT v ...)` /
  `#<NAME :SLOT v ...>`; slot VALUES follow the ambient escape, the `#S`/`:` frame is
  literal in both modes (CLHS 22.1.3.12, matches SBCL).
- Six `%`-internal primitives (`LispNames.OBJ_NEW/REF/SET/IS/TAG/P`, all in
  `PackageRegistry.CL_INTERNALS`), implemented on ALL THREE backends:
  - Interpreter: `LispFunction`s in `LispEvaluator` (search `LispNames.OBJ_NEW`).
  - JVM: `JvmObjCompiler` + `JvmLispCompiler.LayoutPool` (one `private static String[]`
    layout field per referenced tag, interned on demand, filled in a `<clinit>` merged
    with the condition channel's; `max_stack` recomputed). Instance =
    `Object[]{String[] layout, v1..}`, discriminated by `arr[0] instanceof String[]`.
    `_instToString`/`_instToDisplayString` in `JvmRuntimeBuilder`, gated by `InstPrint`.
  - WASM-GC: `WasmInstanceCompiler` + `WasmInstanceLayouts` (a linear-memory layout
    blob, bake-ALL). New `TYPE_INSTANCE` `{const i32 layoutAddr, mut eqref slots}` in
    its own rec group, added behind `usesInstances` via the conditional-index trick
    (`instanceTypeBase()`), moving no existing type or `FUNC_*` index. `emitPrintInstance`
    inlined into both printer bodies. `--no-gc` out of scope (rejects defstruct).
  - `LispInstance` quote/self-eval literal arms on both compile backends (for later
    `#S(...)` literals).
- `%obj-tag` produces an eq-stable symbol (JVM: the tag String; WASM: `_str_build` on
  the interned offset). `%obj-is` compares tags (JVM) / layout addresses (WASM).

**Verified:** full suite 4174 green. Byte-identity confirmed for instance-free
programs across `.class` (default/`--optimize`/`--dynamic`) and `.wasm`
(default/`--simd`/`--component`/`--optimize`), INCLUDING programs that use
defstruct/defclass/handler-case (the expander still emits the tagged list, so nothing
is wired to the primitives yet). Four-backend shape matrix (`#S`/`#<`, string/nil/
nested/in-list/empty/no-slot, princ/prin1/format `~a`/`~s`/`*-to-string`) prints
byte-identically on interpreter + JVM + WASM-P1 + `--component`.

Design notes worth keeping: WASM bakes ALL registered layouts (not demand-interned
like the JVM) because a tag a program uses only becomes known during Pass 2 -- a
`make-instance`/`error` deep in a body expands then -- while `%obj-new` needs the
address as an `i32.const` before Pass 2a. Record this asymmetry in `.kb` when it
lands. See the memory note `instance-object-type-migration`.

### DONE -- Phase 2 (the atomic flip; every instance now goes through the six primitives)

Everything the checklist below listed, plus what it did not anticipate:

- `LispMacroExpander` grew five private builders (`objNew`/`objRef`/`objSet`/`objIs`/
  `objTag`) and EVERY site was rewritten through them -- defstruct, defclass, the setf
  struct place, `class-of`, `slot-value`/`slot-boundp` (literal + runtime), the condition
  slot readers, `buildTypedConstruct`, `expandStringSignal`/`expandObjectSignal`,
  `makeHandlerTypeTest`, the class/struct instance tests. The cons/list/sequence
  specializer exclusion hack is DELETED.
- `(setf (%obj-ref o k) v)` is a setf place of its own, so slot-value / with-slots / a
  struct accessor all compose with setf/incf/push through one case.
- `class-of` became `(if (%obj-p v) (%obj-tag v) ...)`: no tag enumeration to keep in
  step with the registry, and it answers for a STRUCT too (`%struct-NAME`).
- `ClosRegistry.slotDefs(designator)` is the one resolver behind `%class-slot-defs` on
  both the interpreter and the compile path, and it answers for STRUCT designators (all
  types read `T`) -- which is what makes json.lisp serialize a struct as a JSON object
  through the unchanged CLOS walk, and what `typep 'structure-object` (new) tests.
- Runtime-name `slot-value` was rewritten: names at a consistent index share one `member`
  arm, an AMBIGUOUS name now gets an inner `%obj-is` tag dispatch instead of the old hard
  error. `slot-boundp` dispatches over layouts (structs included).
- `equal` kept STRUCTURAL on instances (decision recorded in `.kb/instance-syntax.md`
  with its re-evaluation trigger); JVM `_equal` and WASM `_equal`/`_hash` gained matching
  arms. `equalp` (prelude) descends slot-wise via `%obj-p`/`%obj-tag`/`%class-slot-defs`.
  The JVM hash table keys by printed text, so it agrees for free.
- The emit gate is `LispMacroExpander.mayCreateInstances(program, registry)` -- "can an
  instance be CONSTRUCTED here". Only construction needs it; the four reading primitives
  fold to a constant nil when it is off. It must stay in step with
  `expandSignalDesignator`'s case split (handler-case/ignore-errors/signal/make-condition,
  and error/warn/cerror with a quoted type, a literal make-condition datum or -- for
  error -- a runtime datum with initargs), plus `#'signal`.
- Bug the flip exposed and fixed: `buildTypedConstruct`'s non-pair fallback tagged the
  instance with the QUOTED spelling (`%class-JSON-WRITE-ERROR`) while the layout is
  registered under the resolved one (`%class-COM.INUOE.JZON:JSON-WRITE-ERROR`) -- it now
  uses `cls.name()` whenever the class is known. jzon's `(error 'json-write-error
  :format-control c :format-arguments a)` (odd arg count -> not pairs) hit exactly this.
- `expandObjectSignal`'s fallback message now checks `%obj-p` first, so `(error x)` on a
  non-condition signals x's printed form instead of running off the end of a nil tag.
- gray.lisp dispatches on `%obj-p` (the interpreter wrapper matches: any instance);
  usocket.lisp reads `simple-condition-format-control`; `(nth 1 e)` on a caught condition
  is retired everywhere (tests, ci-spec, doc/en+ja handler-case).

- A SEVENTH primitive landed that the checklist did not foresee: `%obj-slots` (a fresh
  list of an instance's slot values). It exists for `equalp`, which must descend into
  slots as CL does and as the tagged list did by accident. Walking them through
  `%class-slot-defs` + a runtime-name `slot-value` was exact but cost **+19 KB in every
  artifact that so much as mentions `equalp`**; `(equalp (%obj-slots a) (%obj-slots b))`
  reuses the existing cons arm for **+63 bytes**. Trap it exposed: the JVM instance array
  carries the layout at index 0 while the WASM slot array does not, so the JVM cursor
  must stop at 1 -- getting that wrong returned the `String[]` layout as a list element
  and made `equalp` recurse forever.
- Both backends now ASSERT the gate on construction (`%obj-new`/`%obj-set`, and a
  Pass-2 instance literal): a gate that under-approximates used to be silent on the JVM
  (`consp` would answer T for an instance it never knew about); now it is a loud
  compile error, as it already was on WASM.

**Verified:** all four backends print `#S(...)`/`#<...>` byte-identically for the whole
shape matrix (string/nil/nested/in-list/empty slot, princ/prin1/`format ~a`/`~s`/
`prin1-to-string`/`princ-to-string`/`write-to-string`) and agree on `consp`/`listp`/
`atom`/`typep structure-object|standard-object`/`equal`/`equalp`/`class-of`/an
instance-keyed `equal` hash table. New ci-spec case `instance-print-syntax-and-identity`.
Full suite 4180 green; `-Pweb compile` green; `javadoc:jar` clean apart from the
pre-existing `Version` error; native-binary `CiSpecE2eTest` 932 green.

**Byte-identity caveat (report, do not "fix"):** an instance-FREE program is no longer
byte-identical when its (spliced) code merely INSPECTS an instance -- `class-of`, a
`typep 'standard-object`, an `(error <runtime-datum>)` object designator. Those expand to
`%obj-*`, which folds to a constant with the gate off, so the artifact SHRINKS (~1.3% on
a hello-world) with identical behaviour. Programs that touch none of those are unchanged.

## Remaining work

### Phase 3 -- read `#S(...)` from source

`Token.StructOpen` + a `#S(`/`#s(` lexer branch beside `#(`; `LispReader.readStruct`;
a NON-cons `LispStructLiteral` carrier (survives quote/backquote/`#(` with zero special
cases, unlike a `%read-eval`-style marker). Fold `LispStructLiteral` -> `LispInstance`
against `ClosRegistry`: on the compile path inside `expandTopLevelDefinitions` (per
form, so the `defstruct` must precede the `#S`; reached by the browser playground --
NOT `UserMacroExpander`/`cli`); in the interpreter a `resolveStructLiterals` sibling of
`resolveReadTimeEval` at the two top-level `eval` entries. CL rules: leftmost-wins
duplicates, odd/unknown-name = reader error, values read as DATA, absent slot = its
recorded initform (constant, else a clear error). Pin: top level, `'(...)`, single- and
nested-backquote, `#(...)`, `#s` lowercase, qualified name, `#S(EMPTY)`, `#S` under a
failing `#+`. Correct the todo's duplicate-slot claim in the same commit.

### Phase 4 -- runtime `read`/`read-from-string`/`load` of `#S(...)`

Not "do nothing" -- once the lexer has `#S(`, interpreter runtime `read` would return
an unfolded literal. Interpreter: post-process `read`/`read-from-string` through
`foldStructLiterals`. Compile backends: give the emitted readers
(`JvmReadRuntimeBuilder`, `WasmReadRuntimeBuilder`) a syntactic `#S` branch producing a
`(%read-struct NAME :K v ...)` marker, canonicalized by one generated `%struct-canon`
Lisp defun (the `%class-slot-defs` pattern) -- no slot table in the emitted reader. Same
error set on every backend; add a ci-spec case.

### Phase 5 -- docs, `.kb`, ci-spec, native E2E

Phase 2 already carried the parts its own change invalidated: `.kb/instance-syntax.md`
(new, indexed in `.kb/README.md`), the representation paragraphs of `.kb/{defstruct,clos,
error-handling,json,gray-streams}.md`, `doc/{en,ja}` for defstruct.md / defclass.md /
make-condition.md / handler-case.md / missing-features.md, and the ci-spec case. What is
LEFT here is the reading half (data-types.md's `#S(...)` reader literal,
eval-limitations.md) plus `.kb/hash-tables.md`, and the final `-Pweb compile` /
`javadoc:jar` / native `CiSpecE2eTest` sweep.

`doc/{en,ja}` mirrored byte-identically: defstruct.md, defclass.md, make-condition.md,
missing-features.md (narrow the row to `:include` only), data-types.md (`#S(...)` reader
literal), eval-limitations.md. New `.kb/instance-syntax.md` (normative text contract,
the object model, the WASM `TYPE_INSTANCE` disjointness + pinning test, the JVM
discriminator, the WASM bake-all vs JVM demand-intern asymmetry, the settled-vs-interim
note the todo asks for: printed/read SYNTAX settled, and now the VALUE MODEL is the real
object type). Update `.kb/{defstruct,clos,error-handling,json,hash-tables,gray-streams,
README}.md`. New ci-spec cases for the whole shape matrix (all four backends incl.
`--component`). `./mvnw -Pweb compile`, `javadoc:jar`, then the native binary
`CiSpecE2eTest` (not just `./mvnw test`).

## Verification bar (unchanged)

All three defstruct-capable backends print byte-identical `#S(...)`/`#<...>` for a
string slot, a nil slot, a nested struct, and a struct inside a list -- plus `princ`,
`prin1`, `format ~a`/`~s`, `print`. `#S(...)` in source, quoted, and backquoted. Run
the native-binary `CiSpecE2eTest`. Nothing that does not involve a struct or a CLOS
instance may change its printed output.
