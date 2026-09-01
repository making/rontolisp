# The printer never abbreviates `(quote x)` / `(function x)`, and never `|...|`-escapes a symbol

Difficulty: Low

Two read-back gaps in `prin1`/`~S`/`print`, both independent of the package
qualifier (`.todo/391`) and of `*print-case*`:

```lisp
(print '#'foo)          ; rontolisp: (FUNCTION FOO)   SBCL: #'FOO
(print ''foo)           ; rontolisp: (QUOTE FOO)      SBCL: 'FOO
(print '|hello world|)  ; rontolisp: hello world      SBCL: |hello world|
(print (intern "lower")); rontolisp: lower            SBCL: |lower|
(print (list '|x| 'x))  ; rontolisp: (x X)            SBCL: (|x| X)
```

The reader already handles `|...|` correctly -- `(symbol-name '|a b|)` is
`"a b"` and `(eq '|FOO| 'foo)` is true -- so this is only the render side.
`prin1`'s contract is that its output reads back as the same object, and
`(x X)` above reads back as two occurrences of the same symbol.

The escaping rule (CLHS 22.1.3.3): a symbol needs `|...|` (or per-character
`\`) when its name contains a character that is not a constituent, or when
re-reading the name under the current `*print-case*` / readtable case would
not produce the same name -- for us, `:upcase`, so any name with a lowercase
letter. Keywords keep their `:`; an uninterned symbol keeps `#:`. `princ`
and `~A` must NOT escape or abbreviate, so the switch is `*print-escape*`,
which the printer already reads.

The abbreviations are the printer's, not the reader's: a two-element list
whose head is `quote` / `function` prints as `'` / `#'` followed by the second
element. Guard on the length being exactly 2 (`(QUOTE A B)` prints in full),
and note that `.kb/defmacro-backquote.md` expands backquote AT READ TIME, so a
quoted template prints as its `list`/`append` expansion either way -- that is a
separate, deliberate deviation and not something this item restores.

Found by `.todo/620`: `macroexpand-1` output is the thing a reader of the book
compares against the page, and chapter 8's `ppme` exists only to print it.

Same seam as `.todo/391` and the `.todo/041` renderer list -- if 391 lands
first, do these in the same pass. Differential-test against SBCL as 041 did.
