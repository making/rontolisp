# Syntax

The reader is Clojure's, case-sensitive: `Foo` and `foo` name different variables. Every
identifier is mangled behind a `c%` prefix on lowering, so no Clojure name can collide
with a core form or built-in; a quoted symbol demangles, so `'e2e-foo` prints `e2e-foo`.

## Dispatch and comment syntax

, is whitespace, as in Clojure. `;` and `#!` start a line comment (`#!` serves for a
shebang line); `#_` skips the next form (one before a closing bracket or at the
end of the file discards too, so `[1 #_ 2]` is `[1]`). A regex literal (`#"..."`) reads to
a pattern value (see [Regular expressions](reference/regex.md)); `#'x` reads as
`(var x)`, the var of a definition (see [var](reference/var.md)); syntax-quote
(`` ` ``, `~`, `~@`) is covered in [Semantics](semantics.md). Metadata (`^`, the legacy
`#^`) reads too: on a name or a local it parses and drops, on a vector, map or set
literal it attaches like `with-meta` (see
[Semantics](semantics.md#state-and-dynamic-scope)). A record literal
(`#ns.Name{...}` / `#ns.Name[...]`) reads to the record over its unevaluated body (see
[defrecord](reference/defrecord.md)). A namespace map gives its keys a namespace:
`#:user{:id 1 :_/raw 2 name 3}` reads as `{:user/id 1 :raw 2 user/name 3}` (each keyword or
symbol key without a namespace takes it, one qualified by `_` loses it), and `#::{...}` /
`#::alias{...}` take the current namespace or the alias's for their keyword keys.

## Reader conditionals

A `.cljc` file, and the REPL, read reader conditionals like the oracle: `#?(:clj a :cljs b)`
reads the form of the first feature the reader has, and `#?@(...)` splices a list or vector
into the enclosing collection. The features are `:rontolisp`, then the oracle's `:clj`, and
`:default`; a `:cljs` branch is read but never built (`js/x`, `#js {...}` and
`:require-macros` in it never reach the program). A conditional taking no branch reads as
nothing, and a splice at the top level is refused. A `.clj` file refuses `#?` with the
oracle's `Conditional read not allowed`. `read-string` and `read` take
`{:read-cond :allow}` (see [read-string](reference/read-string.md)).

## Characters

Characters read as characters: `\a`, the lowercase `newline`/`space`/`tab`/`return`/
`backspace`/`formfeed` names, `\uXXXX` and `\oNNN`. The character after the backslash
belongs to the literal whatever it is, so `\(`, `\;` and `\"` read (what `pr` spells for
them), and a backslash ends a literal (`[\a\b]` is two). Anything else is an
`Unsupported character` refusal. A character prints `\a` readably (`pr`) and `a` plainly
(`println`/`str`).

## Numbers

Integers read in radix: `0xFF` hexadecimal, `2r101` and `8r17` arbitrary-radix `Nr`
notation, and a leading-`0` octal; the sign applies outside the radix prefix. A shaped
token that parses to nothing is an `Invalid number` refusal.

`1M` lowers to an exact ratio (`0.1M` is `1/10`), so decimal arithmetic stays exact and
prints as the ratio without its mark; a `2N` past the `long` range is a bignum, also
printing without its mark. Ratios read as `1/2`.

`##NaN`, `##Inf` and `##-Inf` read as doubles, in source and under `read-string`/`read`,
and `pr`, `prn` and `println` print them in the same spelling (also inside a collection);
`str` and `format` say `NaN`, `Infinity` and `-Infinity` for the bare value. Any other
`##name` is an `Unknown symbolic value` refusal. Two NaNs are never `=`; a NaN used as a
map or set key is found by value here, where the oracle finds only the same boxed object.

## Booleans, nil and keywords

`true`, `false` and `nil` are self-evaluating; `false` is a distinct object from `nil`
(see [Deviations](deviations.md)). A keyword `:foo` is data holding its spelling
verbatim, case-preserved, so `:a` and `:A` stay apart; it prints with its colon. A
namespaced `:a/b` is opaque data that prints and compares whole. `::`-auto-resolve
resolves against the current namespace: `::kw` to `:my.ns/kw` inside `(ns my.ns)`
(`:user/kw` without one), `::alias/kw` through the alias (`:require`'s `:as`, the
namespace's own name, or a known namespace without any require). An unknown alias is
an `Invalid token` refusal. In call position a keyword is the map lookup -- [Semantics](semantics.md).

## Collection literals

A vector `[1 2 3]`, a map `{:a 1}`, a set `#{1 2}` and a quoted list `'(1 2 3)` read as
the literals whose lowering [Semantics](semantics.md) describes. A set literal refuses a
repeated element by spelling (`Duplicate key`).
