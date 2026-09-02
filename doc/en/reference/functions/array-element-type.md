# array-element-type

`(array-element-type array)`

Returns the array's element type — the **upgraded** one, so the answer is the type the array actually holds rather than the spelling the program used. A packed float array answers `double-float` or `single-float`, and a packed unsigned-integer vector ([`make-array`](make-array.md) with `:element-type '(unsigned-byte 8|16|32)` on a rank-1 array, or a [`concatenate`](concatenate.md) whose result type spells that element type) answers its real `(unsigned-byte n)` specifier. A string — a vector of characters — answers `character`.

A general array **remembers** an element type it was asked for even where no specialized representation exists for it: `character` and the three `(unsigned-byte n)` widths above rank 1, and any of them combined with `:fill-pointer`/`:adjustable`. The representation degrades, the declared type does not — and the array's unsupplied elements take that type's own zero rather than `nil`. An element type with nothing to upgrade to (`fixnum`, `integer`, `bit`, a class) upgrades to `t`. A displaced view owns no storage, so it answers its **target's** element type, resolved through the whole displacement chain -- which is the view's own declared `:element-type`, since [`make-array`](make-array.md) requires the two to be type-equivalent. A view's answer does not change when the view stops being one: [`adjust-array`](adjust-array.md) and a [`vector-push-extend`](vector-push-extend.md) that outgrows the view both un-displace it, and the answer is recorded rather than dropped.

```lisp
(array-element-type "abc") ; => CHARACTER
(array-element-type (make-array 3)) ; => T
(array-element-type (make-array 3 :element-type '(unsigned-byte 8))) ; => (UNSIGNED-BYTE 8)
(array-element-type (make-array '(2 2) :element-type '(unsigned-byte 8))) ; => (UNSIGNED-BYTE 8)
(array-element-type (make-array 4 :element-type 'double-float :fill-pointer 0)) ; => DOUBLE-FLOAT
(array-element-type (make-array 3 :element-type 'fixnum)) ; => T
```
