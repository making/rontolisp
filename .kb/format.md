# `format`: two renderings, one directive set

`format` has TWO implementations of the same directive set, and they must not drift:

| control string | who renders it | where |
|---|---|---|
| a literal (`(format nil "~a" x)`) | `LispMacroExpander.expandFormat` -- parses at COMPILE time and lowers to string pieces (`FmtParser` -> `FmtOp`s -> `%string-concat` / `princ` calls); no interpreter reaches the output | `macro/LispMacroExpander.java` |
| a runtime value | `%fmt-render`, a Lisp-source interpreter over the control string, injected once per program | `macro/format-render.lisp` + `macro/FormatRenderer.java` |

**The invariant: the same control string and arguments render to the same text
whichever path renders them, on all four backends.** Pinned by
`FormatRendererTest.staticAndRuntimeRenderingAgree` (a table run through BOTH
paths) and the `format-runtime-control-string` ci-spec case. Add a directive to
one path and you add a row to that table.

Two implementations is a deliberate cost: the literal path exists so an ordinary
`(format t "...")` compiles to concatenation, with no control-string parsing and
no renderer in the emitted artifact. Merging them would put the renderer into
every program that formats anything.

## What reaches the runtime renderer

Five ways in -- all of them funnel into `(%fmt-render control arguments)`:

1. a computed control expression (cl-who's `escape-string` binds its control to
   a local; cl-postgres carries the server's message);
2. `#'format` as a first-class value -- the `BuiltinFunctionWrappers.formatWrapper`
   body (compile path) and the `format` `LispFunction` in `LispEvaluator`
   (interpreter) both call the renderer, so `(apply #'format ...)` renders the
   same way everywhere;
3. `~?` / `~@?` (the inner control is data by definition);
4. a literal control the static parser DECLINES (`UnsupportedOperationException`
   -- justification `~<`, an argument-divergent `~[` nested in a composite, `~t`,
   `~p`): `expandFormat` falls back rather than failing the compile, so a library
   carrying such a directive on a cold branch still builds;
5. a condition's `format-control` slot -- `%format-condition` (see
   `.kb/error-handling.md`) renders a string control through the renderer.

## Injection (compile path) and lazy load (interpreter)

`LispMacroExpander.expandTopLevelDefinitions` appends `FormatRenderer.defuns()`
at BOTH of its exits (`withFormatRenderer`), the definition-splicing one and the
early "nothing to splice" fast path -- the plainest runtime-control programs take
the fast path. The gate scans the program for (a) a renderer call already present
(the condition-report runtime injected just above, a spliced library), (b)
`#'format`, (c) a `(format ...)` call whose control the static path will decline
-- decided by running the SAME `FmtParser` the expansion runs, so the gate and
the expansion cannot answer differently.

It has to be a scan of the pre-expansion program: expression expansion happens
per form much later (Pass 2) and cannot add a top-level defun. A program with no
way in carries none of the renderer.

The interpreter cannot inject top-level defuns at all, so `LispEvaluator`
evaluates the same forms into the global environment on the first resolution of
a `%FMT-` name (`ensureFormatRendererLoaded`, the `%condition-report-str`
pattern).

## Why the renderer is Lisp source

`FormatRenderer` reads `format-render.lisp` with the real `LispReader`. That is
the reason the `macro` package sits ABOVE `reader` in the dependency order (see
CLAUDE.md): an expander pass may BUILD the AST it injects by reading Lisp instead
of assembling `LispCons` nodes in Java. Before that the renderer was a
hand-assembled lambda inlined at each call site, and it understood `~~ ~% ~a ~s
~d ~x ~c` only -- every other directive was emitted LITERALLY while its argument
was still consumed, so the tail of a mixed control string came out shifted
(todo-216). A full directive set was not writable in that form.

The definitions are many small defuns on purpose: one emitted WASM function body
must not grow without bound (`.kb/wasm-function-body-size.md`). The resource
needs a `resource-config.json` entry to survive native-image
(`NativeImageResourceConfigTest` enforces it).

Cost, measured 2026-07-31 on a three-`format` program: a program that does NOT
reach the renderer is byte-identical to a build that never knew about it (both
backends, verified by the stash dance). One that does grows by ~114 KB of wasm
(316 KB -> 430 KB), which is the whole directive set in one place and is not
tree-shakeable -- every arm is reachable from `%fmt-render`, since the control
string is only known at run time. The gate is what keeps every other program at
zero.

## Deliberate divergences, and why

- **The renderer never signals.** A malformed control, an unknown directive, an
  unterminated `~{`, a missing argument all render as text (`NIL` for the missing
  argument). The literal path signals the same problems at EXPANSION time, which
  is a compile-time diagnostic. Reason: a runtime control usually arrives with
  the data being reported -- a condition report must not fail while reporting.
  Do not "fix" this by signalling; it would put a crash inside the error path.
- **`~t` and `~p` are renderer-only.** The static path declines them and falls
  back, so both work either way; the fallback is what makes that acceptable. If
  the static path ever grows them, drop them from this list, not from the
  renderer.
- **`~r` without a radix prints decimal digits.** English cardinals/ordinals are
  not implemented on either path.
- **`~&` measures the column from the text rendered so far** (an empty
  accumulator counts as the start of a line), because the renderer answers a
  string and cannot see the stream's column. The literal path's `t` destination
  uses the real column. Same approximation the literal path already documents for
  a nil destination.
- **`~x`/`~o`/`~b`/`~r` answer UPPERCASE digits** on both paths, as Common Lisp
  does. The old cut-down fallback answered lowercase, which is why cl-who's
  numeric entity (`&#x~x;`) changed case when the renderer landed
  (`ClWhoE2eTest`).
- **`(error <runtime control> args...)` still drops its format arguments** --
  `expandSignalDesignatorInner` routes a non-literal string datum to the OBJECT
  designator path, which never sees the arguments. Separate defect, tracked in
  its own todo; the renderer is not the reason.
