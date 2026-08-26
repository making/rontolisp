# `linalg` has no cross product

Difficulty: Low

`linalg` covers numpy's vector surface -- `dot`, `outer`, `norm`, `matmul`,
`solve` -- but not `np.cross`, which is the one operation a 3-D program cannot
express as a composition of the others. Every consumer therefore hand-writes the
same six multiplies:

```lisp
(defun cross (a b)
  (vec3 (- (* (aref a 1) (aref b 2)) (* (aref a 2) (aref b 1)))
        (- (* (aref a 2) (aref b 0)) (* (aref a 0) (aref b 2)))
        (- (* (aref a 0) (aref b 1)) (* (aref a 1) (aref b 0)))))
```

That defun is now copied verbatim in `examples/browser/webgl-robot-arm/robot-arm.lisp`
and `examples/macos/metal-robot-arm.lisp` -- two independent programs, one for a
camera basis (`right = normalize(cross(forward, up))`, `up = cross(right, forward)`),
one for the linearized joint rotation `w x (p_j - p_i)` inside the damped-least-squares
Jacobian step. Both files make a point of holding every coordinate in a float array so
that the arithmetic reads as `linalg` calls; this is the single line where that breaks
down, and the comment in both says so.

## What to implement

`(linalg:cross a b)` with numpy's semantics as far as they are worth carrying:

- Two rank-1 operands of length 3 -> the length-3 cross product. This is the
  case every caller wants and the only one that must be exact about width: the
  result keeps the FIRST array operand's element type, like `add` (`.kb/linalg.md`,
  "element types"), so a `#f` pair answers `#f` and a `metal:uniform` / `objc:data`
  hand-off stays float32 with no conversion.
- Length-2 operands: numpy answers the SCALAR z of the implied 3-D product.
  Cheap, and it is what np.cross does; decide whether the divergence of returning
  a scalar from an otherwise array-valued member is worth it, or signal instead.
- Broadcasting over a stack (numpy's `axis` / `axisa` / `axisb` / `axisc`) is
  NOT worth carrying: no consumer here has one, and the axis keywords are the
  ugliest corner of np.cross.

Signal the usual `linalg:` shape error for any other rank or extent.

## The cost of a member

`.kb/linalg.md` names it: a defun in `linalg.lisp` written in canonical package
shape, the `defpackage` export, the `PackageRegistry` entry that must agree with
the library EXACTLY, a per-operator page under `reference/functions/` with a
runnable example in `doc/en` and `doc/ja`, a `_catalog.yaml` entry and a row in
`reference/functions.md`. No `--simd` seam: there is no cross kernel and a
three-element product would not pay for one.

## When it lands

Delete the hand-written `cross` from both robot-arm programs and the comment
that explains why it is there -- that deletion is the point of the item, and the
two files are the test that the signature is the right one.
