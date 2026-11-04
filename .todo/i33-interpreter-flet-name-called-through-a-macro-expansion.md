# i33. Interpreter: a `flet` name that reaches a call head only through a macro is undefined

Difficulty: Medium

Measured 2026-11-04. JVM, both WASM backends and SBCL 2.2.9 answer 2; the interpreter signals.

```lisp
(defmacro call-it (name) `(,name 1))
(defun g1 () (flet ((f (x) (+ x 1))) (call-it f)))
(print (g1))   ; interpreter: The function F is undefined
```

`LispMacroExpander.expandFlet`/`expandLabels` rewrite the local function's call sites textually, before a
macro in the body has expanded. `LispEvaluator.preExpandLocalMacros` runs `expandAll` first only when the
tree already names a `macrolet` (`treeContainsMacrolet`), which is why ironclad's `sha256-round` works and
this does not. A `macrolet` hidden behind a macro call is missed too.

Consumer: `(asdf:test-system :fast-http-test)` stops on `FAST-HTTP.PARSER::URL-CHAR-BYTE-P is undefined`:
`parser.lisp:190` is `(flet ((url-char-byte-p ...)) (with-octets-parsing ... (skip-while url-char-byte-p)))`.

The walk is cheap to skip on purpose: it runs on every `flet` entry, and cl-mustache's recursive renderer
exhausted the stack on a recursive version (see the comment on `treeContainsMacrolet`). Either make the walk
once per call site, in the expansion memo the `symbol-macrolet` arm uses, or bind the local functions in a
scope instead of rewriting call sites, which would also retire the lite scoping `evalMacrolet`'s javadoc
records. Pin in ci-spec, since the compile paths already agree.
