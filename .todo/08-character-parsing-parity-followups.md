# Character / parsing: compiled-backend parity follow-ups

**Status:** deferred polish. The character type, `parse-integer`,
`read-from-string` and `(read stream)` are implemented and tested across all
three backends (was `.todo/04`, now removed). These are the remaining
interpreter-vs-compiler asymmetries, all documented in the README and low
priority -- pick up if exact parity becomes important.

## Items

1. **Compiled `parse-integer` has no big-integer promotion.** The interpreter
   accumulates into a `BigInteger` (`Environment.parseInteger`), but the JVM
   helper (`JvmParseIntegerRuntimeBuilder`, `long` accumulator) and the WASM
   inline loop (`WasmParseIntegerCompiler`, i31) overflow on very large inputs.
   To fix, thread big-integer accumulation through the compiled helpers (JVM
   already has a `BigInteger` runtime; WASM has no bignum, so it would stay
   i31-limited like the rest of WASM integer arithmetic).

2. **WASM `parse-integer` error path traps instead of signaling.** With
   `:junk-allowed nil` (the default), trailing junk or an empty parse emits
   `unreachable` (`WasmParseIntegerCompiler`), aborting the module, whereas the
   interpreter/JVM throw a message containing `parse-integer`. A nicer WASM path
   would write an error string to stderr before trapping (cf. how `%error`
   is handled), or reuse a shared error helper.

3. **WASM character case/letter tests are ASCII-only.** `char-upcase`,
   `char-downcase` and `alpha-char-p` in `WasmCharCompiler` only fold/recognize
   `a-z`/`A-Z`; the interpreter and JVM use `Character.toUpperCase` /
   `Character.isLetter` (full Unicode). The WASM backend byte-indexes strings, so
   broad Unicode support would be a larger change (UTF-8 decoding).

4. **Runtime `read`/`read-from-string` of `#\` character literals is out of
   scope on both compilers.** The hand-written WASM reader and the JVM runtime
   reader do not parse `#\name`; only `#\` literals written directly in source
   (compiled via the AST) are supported. Extending the runtime readers to emit a
   character value would lift this.
