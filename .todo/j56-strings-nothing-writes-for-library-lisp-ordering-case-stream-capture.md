# j56. Library Lisp needs strings nothing writes: a host ordering of two strings, a case fold and a stream capture that are not mutable

Difficulty: Medium

`.kb/starlark-frontend.md`, "The run-time library's rules": a Starlark string is the
host's immutable string, and `%string-concat`, `%substring` and `%decimal-piece` make
one. Three things the library still does the long way (2026-11-16):

- **Ordering.** `%starlark-string-compare` reads both strings character by character.
  On wasm a character read is an allocation and `(char-code (char s i))` about 30 ns, so
  `sorted` of 50,000 short strings was 64% of `sorting.star` there; the library now sorts
  keys of at most 64 characters by the lists of their codes, read once, and anything
  longer still pays per comparison. `string<` is the same loop behind keywords and a
  consed pair (`%string-compare`, the prelude).
- **Case and trim.** `upper`, `lower`, `capitalize` and `title` call the public producers
  and `%normalize-string` the result: string -> character vector -> string
  (`_strToCharVec` 2% of `methods.star` on the JVM for each of `upper` and `lower`).
- **A stream's contents.** `repr` of a container and `title` write to a string stream,
  whose contents come back mutable (`%string-stream-contents` is wrapped at every site,
  `.kb/string-write-runtime.md`), then normalize.

## Do

- `(%string-order a b)`: -1, 0 or 1 by code point, on the interpreter, the JVM and wasm
  (two `TYPE_STRING`s compare as bytes: UTF-8 order is code point order; the JVM compares
  code points, not UTF-16 units). `.kb/adding-primitives.md`; `%string-search` is the
  precedent for an arm a backend may decline.
- The unwrapped twins of the case functions and of the stream capture, as `%substring` is
  `subseq`'s.
- Use them in `starlark.lisp` (drop the code-list sort) and in the prelude's
  `%string-compare`; measure `sorting.star` and `methods.star` before and after
  (`.todo/artefacts/j54-starlark-faster-than-cpython/time.sh`).
