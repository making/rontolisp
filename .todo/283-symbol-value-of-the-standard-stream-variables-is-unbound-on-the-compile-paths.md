# `symbol-value` of the standard stream variables is unbound on the compile paths

Difficulty: Medium

`*standard-output*`, `*standard-input*` and `*error-output*` are bound on the
interpreter and unbound to `symbol-value`/`boundp` on all three compile
backends:

```bash
cat > sv.lisp <<'EOF'
(print (boundp '*error-output*))
(print (symbol-value '*error-output*))
EOF
rontolisp sv.lisp                              # T then 2
rontolisp sv.lisp -o Sv.class && java Sv       # NIL, then "The variable *ERROR-OUTPUT* is unbound"
rontolisp sv.lisp -o sv.wasm && wasmtime run -W gc sv.wasm   # NIL, then a trap
```

Same three answers for `*standard-output*` and `*standard-input*` (the
interpreter answers the designator `T` for both; `*error-output*` is the
reserved stream handle `2`).

## Why it is not cosmetic

It turns a real error into a wrong one, at the worst moment. `lack:builder`'s
default `:backtrace` middleware -- which `clack:clackup` wraps every
application in unless `:use-default-middlewares nil` -- carries
`(output '*error-output*)`, a SYMBOL, and reports a failing handler with
`(symbol-value output)`. On a compiled backend that call is itself an error, so
the application's ACTUAL error is replaced by "The variable *ERROR-OUTPUT* is
unbound" and the served response is a bare 500. Finding the real fault meant
re-running with `:use-default-middlewares nil` to get the middleware out of the
way.

## Why it happens

Two separate variable homes that do not know about each other.

- A compiled program keeps a special variable in a per-name GLOBAL FIELD (JVM)
  or global (WASM), and the three stream variables get a field ONLY when the
  program names one -- `JvmLispCompiler`'s `globalFields` /
  `seedsStandardStream`, whose `<clinit>` then seeds `T`/`T`/handle `2`
  (`.kb/standard-output-redirect.md`).
- `symbol-value` and `boundp` compile to a lookup in `_genv`, the eval
  runtime's global-environment mirror (`JvmSymbolApiCompiler.compileSymbolValue`
  / `compileBoundp`, `.kb/symbol-runtime-api.md`). The seeded stream defaults
  never reach that mirror, so the lookup misses and the emitted
  `emitUnboundThrow` fires.

The interpreter has one home for both (`Environment.createGlobal`), which is
why it is right.

## Suggested fix

Give the runtime lookup the same three defaults the field seeding already
knows: when `symbol-value`/`boundp` misses `_genv` and the name is one of the
three, answer the seeded value (`T`/`T`/`2`) instead of signalling. That keeps
ONE table of "what the standard streams default to" if the constants are shared
with the `<clinit>` seeding rather than copied. A program that REBINDS the
variable must still win -- the `_genv` hit already shadows the fallback, so
order the check after the lookup, not before.

Alternative (bigger, probably better long-term): seed the three into `_genv`
whenever the program can name them, so the two homes agree by construction and
nothing needs a special case at the lookup. Measure the size cost first --
`usesEval` programs already carry the mirror, but a program that only calls
`symbol-value` should not start dragging the stream machinery in.

## Done when

- `(boundp '*error-output*)` is `T` and `(symbol-value '*error-output*)` is the
  interpreter's value on all four backends, same for `*standard-output*` and
  `*standard-input*`, pinned by a `ci-spec.yaml` case (they are printable
  scalars, so the corpus can carry them).
- A `let`-rebinding of one of them still answers the REBOUND value where the
  backend supports it, and the documented
  "`symbol-value` sees the global default, not a dynamic binding" divergence
  (`.kb/dynamic-special-variables.md`, point 3) is unchanged -- this item is
  about the global default being MISSING, not about dynamic scope.
- A program that never names the variables is byte-identical.
- The lack backtrace middleware reports the application's real error on a
  compiled backend: `LackEcosystemE2eTest` gains a leg whose handler signals
  and whose 500 body (or stderr) names the actual condition.

## Related

`.kb/symbol-runtime-api.md`, `.kb/standard-output-redirect.md`,
`.kb/dynamic-special-variables.md`, `.kb/lack.md`.
