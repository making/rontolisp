# Tighten the CL loop `loop-finish`/`it` symbol comparisons to `.equals`

Raised 2026-07-22 as a hygiene follow-up to `.todo/157` candidate 2. Under A2 the reader
upcases every source symbol, so `equalsIgnoreCase` against a lowercase literal is
equivalent to `.equals` against the upcased literal. Two sites in `LispMacroExpander`
still use `equalsIgnoreCase` on CL loop symbol names:

- `LispMacroExpander:1910`: `head.name().equalsIgnoreCase("loop-finish")` -- the loop-body
  scanner that rewrites `(loop-finish)` inside a `loop` body into the block-exit form.
- `LispMacroExpander:1916`: `s.name().equalsIgnoreCase("it")` -- the anaphoric `it`
  substitution in loop forms.

Both should collapse to `.equals("LOOP-FINISH")` / `.equals("IT")` -- byte-identical
behavior under A2 (source `loop-finish` / `it` reads as `LOOP-FINISH` / `IT`).

**Priority: LOW -- pure hygiene, nothing is broken.**

## Scope

These are NOT case-tolerance seams the `keywordMatches`/`foldKeyword` refactor targeted
(they compare general symbol names, not keyword-argument canonicals). They are the same
KIND of dead case tolerance under A2, though.

The other residual `equalsIgnoreCase` sites are OUT OF SCOPE:

- `LispMacroExpander:6609` and `LispEvaluator:1225` -- condition-slot base-name
  reconciliation. Documented as a REMAIN seam in `.kb/reader-case-upcase.md`: a Java-side
  caller spells a built-in condition slot lowercase (`conditionSlotValue` passes
  `"format-control"`), while an upcase-read condition registers `FORMAT-CONTROL`.
  Removing the tolerance here needs the Java-side call sites upcased too, which is a
  separate follow-up if ever wanted.
- `LispMacroExpander:11901` -- `intern designator :keyword` package-name string
  designator. Accepts `"keyword"` / `"KEYWORD"` per CL convention; not a keyword-symbol
  comparison. Keep.
- `Features:81` -- `#+FOO` / `#+foo` reader feature matching. CL convention. Keep.
- `JvmFetchRuntimeBuilder` / `JvmStringEqCompiler` -- Java `String.equalsIgnoreCase`
  method calls emitted into compiled bytecode. Independent.

## Validation

`./mvnw test` (JVM suite + Docker WASM integration) should stay green byte-identical;
the change is a compile-time symbol comparison in a shared macro path. A native
`CiSpecE2eTest` re-run is nice-to-have but not strictly needed (the case tolerance is
unreachable from source under A2, so the loop bodies that exercise it must already have
had them written upcased).
