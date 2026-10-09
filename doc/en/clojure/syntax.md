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
(`#ns.Name{...}` / `#ns.Name[...]`, a tag whose name is dotted) reads to the record over
its unevaluated body (see [defrecord](reference/defrecord.md)); any other tag (`#inst`,
`#uuid`, `#my.lib/tag`) is a [tagged literal](#tagged-literals). A
namespace map gives its keys a namespace:
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
map or set key is found by value here, where the oracle finds only the same boxed object, so
two computed NaN keys of one literal are a `Duplicate key` (the oracle keeps both).

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
the literals whose lowering [Semantics](semantics.md) describes. A map or set literal refuses a
key that is `=` to an earlier one (`Duplicate key`): `{1 :a 1N :b}` and `#{[1] (1)}` are
refused, `{1 :a 1.0 :b}` is not. Keys only equal once evaluated are checked as the oracle's
compiler checks them: when every key is a constant, `{[1] :a '(1) :b}` is refused before the
program runs (`Duplicate constant keys in map`); otherwise `{(+ 1 2) :a 3 :b}`, or `#{x 1}`
with `x` bound to `1`, throws `IllegalArgumentException` `Duplicate key` when evaluated. A set
of constants such as `#{[1] '(1)}` keeps one member, as do `hash-map` and `hash-set`.

## Tagged literals

`#inst` and `#uuid` read through the oracle's default data readers while the form is read:
`#inst "2020-06-15T10:20:30.456+02:00"` is an instant (the oracle's `java.util.Date`),
`#uuid "550e8400-e29b-41d4-a716-446655440000"` a UUID (its `java.util.UUID`). A timestamp is
`yyyy`, then optionally `-MM`, `-dd`, `Thh`, `:mm`, `:ss` and a fraction, each part needing
the ones before it, then optionally `Z` or an offset `+hh:mm`/`-hh:mm`; a date before
1582-10-15 is the Julian calendar's, like the oracle's. A field out of range
(`#inst "2021-02-29"`), a timestamp of another shape and a malformed UUID are the oracle's
read errors, positioned after the string. [Instants and UUIDs](reference/instants.md) has
what the two values print, compare and answer.

```clojure
(prn #inst "2020-06-15T10:20:30.456+02:00" #uuid "1-1-1-1-1")
(println (str #inst "2020") (inst-ms #inst "1970-01-01T00:00:01Z"))
```

```
#inst "2020-06-15T08:20:30.456-00:00" #uuid "00000001-0001-0001-0001-000000000001"
Wed Jan 01 00:00:00 UTC 2020 1000
```

Any other tag is a library's: a `data_readers.clj` or `data_readers.cljc` at a source root
(a directory or jar on the [source path](semantics.md#projects-depsedn)) maps tag symbols to
the vars whose functions read them, and every one is merged as the oracle merges them at
startup (each `.clj` file before each `.cljc` one; only its first form counts, and a tag two
files give different vars is refused). A tagged literal calls its function on the form read
after it, while the source is read, and the answer stands in the literal's place: data (a
vector, map, set, record, string, number, keyword, `#inst`, `#uuid`, pattern) is that value,
a list or symbol is code. The function runs as the program compiles, on the JVM, so it may
call Java even for a wasm target. Like the oracle's startup, which names the var without
loading it, its namespace must be loaded by a form above the literal (an `ns` `:require`),
else the literal is `Attempting to call unbound fn`. An answer `nil` is the oracle's
`No dispatch macro`, one with no source spelling (an atom, a function, a host object other
than a UUID or Date) its `Can't embed object in code`. A data reader of `inst` or `uuid`
reads it ahead of the default; a tag no data reader reads has no reader function. At run
time `read-string` and `read` ask `*data-readers*` (the same map) first (see
[read-string](reference/read-string.md)).

```console
$ cat src/data_readers.clj
{geo/point my.geo/point}
$ cat src/my/geo.clj
(ns my.geo)
(defn point [[x y]] {:x x :y y})
$ cat src/app/main.clj
(ns app.main (:require [my.geo]))
(println #geo/point [1 2])
$ rontolisp src/app/main.clj
{:x 1, :y 2}
```
