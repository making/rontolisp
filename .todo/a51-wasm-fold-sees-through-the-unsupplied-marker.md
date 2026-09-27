# Let the wasm type-test fold see through the UNSUPPLIED marker

Difficulty: Medium

A physical optional's prologue is `(if (%supplied-p p) p default)`, and on wasm
`%supplied-p` is `ref.eq p (global.get <raw-local sentinel>)` (`WasmPhysicalArgs`,
`.kb/lambda-lists.md`, "Optional arguments travel as parameters"). `WasmRefTypeFolder`
treats `ref.eq` as opaque, so the optional's variable keeps `TYPE_CELL` (the marker's type)
in its set whatever the call sites pass, and the arm the calls never take stays:

| program (Preview 1 bytes) | stepped rest list | physical optional |
|---|---|---|
| `(defun f (a &optional (b 2)) (+ a b)) (print (f 1))` | 981 | 1,330 |

The old prologue read `(car rest)` of a rest list the folder proved nil and folded `b` to
the constant 2; now `+` keeps its non-fixnum arms (the ratio constructor among them). Where
the optionals are passed the new shape is smaller (two defuns: 4,556 -> 3,553), so this is
the one-sided case.

Either route decides the test: a dedicated singleton marker type (`(sub final (struct))`,
one instance in an immutable global, `%supplied-p` a `ref.test` the fold already narrows
on) appended so no existing type index moves; or allocation-site identity in the fold for
an immutable global initialized by `struct.new` (a pseudo-member "that global's object",
a subtype of its type). Pin with a size test on the program above.
