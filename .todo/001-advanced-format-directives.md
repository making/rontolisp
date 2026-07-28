# Advanced `format` directives (deferred scope)

**Status:** mostly implemented; a small remainder is still deferred.

The `format` macro now covers, in addition to the original "Basic Formatting"
set (`~a`/`~s`, `~d`, `~f`, `~e`, `~$`, `~%`, `~&`, `~~`, prefix parameters,
`:`/`@` modifiers):

- Radix integers: `~x`/`~o`/`~b` (uppercase digits, same parameters/modifiers
  as `~d`) and `~NR` (radix parameter required). The reader also gained
  `#x`/`#o`/`#b` integer literals.
- `~c` (character), with `@` (`#\` prin1 syntax) and `:` (names for non-graphic
  characters, derived from `prin1-to-string` minus the `#\` prefix).
- Case conversion `~( ... ~)` with all four variants (`~(`, `~:(`, `~@(`,
  `~:@(`).
- Conditionals `~[ ... ~]` incl. `~;` / `~:;` default clauses, `~:[`, `~@[`,
  literal `~N[` and `~#[` selectors. Because the expansion is static, a
  runtime-selected `~[` requires every clause to consume the same number of
  arguments (a literal/`#` selector is resolved at expansion time and lifts
  the restriction); `~@[` must consume exactly the tested argument.
- Iteration `~{ ... ~}` with `~:{`, `~@{`, `~:@{` and a max-pass count
  (literal or `v` for the runtime-list forms; `~@{`/`~:@{` unroll statically
  over the remaining arguments). Bodies parse against a runtime argument
  source (`FmtArgs.forRuntimeItems`), so directives inside the body consume
  list elements via car/nthcdr chains. `#` and `~@{` are not available inside
  a `~{` body.
- Argument jumps `~*`, `~N*`, `~:*`, `~N:*`, `~N@*`.
- `~g` (no prefix parameters): plain float representation for magnitudes in
  [0.1, 1e16) and zero, the `~e` default form otherwise — an approximation of
  the CLHS significant-digit rule.
- Runtime (`v`) pad characters (`~v,vd`), `~f` scale factor and overflowchar,
  `~e` exponent-digit count / overflowchar / exponentchar (scale factor fixed
  at 1).
- `~<newline>` (line continuation), with its `:` / `@` variants.

Implementation: `LispMacroExpander.FmtParser` (recursive descent) +
`FmtArgs` (static temporaries at the top level, on-demand item temporaries
inside iteration bodies). All expansions remain pure-Lisp over primitives the
three backends already share; covered by `LispEvaluatorTest` /
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` format cases and the
`format-directives-*` cases in `ci-spec.yaml` (verified on all four backends).

Still not implemented (possible future work):

- Column control: `~t` (tabulate), `~<...~>` (justification).
- The `~:^`/`~@^` variants and prefix parameters of the loop escape (plain
  `~^` landed 2026-07-28 with todo-195: supported at the top level -- where
  the static argument count decides it at expansion time -- and inside
  `~{`/`~@{`/`~:{` bodies via the `FmtCut` segment lowering; pinned by
  `LispEvaluatorTest.evalFormatIterationEscape` and the
  `s-sql-enablement-language-group` ci-spec case).
- `~r` without a radix (English cardinal/ordinal, Roman numerals `~@r`).
- A runtime `v` count for `~%`/`~&`/`~~`, and accurate column tracking for
  `~&` with destination `nil` and inside composite bodies (still a static
  approximation).
- Runtime-selected `~[` with clauses of uneven argument consumption (would
  need a runtime argument cursor instead of the static expansion).
- `~e` notes carried over: the mantissa is built from integer arithmetic, the
  digit count must be a literal, and the WASM i31 cap limits precision to
  roughly `~,De` with `D` <= 8.
