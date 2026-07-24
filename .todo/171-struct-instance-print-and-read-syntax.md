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

## Remaining work (start here in the new session)

The primitives exist and are proven on all three backends; nothing calls them yet.
The rest is the atomic flip plus reading and docs.

### Phase 2 -- flip defstruct/defclass/conditions onto the object type (ATOMIC, one commit)

The expander is shared by all backends, so the switch must be one change. Rewrite in
`LispMacroExpander` (all sites already located):

- **defstruct** (`expandDefstruct`, ~6170): constructor `(list '%struct-NAME v...)` ->
  `(%obj-new '%struct-NAME v...)` (keyword and BOA); predicate `(if (consp o) (equal
  (car o) 'tag) nil)` -> `(%obj-is o 'tag)`; copier `(copy-list o)` -> `(%obj-new 'tag
  (%obj-ref o 0) ...)`; accessor `(nth i o)` -> `(%obj-ref o (i-1))`; the
  `structAccessors` setf position must now emit `%obj-set` (check `expandSetf`'s struct
  branch).
- **defclass** (`expandDefclass`, ~6442): constructor `(list '%class-NAME v...)` ->
  `%obj-new`; reader/writer methods -> `%obj-ref`/`%obj-set`.
- **tag tests** -> `%obj-is`: `instanceTagTest` (~6960), `makeClassInstanceTest`
  (~11849), `makeAnyClassInstanceTest` (~11829), the struct specializer (~7822/7841),
  the `class-of` dispatch (`expandClassOf` ~6745, currently `(car v)` after the tag
  test -> `%obj-tag`), `expandSlotValue`/runtime slot dispatch (position walks -> `%obj-ref`).
- **DELETE** the cons/list/sequence specializer exclusion hack (~7841 +
  `makeAnyClassInstanceTest`'s `consp` guard) -- with `consp` nil it is dead.
- **conditions** (all AST, all in `LispMacroExpander`): `buildTypedConstruct`,
  `expandObjectSignal` ctors (~10117/10125), the literal-control signal (~9857), and
  the message builder `(subseq (prin1-to-string (car cond)) 7)` (~10061) -> `%obj-tag`
  minus prefix. `SIMPLE_CONDITION_TAGS` tag tests -> `%obj-is`.
- **synthesizeSimpleError** (`LispEvaluator.java:3658`) -> build a `LispInstance`.
- **handler-case condition construction**: `JvmHandlerCaseCompiler.java:298-303` and
  `WasmHandlerCaseCompiler.java:297-303` build `(list '%class-simple-error msg nil)` as
  AST -> `(%obj-new '%class-SIMPLE-ERROR msg nil)` (a few lines each, no emitter work).
- **`consp`/`listp`/`atom`/`typep 'cons/'list/'sequence`** on every backend: add the
  `arr[0] instanceof String[]` (JVM) / `ref.test $instance` (WASM) exclusion so an
  instance is not a list; interpreter `isCons`/`isList` reject `LispInstance`.
- **`equal`/`equalp`/`_hash`**: interpreter `LispInstance.equals` is already structural
  (tag + slots). Give the JVM `_equal` and the WASM `_equal`/`_hash` matching instance
  arms so `(equal p1 p2)` agrees across backends AND stays consistent with the
  printed-key hash tables. DECIDE and record: keep `equal` structural (today's
  behaviour) rather than switching to CL's "distinct structs are not `equal`", which
  would silently change loaded libraries -- `.kb` re-eval trigger.
- **class-of** keeps returning the `%class-NAME` tag SYMBOL (do not change the printer's
  symbol branch) -- ci-spec `rontolisp-package-introspection` / the three backend tests
  stay green.
- **gray.lisp:23** `(consp stream)` and **usocket.lisp:55** `(nth 1 c)`: rewrite to an
  instance-aware predicate / `%obj-ref` (or a slot reader).
- **json.lisp** `%json-out-instance` / `%json-out` cond order: `(car v)` -> `class-of`
  via `%obj-tag`, `(slot-value ...)` walk stays through `%class-slot-defs`; retire the
  "instance IS a tagged cons" comment. A struct now serializes as a JSON OBJECT (was
  `["%struct-POINT",1,2]`); extend `%class-slot-defs` to answer for struct designators;
  add a `JzonE2eTest` struct case; update `.kb/json.md`.
- **Move the pinned expectations in the SAME commit**: `LispEvaluatorTest` condition
  prints (`(%class-...)` -> `#<...>`), `doc/{en,ja}/reference/macros/make-condition.md`
  (`; =>` line), and anything printing a condition. NOTHING that does not involve an
  instance may change output.

Risk watchlist: JVM printer `max_stack` literals are hand-written and StackMapAugmenter
copies them verbatim (VerifyError only shows at RUN, not compile) -- run every new JVM
test. WASM StringTable ordering trap. The emit gate must be "program defines a struct/
class or uses conditions", never `closRegistry.classes().isEmpty()` (21 seeded classes,
never empty).

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
