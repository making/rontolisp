# A quoted datum is shared on the interpreter and rebuilt on the compile backends

Difficulty: High

Split out of `.todo/578` on 2026-08-29, which closed the same divergence for the
SELF-EVALUATING array literal (`.kb/array-literals.md`) and could not close it under
`quote`. What is left is not array-specific: it is `quote`, and a cons has it as much
as an array does.

## The measurement (2026-08-29, all four backends)

```lisp
(defun ql () '(1 2 3))
(print (eq (ql) (ql)))
(let ((a (ql))) (setf (car a) 99))
(print (ql))
```

| backend | `(eq (ql) (ql))` | the second `(ql)` |
|---|---|---|
| interpreter | `T` | `(99 2 3)` -- the constant in the source is gone |
| JVM class | `NIL` | `(1 2 3)` |
| WASM preview 1 | `NIL` | `(1 2 3)` |

`'#(1 2 3)` behaves exactly the same way, and so does an array nested inside quoted
data (`'(1 #(2 3))`): the interpreter hands back the reader's object, both compile
backends rebuild the whole datum at the site (`JvmQuoteCompiler.compileQuotedCons` says
so in as many words -- "The cells are still fresh at every evaluation -- this moves the
construction, it does not memoize it").

## Why 578 could not take it

`.todo/578` landed "an array literal is fresh at every evaluation" by materializing in
`LispEvaluator.eval`'s self-evaluating array arms. Doing the same in `evalQuote` was
implemented and reverted, because at run time the interpreter ALSO uses
`(quote <value>)` to splice a LIVE value back into a form it then re-evaluates:
`LispEvaluator.quoteValue`, four call sites, one of them
`evalSequenceWithGrayDispatch`'s rebuild. With `evalQuote` copying, `read-sequence`
filled the copy and six `read-sequence`/`write-sequence` tests read back their initial
contents. There are ~15 more `(quote <value>)` constructions across `eval` and `macro`
(`LispEvaluator` 3422/3433/6717/7168/7189, `UserMacroExpander`, `LoadFormSubstituter`,
`AsdfRuntimeLibrary`), so the splice pattern cannot be audited into safety cheaply.

## What to decide first

Same fork as 578's, and the answer is NOT obviously the same one:

- **Fresh per evaluation everywhere** (what 578 chose for arrays). For a cons this is a
  much bigger bill than it was for an array: `'(...)` is everywhere in macro-heavy code
  and the interpreter would deep-copy the datum at every evaluation. It also has to
  respect the cons-IDENTITY rule every AST pass depends on
  (`.kb/source-positions.md`, `SourceProvenance`'s cons-keyed side table) -- copying a
  quoted datum that is later read as AST would lose its provenance.
- **Shared everywhere.** For a cons this is the CL-conformant reading and probably the
  cheap one to state, but it inverts what BOTH compile backends do today and means
  making the two quote compilers memoize -- the JVM has the machinery
  (`JvmLispCompiler.LayoutPool` / `BigIntPool` are the pattern), wasm needs a new
  global-initializer emission shape (`.kb/array-literals.md` records both surveys).
  It would also make `(setf (car '(1 2 3)))` corrupt a shared constant on every backend
  rather than on one, which is what real CLs do and what CLHS leaves undefined.

Measure the interpreter cost of the first before assuming it is unaffordable, and
measure the emitted-size cost of the second (`.kb/optimize-dead-code-elimination.md`
has the harness): a memoized quoted table is FEWER bytes at the site, so the second may
pay for itself.

## The prerequisite either way

**Give the live-value splice its own head.** `quoteValue` and the ~15 sites like it are
spelling "here is a value, evaluate to it" as `(quote <value>)`, which is
indistinguishable from a source literal. A distinct internal head (the shape
`%UNSPELLED-QUOTE` already has -- `LispNames.UNSPELLED_QUOTE`, routed to `evalQuote` at
`LispEvaluator.java:4752`) evaluated as "answer the datum verbatim" separates the two,
and is what lets `evalQuote` do anything at all with a literal. That is a small,
self-contained change and it stands on its own.

## Definition of done

The program at the top answers identically on all four backends, with a `ci-spec.yaml`
case pinning it and `.kb/array-literals.md`'s "What quote still shares" section deleted
or rewritten. `LispEvaluatorTest.aQuotedArrayIsStillTheDatumOnTheInterpreter` exists to
FAIL when this lands, so nobody has to remember.
