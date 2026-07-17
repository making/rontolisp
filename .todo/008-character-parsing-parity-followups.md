# Character / parsing: compiled-backend parity follow-ups

**Status:** deferred polish. The character type, `parse-integer`,
`read-from-string` and `(read stream)` are implemented and tested across all
three backends. These are the remaining interpreter-vs-compiler asymmetries,
documented in `doc/*/guides/read-load-limitations.md` and low priority -- pick
up if exact parity becomes important.

> **Update 2026-07-17 (inventory):** items 1 and 2 were retired. Commit
> `3d4f6c0` (parse-number v1.8 on all four backends) deleted
> `JvmParseIntegerRuntimeBuilder` and `WasmParseIntegerCompiler` and replaced
> both with one shared pure-Lisp expansion, `LispMacroExpander.expandParseInteger`.
> That accumulates through generic `(+ (* acc radix) digit)`, so the JVM half of
> item 1 is fixed by `JvmArithCompiler`'s BigInteger promotion, and WASM stays
> i31-limited like all WASM integer arithmetic -- the general WASM integer story,
> not a `parse-integer` bug. Item 2 is likewise gone: the expansion emits real
> `(error "parse-integer: junk in string ~s" ...)` forms, so the message matches
> by construction; what remains is generic WASM `%error` behavior (traps outside
> EH mode, throws a catchable `$lisp-cond` inside it), which belongs to
> `.todo/116-error-handling-foundation.md`.

## Items

1. **WASM character case/letter tests are ASCII-only.** `char-upcase`,
   `char-downcase` and `alpha-char-p` in `WasmCharCompiler` only fold/recognize
   `a-z`/`A-Z`; the interpreter and JVM use `Character.toUpperCase` /
   `Character.isLetter` (full Unicode). The WASM backend byte-indexes strings, so
   broad Unicode support would be a larger change (UTF-8 decoding).

2. **Runtime `read`/`read-from-string` of `#\` character literals is out of
   scope on both compilers.** The hand-written WASM reader and the JVM runtime
   reader do not parse `#\name`; only `#\` literals written directly in source
   (compiled via the AST) are supported. Extending the runtime readers to emit a
   character value would lift this.
