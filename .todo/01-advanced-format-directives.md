# Advanced `format` directives (deferred scope)

**Status:** intentionally out of scope for the current `format` work.

The `format` macro now covers the ["Basic Formatting" directives](https://gigamonkeys.com/book/a-few-format-recipes.html#basic-formatting): `~a`/`~s`
(padding, `:` for nil), `~d` (`:` comma grouping, `@` sign, padding), `~f`, `~e`
(scientific, padding, `@` sign), `~$`, `~%`, `~&`, `~~`, prefix parameters
(number / `'c` / `v` / `#`) and the `:`/`@` modifiers. See the README "format"
section for the supported set and limitations.

`~e` notes: the mantissa is built from integer arithmetic (so output is identical
on all three backends, unlike a raw `princ-to-string` of a float); the digit count
must be a literal (no runtime `v`); the WASM i31 cap limits precision to roughly
`~,De` with `D` <= 8; the omitted-digit default is 6 (C `printf("%e")` convention)
with trailing fractional zeros stripped.

Not yet implemented (a possible future task):

- Iteration `~{ ... ~}` (and `~@{`), and the loop-escape `~^`.
- Conditional `~[ ... ~]` (and `~:[`, `~@[`), and case conversion `~( ... ~)`.
- `~c` (character), `~r` (radix / cardinal-ordinal English), `~o`/`~x`/`~b`
  (octal / hex / binary), `~g` (general float).
- Column control: `~t` (tabulate), `~<...~>` (justification), `~*` (argument
  jump).
- A runtime `v` count for `~%`/`~&`/`~~`, and accurate column tracking for `~&`
  with destination `nil` (currently a static approximation — see README).

These are independent of the existing expansion (`LispMacroExpander` parses the
control string into pure-Lisp forms built only from primitives the three backends
already share — `subseq`/`length`/`%string-concat`/`while`/`round`/`expt`/...),
so each can be added directive-by-directive without new runtime helpers, except
where genuine runtime state is required (as `~&` needed an output-column flag).
