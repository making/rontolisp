# Syntax

The reader is Clojure's, case-sensitive: `Foo` and `foo` name different variables. Every
identifier is mangled behind a `c%` prefix on lowering, so no Clojure name can collide
with a core form or built-in; a quoted symbol demangles, so `'e2e-foo` prints `e2e-foo`.

## Dispatch and comment syntax

, is whitespace, as in Clojure. `#!` starts a shebang comment on the first line; `;`
starts a line comment; `#_` skips the next form. The dispatch forms that read but are
refused later are covered in [Semantics](semantics.md#refused-forms): regex literals
(`#"..."`), backquote/unquote (`` ` ``, `~`, `~@`), `var`/`#'` and metadata (`^`).

## Characters

Characters read as characters: `\a`, the lowercase `newline`/`space`/`tab`/`return`/
`backspace`/`formfeed` names, `\uXXXX` and `\oNNN`. Anything else is an `Unsupported
character` refusal. A character prints `\a` readably (`pr`) and `a` plainly
(`println`/`str`).

## Numbers

Integers read in radix: `0xFF` hexadecimal, `2r101` and `8r17` arbitrary-radix `Nr`
notation, and a leading-`0` octal; the sign applies outside the radix prefix. A shaped
token that parses to nothing is an `Invalid number` refusal.

`1M` lowers to an exact ratio (`0.1M` is `1/10`), so decimal arithmetic stays exact and
prints as the ratio without its mark; a `2N` past the `long` range is a bignum, also
printing without its mark. Ratios read as `1/2`.

## Booleans, nil and keywords

`true`, `false` and `nil` are self-evaluating; `false` is a distinct object from `nil`
(see [Deviations](deviations.md)). A keyword `:foo` is data holding its spelling
verbatim, case-preserved, so `:a` and `:A` stay apart; it prints with its colon. A
namespaced `:a/b` is opaque data that prints and compares whole; `::`-auto-resolve is
refused. In call position a keyword is the map lookup -- [Semantics](semantics.md).

## Collection literals

A vector `[1 2 3]`, a map `{:a 1}`, a set `#{1 2}` and a quoted list `'(1 2 3)` read as
the literals whose lowering [Semantics](semantics.md) describes. A set literal refuses a
repeated element by spelling (`Duplicate key`).
