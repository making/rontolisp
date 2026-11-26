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

## Nested backquote — DONE

`` `(a `(b ,c)) `` works. `LispReader` carries a faithful port of the CLtL2 /
Steele Appendix C backquote algorithm (`LispReader.java:561+`): `readBackquote`
raw-reads the template first (`readRawTemplate`, marker conses + identity-compared
sentinel symbols), and routes anything containing an inner backquote through
`bqCompletelyProcess`, which lowers every level to `list`/`append`/`cons`/
`list*`/`quote` at read time — so no backquote survives to run time and no
backend gained a case. The
`LispReadException("Nested backquote is not supported")` at `LispReader.java:444`
is now unreachable for that case: it sits in the optimized single-level path,
which `readBackquote` skips once `rawSawNestedBackquote` is set.

Verified against SBCL including `once-only` three levels deep (`.kb/defmacro-backquote.md`).
Tests: `LispReaderTest:315`, `JvmLispCompilerTest:558`,
`WasmLispCompilerIntegrationTest:4224`.

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

## Macros outside cl-user — OBSOLETE

This section's revisit trigger ("if `defpackage` ever lands") has fired:
`defpackage` is real (`LispNames.java:1902`,
`doc/en/reference/special-forms/defpackage.md`, `.kb/packages.md`), so the
"built-in three-package world" framing no longer describes anything, and the
gap it recorded is gone. `UserMacroExpander` is package-aware: it recognizes
`in-package`/`defpackage` (plus the `%push-package`/`%pop-package` markers
`LoadInliner` brackets a loaded file with) BEFORE resolution and keeps them
verbatim for the compilers' own pass, feeds them to the macro evaluator's
resolver, and resolves every other form through that same resolver — so a
`defmacro` under `(in-package P)` registers its canonical P-qualified name, its
template symbols resolve against P, and call sites match the canonical name.
A form the walk did not touch keeps its ORIGINAL spelling (a canonicalized
`cl:` symbol is not always re-resolvable by the compilers' pass).

## Compile-time side effects

The macro-time evaluator registers top-level `defun`s so macro bodies can call
helpers (more lenient than CL's `eval-when` requirement), but top-level
`defvar`/`setq` are NOT evaluated at compile time — a macro body reading a
global fails at expansion time. Matches CL; documenting here because the
asymmetry (functions yes, variables no) may surprise.
