# c38. JVM backend: every defun docstring is a dead `ldc`/`pop` in the class

Difficulty: Medium

A defun's docstring compiles to `ldc_w #n; pop` at the start of its method on the JVM backend, so the
class carries every docstring of every function it keeps. Measured 2026-10-03 on
`examples/clojure/demo.clj` (`-o X.class`): 112 docstrings, about 11 KB of the 131,502-byte class, all
from the spliced `clojure.lisp`; a changed docstring changes the class (two edited docstrings grew it by
209 bytes). The wasm backend drops a self-evaluating literal in statement position
(`WasmExprCompiler`, the docstring comment there).

Drop the statement on the JVM backend the way wasm does, unless `documentation` needs the string (then
keep it once, where `documentation` reads it). Measure the class sizes of the corpus before and after.
