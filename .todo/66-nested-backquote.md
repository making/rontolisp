# 66: Nested backquote in the reader

## Status: DONE (2026-07-05)

Implemented a faithful port of the CLtL2/Steele Appendix C backquote algorithm
in `LispReader` (`bqCompletelyProcess` = `bqProcess` + `bqSimplify` +
`bqRemoveTokens`, over identity-compared sentinel markers). `readBackquote`
first raw-reads the template (`readRawTemplate`) to detect an inner `` ` ``;
non-nested templates keep the optimized single-level expander (all existing
output shapes unchanged, no test churn), nested ones go through CLtL2. Every
quasiquote level is expanded at read time to `list`/`cons`/`list*`/`append`/
`quote` -- no runtime marker survives, so the evaluator and all four backends
are unaffected. One deviation from CLtL2: an inner backquote that escapes to
level 0 (inside a comma argument) is expanded in place by `bqExpandEscaped`
rather than left as a live `backquote` macro call, because our runtime has no
backquote.

Verified against SBCL 2.6.5: `once-only`'s `square` expansion is structurally
identical, single/double/triple nesting match, and `(square (bump))` guards
against multiple evaluation on interpreter + JVM + WASM Preview 1 + WASM
component. Tests: `LispReaderTest` (`readNestedBackquote*`, `readTripleNested*`),
`LispEvaluatorTest#defmacroNestedBackquoteOnceOnly`,
`JvmLispCompilerTest#compileAndRunNestedBackquoteOnceOnly`,
`WasmLispCompilerIntegrationTest#nestedBackquoteOnceOnly`, ci-spec
`nested-backquote-once-only`. Docs: `defmacro.md` + `missing-features.md` (en+ja).

Follow-up handed back to `.todo/65`: the REAL `once-only.lisp` also calls
`(string name)`, and rontolisp has no `string` builtin -- added to the 65
stdlib-residue list.

---

Split out of `.todo/65` (cl-utilities): `once-only.lisp` uses three levels of
backquote

```lisp
`(let (,@(loop ... collect `(,g (gensym ,(string name)))))
   `(let (,,@(loop for g in gensyms for n in names
                   collect ``(,,g ,,n)))
      ,(let (...) ,@body)))
```

and our reader rejects it: `LispReader.readTemplateElement` throws
"Nested backquote is not supported" on an inner `` ` ``. This blocks the
whole-system `asdf:load-system :cl-utilities` (once-only is a component that
`extremum` depends on, and load-system reads every component).

## Scope

- Read-time expansion only. Backquote is expanded entirely in the reader
  (`LispReader.readBackquote`/`readTemplateElement`/`readTemplateList`), so the
  evaluator and all four backends are UNAFFECTED -- the change is confined to the
  reader and its tests (`LispReaderTest`, `LispLexerTest`).
- Implement the CLtL2/Steele nested-backquote algorithm: an inner `` ` ``
  increments the quasiquote level; `,`/`,@` decrement it; only unquotes that
  bring the level back to 0 are evaluated in the outer expansion, deeper ones
  are re-quoted. The current single-level expander must become level-aware
  instead of erroring on a nested `` ` ``.
- Watch the interactions already handled at level 1: dotted templates
  (`readTemplateList`), `,@` position checks, `'x`/`#'x` inside templates
  (`readWrappedTemplate`), and constant `quoteIfSymbol` data.

## Verify

- `once-only.lisp` reads and its `once-only` macro expands correctly (compare a
  known expansion, e.g. SBCL's, structurally).
- All four backends (backquote is frontend-only, but confirm a nested-backquote
  macro used in a compiled program still works end-to-end).
- Then return to `.todo/65` for the cl-utilities stdlib residue.
