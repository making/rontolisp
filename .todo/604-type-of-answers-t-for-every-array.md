# type-of answers T for every array, and a compound array type specifier never matches

Difficulty: Medium

Found enabling array-operations (`.kb/asdf.md`). Against SBCL 2.2.9:

```lisp
(type-of (make-array 4))                       ; T    SBCL: (SIMPLE-VECTOR 4)
(type-of (make-array 4 :element-type 'single-float))   ; T  SBCL: (SIMPLE-ARRAY SINGLE-FLOAT (4))
(type-of (make-array '(2 2) :element-type 'double-float)) ; T  SBCL: (SIMPLE-ARRAY DOUBLE-FLOAT (2 2))
(type-of #*101)                                ; T    SBCL: (SIMPLE-BIT-VECTOR 3)
(typep (make-array '(2 2) :element-type 'single-float)
       '(simple-array single-float (2 2)))     ; NIL  SBCL: T
```

`T` is a legal `type-of` answer in the letter of CLHS (every object is of type
`T`), but it is useless: nothing can tell a vector from a matrix, and it is the
only array answer any implementation gives that carries no information. The
`typep` line is a plain wrong answer -- the object IS an array of that element
type and those dimensions.

The element type is already tracked where a PACKED representation exists
(`(array-element-type (make-array 4 :element-type 'single-float))` answers
`SINGLE-FLOAT`, `(unsigned-byte 8)` answers itself), so the work is to (1) build
the `(simple-array ET (dims))` / `(simple-vector N)` specifier from an array value
in `type-of`, and (2) teach the type test to take a compound array specifier with
an element type and a dimension list (or `*`).

Out of scope, and it will keep failing after this lands: `(array-element-type
(make-array 4 :element-type 'fixnum))` answers `T` here where SBCL answers
`FIXNUM`. That is conformant -- `array-element-type` returns the UPGRADED element
type and there is no fixnum-specialized array to upgrade to.

This is now the WHOLE remainder of array-operations' own clunit2 suite: 11 of the
11 assertions it still fails here (208/219 against SBCL's 219/219, once
`.todo/603` landed the rank-0 array), 2 of which are the `fixnum` line above.

A rank-0 array exists since `.todo/603`, so `type-of` has to answer for it too:
`(type-of (make-array nil))` is `(SIMPLE-ARRAY T NIL)` in SBCL -- the dimension
list is `NIL`, not a size. The related ATOMIC gap (`vectorp` and `(typep a
'vector)` answering `T` at every rank) is `.todo/605`; this item owns the
COMPOUND specifier and `type-of`.

Behavior must be identical on all four backends: `.kb/declarations-type-checks.md`
owns the type lattice and names the pinning tests; add a ci-spec case, then
re-run array-operations' suite and move the number in `.kb/asdf.md`.
