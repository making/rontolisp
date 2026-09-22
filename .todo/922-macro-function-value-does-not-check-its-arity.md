# The `macro-function` value does not check its arity

Difficulty: Low

Split out of `.todo/917` (2026-09-20), which left it on the ANSI `packages`
board as the one row shared by every chapter.

## What it is

The ANSI suite's `def-macro-test` (`auxiliary/ansi-aux.lsp`) asks three things of
every macro NAME: `(funcall (macro-function 'NAME))` with no arguments,
`(funcall (macro-function 'NAME) '(NAME ...))` with one, and the same with four,
must each signal a `program-error` -- a macro function takes exactly two
arguments (the form and the environment). rontolisp's interpreter answers
`macro-function` with "the REAL expander" (`LispEvaluator`, the
`LispNames.MACRO_FUNCTION` binding: a `LispFunction` that macroexpand-1's the
form after re-heading it), and that function expands happily with one argument,
so the middle probe answers a value instead of signalling: every
`def-macro-test` row reads `got (T NIL T) want (T T T)`.

The compiled backends answer `#'%macro-expander-stub` (non-nil, a signal when
called) -- check what it signals with one argument; the partition invariant in
`.kb/symbol-runtime-api.md` ("`macro-function` / `special-operator-p` PARTITION
the operators") is the section to extend.

## What it costs

24 `def-macro-test` rows in the suite (`grep -c def-macro-test` over the chapter
files, aux excluded); 7 of them are in the `packages` chapter
(`with-package-iterator.error.1`, `in-package.error.1`, `defpackage.error.1`,
`do-symbols.error.1`, `do-external-symbols.error.1`, `do-all-symbols.error.1`,
and `unuse-package`'s twin). Measure as a DIFF of failing test names across the
whole suite, since the row appears in most chapters.

## Plan

Make the interpreter's macro-function value require exactly two arguments
(a `program-error` otherwise, the arity message the other CL functions use) and
pin the three probes in `LispEvaluatorTest#macroFunctionIsTheRealExpanderOnTheInterpreter`;
give the compiled stub the same check if it lacks one, with the matching pair in
the two compiler tests. No new operator, no doc page: the behavior is the
`macro-function` page's existing contract.
