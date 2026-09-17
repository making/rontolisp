# Two string-identity edges the backends still spell differently

Difficulty: Medium

Left over from the `eq`/`eql` identity work (2026-09-17), which made two distinct strings
with equal contents non-`eql` on all four backends (`.kb/hash-tables.md`, "Strings under
eq/eql"). Both remaining differences are constant-coalescing edges ANSI leaves
implementation-dependent, so neither is a conformance bug -- but "all four backends print
the same thing" is the project's own rule.

1. `(let ((s (copy-seq "ab"))) (eq s (string s)))` is `T` on the interpreter and `NIL` on
   the JVM and both WASM backends, whose `string` renders a character vector into a fresh
   value instead of answering the argument. SBCL answers `T` (CLHS: `string` of a string is
   that string). The JVM path is the `_strv` normalization in the `string` arm, the WASM
   path the `_charvec_to_str` call.
2. A string a MACRO builds into its expansion -- `(defmacro m () (format nil "~a" "made"))`
   -- is a constant on the compiled backends (`(eq (m) (m))` is `T`, the literal is
   coalesced) and a freshly allocated string on the interpreter (`NIL`). The interpreter
   marks only the reader's `"..."` and a symbol's name as constants
   (`LispString.sourceLiteral`).

## To do

1. Decide each one: make `string` identity-preserving on the compiled backends (measure
   what else reads its result as an immutable value first), and decide whether the
   interpreter should treat every string it evaluates AS A FORM as a constant -- which is
   what CLHS 3.2.4.4 lets the compiled backends do -- or whether the divergence is worth
   only a line in the kb.
2. Whatever is decided, pin it with a `ci-spec.yaml` case and record it in
   `.kb/hash-tables.md` beside the rest of the string-identity rules.
