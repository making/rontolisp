# JVM: a defun whose name has a cell is never fused

Difficulty: Medium

A program holding `eval` (every clack app: clack's `eval-file`) gives every named defun a cell
(`RedefinedDefuns.celledDefuns`), and `JvmLispCompiler` keeps those names out of `inlinableDefuns`
(`notinline`), so ironclad's `mod32+`/`rol32` stay calls on the JVM. The WASM twin substitutes such a
name under a check of its cell since j32 (`.kb/wasm-int-fusion.md`, "A name with a cell").

## Measured (2026-11-09)

Xeon E5-2697A v4, `cl-postgres::gen-salted-password` x4096 (PBKDF2-HMAC-SHA-256) in a class
compiled from a program loading cl-postgres and cl-who, six derivations in one process: without
`eval` in the program 91-212 ms each after the first, with an unused `(defun f (x) (eval x))`
254-657 ms. The first derivation (4-6 s, JIT warm-up) is the same either way.

## What to do

Port the WASM design to `JvmIntFusionCompiler`: substitute a celled name's body where the site keeps
the order a replacement would see (every leaf after the call's application inert), check the cell
(`_fc$name`) before the fast path, and in the fallback call the name's entry over the arguments, once
per argument use. Mind what the WASM side did not have: the outlined method's parameters, a linked
class's `notinline` (`JvmFunctionCell.notinlineNames`), and the tail bounce (`celledBouncers`) -- a
tail-position call stays a call, as on WASM. Pin by extending
`JvmLispCompilerTest.fusedIntegerExpressionTreesMatchTheGenericPath` (`MIX32` calls neither `MOD32+`
nor `ROL32`) with the same program holding `eval`; ci-spec
`a-defun-written-through-a-computed-name-answers-the-new-definition` already holds the replacement
semantics.
