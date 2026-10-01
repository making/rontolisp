# Clojure: interop-as-value + arrays + `*in*` (shcloj4 basics)

Difficulty: Medium (lowering + `java:` surface; JVM/interp only, wasm
refuses `java:` by design -- the refusal cases pin too. No new value model).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

| Probe | rontolisp today | oracle |
|---|---|---|
| `{KeyEvent/VK_LEFT [-1 0]}` (static field as map key/value; `snake.clj`, `atom_snake.clj` `dirs`) | `unknown name: KeyEvent/VK_LEFT` even with `(:import ...)` wired | int constant |
| `(every? Character/isWhitespace s)` (member as function value; `introduction.clj` `blank?`) | `unknown name: Character/isWhitespace` | boolean |
| `(map (memfn getName) files)` | works (`memfn` lowers) | -- (already green, regression pin) |
| `(System/currentTimeMillis)` / `(System/nanoTime)` (zero-arg static call; `pi.clj`, bench macros) | `NoSuchFieldException` (read as field; `Class/m` with no args is the field spelling, method needs `(. Class m)`) | long |
| `(make-array String 5)` + `(aset a 0 x)` + `(aget a 0)` (`interop.clj`) | `unknown name: make-array` | array ops |
| `(println *in*)` + `(.readLine *in*)` (hangman `take-guess`) | `unknown name: *in*` | stdin reader |

Notes: `ns` `:import` (single/list/vector/multi) and `(import ...)` already
work (verified); dotted/`java.lang`/imported class resolution for CALL
position already works (`Math/sqrt`, `String/valueOf`, `Thread/sleep`).
The gap is strictly VALUE position + arrays + the zero-arg static
call/field ambiguity + the missing `*in*` special.

## Design sketch

- Value-position `Class/member` and bare `Class/FIELD`: lower through the
  same `resolveClass` + `isClasslike` path call position uses, answering a
  zero-arg static-field read or a member-as-value lambda
  (`Character/isWhitespace` -> 1-arg lambda). Keep the b08 deviation
  (zero-arg static METHOD spells `(. Class m)`) or lift it: if the field
  read fails at macro time... no -- decide at lowering: prefer method when
  the class is known to have a zero-arg static method? Simplest honest
  rule: `(Class/m)` with no args tries the static method first via
  `java:call`, falling back to field -- document whichever wins.
- `make-array`/`aget`/`aset`/`alength`: lower to the existing array
  primitives (`make-array`/`aref`/`aset` equivalents the CL side already
  compiles on all four? verify -- general arrays exist per
  `.kb/array-literals.md`; only the Clojure spellings are new).
- `*in*`: bind to `*standard-input*` like `*out*` is `*standard-output*`
  (one-line seam + `binding`-rebindable special).
- Multi-interface `proxy` + `proxy-super` stay refused (documented):
  snake GUI files remain non-goals (b16).

## Acceptance

- `clojure-spec.yaml`: static field as value (with import), member as
  value over `every?`/`map`, zero-arg static call, `make-array` round-trip,
  `*in*` bound (read from a string stream in-test, never real stdin) --
  green on interpreter + JVM; wasm legs pin the `java:` refusal.
- Corpus pins: `introduction.clj` `blank?`, `interop.clj`
  `painstakingly-create-array`, hangman `take-guess` input shape (mocked
  `*in*`).
- `.kb/clojure-frontend.md` interop row widened (value position);
  doc pages en+ja.

## Depends on

b16 (harness). Independent of b17-b19/b21/b22.
