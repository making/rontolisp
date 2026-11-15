# j52. Starlark: the `bytes` and `set` types

Difficulty: Medium

Both are in the spec ("Bytes literals", "Bytes", "Sets", `bytes`, `set`, `bytes·elems`, the
sixteen `set·` methods) and neither is in the front end (`.kb/starlark-frontend.md`: a
`b"..."` literal is refused at its position), so nothing here is measured. A built-in is a
row of `StarlarkBuiltins` (a method behind its types, `"list|dict.pop(..)"`) and a fixed-arity
helper in `starlark.lisp`, which tests its receiver before anything else.

## bytes

- An immutable array of ints 0-255, type `"bytes"`. The reader (`StarlarkParser`, `.kb/starlark-frontend.md`) already lexes
  `b"..."`, `br`/`rb` and their escapes; this item gives the literal a value: non-escaped
  text is its UTF-8 encoding, `\x`/octal escapes are any byte, `\u` is the code point's
  UTF-8.
- `bytes(x)`: a bytes is itself, a string is its UTF-8 (an invalid element becomes U+FFFD),
  an iterable of ints is those bytes (out of range is an error), anything else is an error.
- `len`, `+`, indexing (an int), slicing (a bytes), `in` (an int 0-255 or a bytes
  subsequence; anything else is an error), hashable, totally ordered, true when non-empty,
  NOT iterable. Method: `elems`. `repr` is the `b"..."` form.
- Representation: a packed octet vector (`.kb/packed-integer-vectors.md`) behind a struct,
  so it is distinct from a list and from a string on every backend. A string's elements are
  code points (`.kb/starlark-frontend.md`, "The decisions"), so `bytes(str)` is the UTF-8 of
  its code points and no string holds half a character.

## set

- Mutable, insertion-ordered, elements hashable exactly as dict keys are; type `"set"`. No
  literal. `set()` / `set(iterable)`.
- Build it on the dict's table (the prototype's normalized key -> cell), so hashing, order,
  the frozen flag and the mutation-during-iteration check are one mechanism, not two.
- `in`, `len`, iteration in insertion order (re-adding keeps the place), truthiness.
- `==`/`!=` ignore order; a set never equals a list; `<` etc. and sorting sets are errors.
- `|`, `&`, `-`, `^` answer a new set, left operand's elements first; `|=`, `&=`, `-=`,
  `^=` update the left set IN PLACE (the lowering's augmented assignment must not rebind a
  fresh value: `+=` already goes through `%starlark-add-in-place`, the other operators
  through the plain one). `|` also means dict union and int or:
  one dispatch.
- Methods: `add`, `clear`, `difference`, `difference_update`, `discard`,
  `intersection`, `intersection_update`, `isdisjoint`, `issubset`, `issuperset`,
  `pop`, `remove`, `symmetric_difference`, `symmetric_difference_update`, `union`,
  `update`.
- Freezing (`.todo/j49`) covers a set like a list.

## Test

The conformance corpus (`StarlarkSpecE2eTest#conformance`) barely covers either type, so add cases to that
driver from the spec's own examples, on the four backends.
