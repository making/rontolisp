# defmacro / backquote follow-ups

`defmacro` (user macros) and the backquote template syntax landed: the reader
expands `` ` ``/`,`/`,@` at read time into `list`/`append`/`quote` forms, the
interpreter expands user macro calls at evaluation time
(`LispEvaluator.userMacros`/`expandUserMacro`), and the compile path runs
`eval.UserMacroExpander` in the CLI (after `LoadInliner`, before the compilers),
so the JVM/WASM backends never see a macro form. Remaining gaps, roughly by
value:

## gensym — DONE

Implemented in all three backends: interpreter (`Environment`, per-environment
`AtomicLong`), JVM (`JvmGensymCompiler` + static `_gensymCtr` field), WASM
(`WasmGensymRuntimeBuilder`/`WasmGensymCompiler`, `FUNC_GENSYM` +
`GENSYM_CTR_ADDR`). Names are `#:<prefix><n>`; the compilers require a literal
string prefix. `PackageRegistry.splitQualified` exempts `#:`-prefixed names
from package qualification (an expanded macro body reaches `PackageResolver`
on the compile path). Remaining gap: the counter is not CL's
`*gensym-counter*`, and interpreter vs compile-path counters can disagree when
a macro body calls gensym (documented in ci-spec).

## Nested backquote

`` `(a `(b ,c)) `` is a read error ("Nested backquote is not supported").
Supporting it needs the standard depth-tracking expansion algorithm in
`LispReader.readTemplateElement`. Mostly needed for macro-defining macros;
those can be written with explicit `list`/`quote` today.

## defmacro lambda lists — DONE

Destructuring parameter lists, `&optional` (with defaults), `&key`, and `&aux`
landed 2026-07-05 via the `destructuring-bind` wrapping in
`LispEvaluator.evalDefmacro` (details: `.kb/defmacro-backquote.md`; see also
`.todo/031-lambda-list-extensions.md`). Remaining gaps: `&whole` and
`&environment` are rejected with an error, and an extended lambda list has lite
no-mismatch semantics (missing positions bind nil, surplus forms ignored) —
only a plain "required + `&rest`/`&body`" list keeps the strict
argument-count check.

## macroexpand / macroexpand-1 — DONE

Implemented interpreter-natively (`LispEvaluator.macroexpand1/macroexpand`,
built-in macros via `LispMacroExpander.expandBuiltinMacro` — keep its case list
in sync with `PackageRegistry.CL_MACROS`). On the compile path,
`UserMacroExpander` folds a literal quoted argument to `(quote expansion)` and
activates on macroexpand calls even without a defmacro. No multiple values (no
`expanded-p`), no environment argument, computed arguments are not compilable.

## Runtime read of backquote in compiled programs

Backquote is expanded by the JDK `LispReader`, so the runtime `read` of
compiled output (`Jvm/WasmReadRuntimeBuilder`) does not understand the `` ` ``
character (it lexes as part of a symbol). Interpreter `read` expands it (the
result is the expansion form as data). Documented in the defmacro reference
page and eval-limitations; full parity would need the backquote expansion in
both emitted readers.

## Macros outside cl-user

`UserMacroExpander` matches macro names by bare (unresolved) name and its
macro-time evaluator processes `defmacro`/`defun` forms through the default
package state, so macros defined/used after `(in-package rontolisp)` or via
qualified names are untested/unsupported. Fine for the built-in three-package
world; revisit if `defpackage` ever lands.

## Compile-time side effects

The macro-time evaluator registers top-level `defun`s so macro bodies can call
helpers (more lenient than CL's `eval-when` requirement), but top-level
`defvar`/`setq` are NOT evaluated at compile time — a macro body reading a
global fails at expansion time. Matches CL; documenting here because the
asymmetry (functions yes, variables no) may surprise.
