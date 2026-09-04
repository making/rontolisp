# The interpreter converts a sequence in Java, not through an interpreted `map`

**Invariant: on the INTERPRETER, `(coerce x 'list)` / `'string` / `'vector` is a
Java conversion over the value's own buffer for every representation it can
answer identically, and DECLINES to the shared `expandCoerce` lowering for
everything else. The compile paths keep the expansion; both must answer the same
thing, including the shapes the fast arm declines.** A COST invariant.

## Why the conversion, not any operator

- Every generic sequence lowering funnels through two `LispMacroExpander`
  builders: `seqAsListForm` (a literal `(coerce seq 'list)` form) and
  `seqResultDispatchForm` (also rebuilds the result via `(coerce res 'string)`).
  `expandCoerce`'s list arm for a string is `(map 'list #'identity s)` -- an
  INTERPRETED funcall per character for every caller: `find`, `count`, `remove`,
  `substitute`, `reduce`, `every`/`some`, `remove-duplicates`, the `sort` string
  wrap (`remove`/`substitute` twice, both directions).
- `position`/`find` and their `-if` variants do not coerce at all:
  `buildPositionScan` walks a LIST through its cons cursor and scans a
  VECTOR/STRING by index against a length bound -- no allocation, early exit on a
  hit. Trade: SITE BYTES (a wasm `position` site ~1,449 vs 591), budgeted by
  `WasmLispCompilerTest.\
aSequenceOperatorSiteDoesNotCarryItsOwnCopyOfTheSharedConversions`.
- `position` keeps the non-sequence-answers-nil oddity via its `vectorp` guard;
  one edge moved -- over a RANK >= 2 array it answers nil instead of signalling
  through `length`.

## The `coerce` seam

`LispEvaluator.evalCons`'s `COERCE` case calls `evalSequenceCoerce`, the
declining-primitive shape of `.kb/binary-sequence-io.md`, in Java:

1. `coerceSequenceTypeName` reads the result type SYNTACTICALLY, normalized as
   `expandCoerce` does: qualifier dropped,
   `simple-string`/`base-string`/`simple-base-string` -> `STRING`,
   `simple-vector` -> `VECTOR`. Only `LIST`, `STRING`, `VECTOR` answer; a
   computed designator, compound spec, float type or unresolvable deftype name
   answers null and goes to the expansion.
2. The value form is evaluated ONCE, then `coerceSequenceFast` answers or
   declines with null.
3. A decline re-runs the expansion over that value, re-quoted as
   `(coerce (quote <value>) <typeform>)` -- one evaluation either way, the
   expansion's answer (error, oddity, identity) unchanged.

- Packed `(vector (unsigned-byte N))` types are unaffected:
  `ConcatenateForms.packedVectorCoerce` runs first
  (`.kb/concatenate-result-families.md`).
- `sequenceElementsAsList` is the converter: `LispString` by CODE POINT
  (`codePointCount`/`codePointAt` -- a supplementary character is ONE element),
  rank-1 `LispArray` over `effectiveLength()`/`readFlat` (fill pointer bounds it,
  displaced view reads through), rank-1 `LispFloatArray` over
  `totalSize()`/`readFlat`, `LispIntVector` over `length()`/`elementAt` -- what
  `(aref v i)` over `(length v)` answers, element for element.
- `Environment.seqAsList` (the same conversion for natively-registered
  `#'position`, `reverse`, `count`, ...) reads the buffer slot by slot, not by
  rebuilding the Java `String` through `value()` (`.kb/string-index-cost.md`).

Each arm reproduces its `expandCoerce` body exactly, oddities included:

- **`'list`** -- `LispCons`/`LispNil` is itself (the `(listp x)` arm, dotted
  included); the four vector representations convert. Else DECLINES: a rank-2
  array (whose `(length ...)` signals `not a sequence`) and a non-sequence, for
  which the expansion answers **nil** (its `(length x)` falls through to the cons
  walk) -- wrong, but what the operator has always answered.
- **`'string`** -- `LispString` is itself; a list or converted vector of all
  `LispChar` becomes a string. A NON-character element declines and the
  expansion's `(map 'string #'identity ...)` answers as before:
  `(coerce '(1 2) 'string)` is `"12"`.
- **`'vector`** -- a list or string fills a fresh rank-1 `LispArray`; anything
  else is the IDENTITY (as `coerceToVectorBody`'s else arm), so this arm never
  declines: `(coerce 5 'vector)` is `5`, a packed float array is itself.

No primitive was added to the compile paths: it would cost every wasm module
bytes for a problem they do not have (`.kb/sequence-op-runtimes.md`).

## `search` and `mismatch` are a different defect

Both are Lisp-source prelude `defun`s (`LispPreludeLibrary.SOURCES`,
`LispNames.SEARCH`/`MISMATCH`) on every backend; neither uses `seqAsListForm`.
They index both operands with `(elt seq i)` and compare with
`(funcall test ...)` -- two `elt` calls plus a funcall per element PAIR, times
O(n*m). `LispEvaluator.evalConsRareOperator` has a `SEARCH`/`MISMATCH` case
calling `evalSequenceScan`: resolve the function, then evaluate the arguments
(that order), with `SequenceScanFast` in front of the `apply`. A decline applies
the same function to the same values -- evaluation order, count and every error
unchanged. Served only where identical answers are provable:

- **The comparison must be `eql`**: absent `:test` (prelude default), explicit
  `#'eql`, or `#'char=` when BOTH operands are strings. `:key` non-nil, a user
  function, a lambda and explicit `:test nil` decline.
- **Both operands must be sequences `(length x)` measures the way the arm does**:
  a string (by code point), a PROPER list (materialized once into a `LispVal[]`,
  removing a two-list `search`'s O(n^2*m)), a rank-1 `LispArray`
  (`effectiveLength`/`readFlat`), a `LispIntVector`, a rank-1 `LispFloatArray`. A
  dotted list, rank-2 array and non-sequence decline, so the prelude keeps owning
  them -- `(search "ab" 5)` is NIL, not an error, the same `(length x)` oddity.
- **Every bounding index must be inside its sequence, start <= end.** Outside it
  the prelude's answer depends on which `elt` it reaches first
  (`(search "ab" "xab" :end2 99)` is 1, not an error), so the arm never guesses.
  An END of `nil` IS the default (the body or-defaults it); a START of `nil` is
  not (the lambda list binds it, the arithmetic signals).
- **`mismatch` with `:from-end` declines.** The prelude accepts and IGNORES the
  keyword: `(mismatch "abcd" "xbcd" :from-end t)` is 0 on all four backends where
  CLHS says 1 -- a deliberate deviation
  (`doc/{en,ja}/reference/functions/mismatch.md`, "Lite").
- **A user redefinition takes the call back WHOLE.** The lazy loader never loads
  an entry over an existing `(defun search ...)`; the arm serves only while the
  name still resolves to the object the loader installed, recorded in
  `preludeDefinitions` by `LispEvaluator.loadPreludeDefinition` (the single load
  site both callers use). Without it the fast shapes would silently ignore a
  LATER redefinition.
- Nothing added to the compile paths: `search` is 15 bytes a site, the `defun`
  being a shared callee (`.kb/sequence-op-runtimes.md`). A native `Environment`
  built-in was rejected: the prelude loads only when the name is unresolved, so
  that is a REPLACEMENT, not a fast path, and a second keyword implementation.

## The prelude bodies walk a list with a cursor, not with `elt`

`(elt list i)` lowers to `(nth i list)`, an O(i) head walk, so a two-list
`search` was O(n^2*m) and a two-list `mismatch` O(n^2) on the three compile paths
(which run the `defun`). Both bodies seed a cons cursor:

```lisp
(if (consp c) (prog1 (car c) (setq c (cdr c))) (elt seq i))
```

- The `map-into` cursor shape (`LispMacroExpander.readElement`) with the advance
  folded into the read. A non-list operand pins a nil cursor and keeps indexing;
  a cursor run out (past an out-of-range bound, or onto a dotted tail) falls back
  to the very `elt` call the body used to make, answer and error alike.
- `search` seeds the needle cursor once (its `start1`/`end1` window never moves)
  and advances the haystack cursor one `cdr` per OUTER position, both copied into
  the restarting inner walk.
- **The cursor is NOT a `(null cell)` STOP.** `replace`'s array-arm list source
  took one and changed what an invalid call answers
  (`.kb/sequence-op-runtimes.md`); this pair cannot, `SequenceScanFast` having
  DECLINED every out-of-range bound so these bodies keep owning them.
- **Folding the advance into the read is what makes it free for a string.** A
  separate `(if (consp c) (cdr c) c)` step form costs a SECOND `consp` per
  element -- on the declined path (a string; cursor never fires) that is the
  whole regression.

## The rest of the `elt`-per-element family -- five sites, one cursor

| site | ran the quadratic loop | the indexed read |
| --- | --- | --- |
| `lowerInitialContentsMakeArray` (rank 1) | the 3 compile paths | `(elt contents i)` |
| `buildNestedInitialContentsFillLevel` (rank >= 2) | the 3 compile paths | `(elt <level's seq> idx)`, per LEVEL |
| `replaceDispatch`'s list-DESTINATION arm | all four | `(elt source (+ start2 k))` |
| `expandMap`, per operand | all four | `(nth i s)` |
| `Environment`'s native `replace`, source side | the INTERPRETER only | `sequenceRef(source, start2 + k)` |

- The first four take the same read, spelled once as
  `LispMacroExpander.readElementAdvancing` / `cursorRead`; the fallback
  reproduces every answer and error (NIL past a proper list, a signal past a
  dotted tail, a negative index). The fifth is Java,
  `Environment.SequenceSourceCursor`, keeping `sequenceRef` for non-lists.
  **No site took a `(null cell)` stop.**
- **Two of the five are one cursor per something, not per call**: the rank >= 2
  fill binds one per DIMENSION LEVEL, re-seeded on every iteration of the level
  above (each row is a fresh list); `map` binds one per OPERAND, all at index `i`.
- A non-list operand pays an unused `consp` per element; the interpreter's
  `replace` does not (its cursor is a NULL CHECK).

## The last three sites -- three defects, one symptom

| site | defect | fix |
| --- | --- | --- |
| the runtime `format` renderer's argument list | `(nth i all)` per directive, `(length items)` TWICE per `~{` pass | MATERIALIZE, not a cursor |
| the `#'map-into` WRAPPER's store | `(setf (elt r i) v)` into a list destination | the cursor, verbatim |
| `(map 'string ...)`'s accumulator | `%string-concat` of the whole result per element -- quadratic in the OUTPUT | a pairwise JOIN |

- **The renderer is the one place a cursor is the wrong answer**: `~*` moves the
  argument pointer forward, `~:*` backward, `~n@*` absolutely, `~?`/`~{` recurse
  with their own -- RANDOM access, where a monotone cursor needs a re-seed
  costing the walk it removes.
- `all` is the pair `%fmt-args` builds (the list as given plus a vector of it),
  materialized once per rendering LEVEL and only for a PROPER list long enough to
  pay for it, read through `%fmt-arg`/`%fmt-count` whose fallback is the very
  `(nth i list)` / `(length x)` the renderer used to make (`.kb/format.md`, "The
  argument list is a materialized VECTOR"). The iteration loops also collect and
  join once: `(%fmt-cat acc piece)` per pass is quadratic in the OUTPUT even with
  O(1) reads.
- **A premise this corrects:** `(map 'string ... <string>)` over a `make-string`
  source is quadratic in the READ, not the accumulator -- on both compiled
  backends a `make-string` is a mutable character VECTOR whose `(char v i)`
  renders the whole vector per access, a separate defect
  (`.kb/adjustable-arrays.md`, `.kb/geom.md`).
- The renderer's added bytes are paid only by a program that reaches the runtime
  renderer at all (`.kb/format.md`'s gate).
- `%fmt-run`'s `(%fmt-cat out (string (char ctrl pos)))` per character stays
  quadratic in the CONTROL STRING's length: not part of this family.

## Is the family closed? Yes -- the search

`(elt <seq> <i>)` / `(nth <i> <seq>)` with a RUNTIME-sized sequence and a loop
index, everywhere one can be written:

- **shipped Lisp** (`src/main/resources/**/*.lisp`) -- remaining hits are a
  constant index into a record (`http-server.lisp`, `asdf.lisp`, `geom.lisp`'s
  glTF accessors), an axis into `array-dimensions` (`linalg.lisp`, `torch.lisp`,
  bounded by RANK), `usocket.lisp`'s four-octet address, and `geom.lisp:422-423`'s
  `(nth i facet)`, bounded by a polygon's vertex count.
- **the prelude** (`LispPreludeLibrary.SOURCES`) -- the only `elt` forms left are
  the four cursor FALLBACKS in `search`/`mismatch`/`replace`.
- **`LispMacroExpander` lowerings** -- `expandElt`/`array-dimension` are single
  reads; `arityDispatchedCall`, `bindParams`, `WasmArityBundler` are bounded by a
  lambda list's length; every generated sequence loop carries
  `readElementAdvancing`/`cursorRead` or the `readElement`/`advanceCursor` pair.
- **native `Environment`/`LispEvaluator` arms** -- `sequenceRef` has one in-loop
  caller, `SequenceSourceCursor`; every other per-element read is `elementAt` on
  a packed vector or walks a `seqAsList` materialization.
- **`BuiltinFunctionWrappers`** -- the one `(setf (elt r i) v)` left is the
  `#'map-into` wrapper's ARRAY arm, an O(1) store by construction.

## Re-evaluation triggers

- **`coerceSequenceFast` must stay a strict subset of `expandCoerce`, and
  `SequenceScanFast` a strict subset of the prelude `defun`.** If either grows a
  representation, a bound, an argument order or the `:from-end` direction, the
  arm follows or starts declining it in the same commit; the ci-spec cases catch
  the drift, running one program on the interpreter (the arms) and three backends
  that run the expansion / the `defun`. Same rule if `coerceToVectorBody` learns
  to rebuild a packed array (today `LispFloatArray` is served for `'list`,
  identity for `'vector`). Making `mismatch`'s `:from-end` CLHS-correct or giving
  an out-of-range bound a uniform error are prelude-SOURCE changes that move
  every backend at once and lift the matching decline with them.
- **Serving the declined shapes** (`:key`, a user `:test`) means an `apply` per
  element -- a weaker win over a wider agreement surface; measure a consumer.
- **A new loop over a sequence whose representation is a RUNTIME fact must carry
  a cursor from the start** -- a generated `do`/`dotimes`/`while` whose body
  reads `(elt s i)` or `(nth i s)` where `s` can be a list at run time. Fix:
  `readElementAdvancing` (plain `elt` fallback), `cursorRead` (any other
  fallback), the `readElement`/`advanceCursor` pair (a loop with a `do` binding
  for the step) -- or `%fmt-args`'s materialize-once shape when the index does
  NOT advance monotonically.
- **A quadratic ACCUMULATOR is the family's twin and the cursor cannot see it.**
  Any loop whose accumulator step is `%string-concat`/`concatenate` of the
  accumulator is O(n^2) in the OUTPUT; collect and join
  (`joinStringPiecesReversed`, `%fmt-join`). `.kb/map-family.md` worked example.

## Pinning tests

`ci-spec.yaml` cases, all four backends via `CiSpecE2eTest`:
`sequence-coerce-across-representations` (served and declined alike);
`search-and-mismatch-across-representations` (every keyword served, the declined
`:key` / `#'char-equal` / out-of-range `:end2` / `start1 > end1` /
`mismatch :from-end`, and the list-cursor rows);
`make-array-rank2-initial-contents`, `replace-into-a-list`, `map`,
`format-runtime-control-string`, `map-into` -- each extended with run-time
list/vector/string/packed operands, the `'string` result type, the repositioning
directives around the materialization threshold, and long operands.

`LispEvaluatorTest`:
`theNativeSequenceCoerceArmAnswersEveryRepresentationTheExpansionDoes`,
`theNativeSequenceCoerceArmDeclinesEverythingItCannotAnswerIdentically`,
`theNativeSearchAndMismatchArmAnswerWhatThePreludeDefunAnswers`,
`theNativeSearchAndMismatchArmDeclineEverythingTheyCannotAnswerIdentically`
(each case checked against a literal expectation AND against
`(funcall #'search ...)`, which never reaches the arm),
`searchAndMismatchWalkAListWithACursorRatherThanIndexingItWithElt`,
`replaceReadsAListSourceThroughACursorRatherThanIndexingItFromTheHead`,
`theFormatRendererReadsItsArgumentListThroughAMaterializedVectorRatherThanNthPerDirective`,
`theMapIntoWrapperWalksAListDestinationWithACursorRatherThanStoringByIndex`,
`theStringResultOfMapCollectsItsPiecesAndJoinsThemOnceRatherThanConcatenatingPerElement`;
pre-existing `evalCopyTreeAndSearch`,
`coerceConvertsBetweenListVectorAndString`, `coerceAcceptsAComputedResultType`.

`JvmLispCompilerTest`:
`compileSequenceCoerceAnswersTheSameForEveryRepresentation`,
`compileSearchAndMismatchAnswerTheSameAsTheInterpretersNativeArm`,
`compileSearchAndMismatchWalkAListWithACursor`,
`compileAndRunMakeArrayInitialContentsWalksAListWithACursor`,
`compileAndRunMapWalksAListWithACursor`,
`compileAndRunAListIntoAListReplaceReadsItsSourceWithACursor`,
`compileAndRunTheFormatRendererReadsItsArgumentListThroughAMaterializedVector`,
`compileAndRunTheMapIntoWrapperWalksAListDestinationWithACursor`,
`compileAndRunTheStringResultOfMapJoinsItsPiecesOnce`, `compileCoerceConversions`.

`WasmLispCompilerIntegrationTest`, the twins:
`sequenceCoerceAnswersTheSameForEveryRepresentation`,
`searchAndMismatchAnswerTheSameAsTheInterpretersNativeArm`,
`searchAndMismatchWalkAListWithACursor`,
`makeArrayInitialContentsWalksAListWithACursor`, `mapWalksAListWithACursor`,
the list-source rows appended to `replaceIntoAList`, and the last three without
the `compileAndRun` prefix. **The search/mismatch ones must use
`compileAndRunPrelude`, not `compileAndRun`**: without the prelude splice the
module has no `search` at all and traps. Each cursor test carries a 2,000-element operand.

## Related

- [[seq-conversion-runtime]] -- the COMPILE half: the
  `%seq-to-list`/`%seq-to-string`/`%seq-to-vector` trio, shared for SIZE.
- [[binary-sequence-io]] -- the declining-primitive protocol this arm follows.
- [[string-index-cost]] -- reading a string by slot, not through `value()`.
- [[sequence-op-runtimes]] -- `replace`/`fill`/`map-into` (not via `coerce`) and
  the per-site byte budgets.
