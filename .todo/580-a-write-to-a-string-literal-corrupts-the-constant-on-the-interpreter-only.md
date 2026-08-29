# A write to a string literal corrupts the constant on the interpreter only

Difficulty: Medium

Found 2026-08-29 alongside `.todo/578`, which closed the same class of divergence for the
SELF-EVALUATING ARRAY literal (`.kb/array-literals.md`) by materializing a fresh array per
evaluation on the interpreter. A string literal was left out of that fix and needs a
DIFFERENT one -- 578's answer is the wrong shape here, and that is the whole point of this
item.

## The measurement (2026-08-29, all four backends)

```lisp
(defun fs () "abc")
(print (eq (fs) (fs)))
(let ((a (fs))) (setf (char a 0) #\Z) (print a))
(print (fs))
```

| backend | `(eq (fs) (fs))` | the write | the second `(fs)` |
|---|---|---|---|
| interpreter | `T` | `"Zbc"` | `"Zbc"` -- the constant in the source is gone |
| JVM class | `T` | `"Zbc"` | `"abc"` |
| WASM preview 1 | `T` | `"Zbc"` | `"abc"` |
| WASM component | `T` | `"Zbc"` | `"abc"` |

## Why 578's fix does not transfer

For the array literal the backends disagreed about `eq` AND about the write, so making the
interpreter allocate per evaluation settled both at once. Here **`eq` already agrees**: the
literal is shared on all four. Only the write diverges, because the compile paths do not
write into the literal at all -- `(setf (char a 0) ...)` on a place that is not an
allocated buffer lowers to the setq-rebuild (`.kb/string-write-runtime.md`,
`%schar-set-runtime`), which rebinds the VARIABLE and leaves the constant alone. The
interpreter's `LispString` is a mutable buffer and the write lands in it.

So materializing a fresh string per evaluation would FIX the write and BREAK the `eq`
answer that currently holds on all four. The fix has to make the interpreter's write local
to the binding, not make its literal fresh.

## Do

- Give the interpreter the compile paths' rule: a write through a place holding a string
  LITERAL rebinds that place instead of mutating the object, so the source constant
  survives and `eq` on the literal stays `T`. The compile paths' own limits come with it
  and must be written down, not silently inherited -- the place must be a variable, and the
  update is invisible through an alias (`.kb/asdf.md`, cl-base64 item 3).
- Decide what happens where the compile paths cannot rebind either (a literal written
  through a function argument, or as a bare `(setf (char "abc" 0) ...)`). Whatever the four
  backends do there must be the same thing, and it is currently unmeasured.
- Pin `eq` AND the write in `ci-spec.yaml`, the way 578 pinned
  `array-literal-freshness-cross-backend`. `.kb/string-write-runtime.md`'s "Pinning tests"
  section is where the new case is named.
- Check the rest of the self-evaluating family for the same gap while here: a character, a
  number and a bit vector were covered by 578, but a PATHNAME literal and a
  `#(...)`-inside-a-string case were not measured.

## Related

- `.todo/579` -- the same divergence under `quote`, where a cons has it as much as an
  array. That one needs a head for the interpreter's live-value splice first; this one does
  not, because nothing splices a string literal.
- `.kb/array-literals.md` -- the measurement that closed the array half, including the four
  places the tree deliberately commits to a mutable literal.
