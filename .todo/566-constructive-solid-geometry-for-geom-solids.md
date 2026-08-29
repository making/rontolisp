# Constructive solid geometry: union, difference, intersection, section

Difficulty: High

Member 3 of `563-solid-modeling-and-a-3d-viewer.md`; depends on
`564-the-geom-package-transforms-scene-graph-and-solids.md`. Not started -- the spike
deliberately did not attempt it (`563-solid-modeling-and-a-3d-viewer/README.md`, "What the
spike did not settle").

## What it is for

`562` gives you solids you can construct by name and place. What it does not give you is a
solid that is a bracket: a plate with four bolt holes, a block with a slot milled in it, a
housing that is the outside minus the inside. Every one of those is a boolean of two
primitives, and without them the package models scenery rather than parts.

```lisp
(geom:union a b)          ; a ∪ b
(geom:difference a b)     ; a \ b
(geom:intersection a b)   ; a ∩ b
(geom:section solid plane)   ; the cross-section loops where a plane cuts a solid
```

Each answers a NEW solid and leaves its arguments untouched; each takes its arguments in
world coordinates, so `(geom:difference plate hole)` means what it looks like after both
have been placed.

## Why this is the hard one

Boolean operations on boundary representations are where solid modeling is actually
difficult, and the difficulty is entirely in the degenerate cases:

- two faces that are coplanar (a hole drilled exactly to the surface);
- a vertex of one solid lying exactly on a face or an edge of the other;
- a face pair that touches without crossing;
- near-degenerate intersections that a float32 tolerance classifies inconsistently on two
  sides of the same edge, producing a shell with a hole in it.

The last one is the real risk here, because `geom` is float32 throughout (`562`, and the
GPU reason for it). **Decide early whether the boolean pipeline runs in float64 and
narrows on the way out.** It probably must; `linalg` carries both widths and preserves
them, so this is a representation decision, not a rewrite. Measure the cost of the widening
rather than assuming it.

## Do

1. Choose the algorithm and write down why, before any code. The candidates are the classic
   face-splitting / classification pipeline (split each face of A by the faces of B,
   classify each fragment in/on/out of B, keep the fragments the operation selects, stitch)
   and a BSP-tree formulation (simpler to get right, much easier to make robust, worse
   output topology -- it fragments faces that did not need splitting). For a first landing
   the BSP formulation is likely the correct trade: it is a few hundred lines, it has no
   special cases, and `geom` already renders triangles rather than caring about face
   structure. Record the choice and the rejected one in `.kb/geom.md`.
2. A tolerance model, stated once and used everywhere. One `geom:*tolerance*` special, a
   documented default, and a note on what it means in the units the caller is working in
   (a package with no unit of length cannot pick an absolute epsilon for the user -- say
   whether the tolerance is absolute or relative to the operand's bounds, and make it the
   latter if that is what survives a 0.001-scale and a 1000-scale model).
3. `section` first, if it helps -- a plane against a solid is the same classification
   problem with one operand trivial, it is independently useful (a cross-section drawing),
   and it is far easier to test.
4. Tests are the deliverable as much as the code. Volume is the oracle: `(volume (union a
   b))` = `(+ (volume a) (volume b) (- (volume (intersection a b))))` for ANY pair, within
   the tessellation error `562` already measures. Add the degenerate cases explicitly --
   coplanar faces, a vertex on a face, two solids exactly touching, a hole exactly as deep
   as the plate is thick -- and assert what each does, including "signals" where that is
   the honest answer. A boolean that silently returns a broken shell is worse than one that
   refuses.
5. The result's normals and winding must satisfy `562`'s invariant, or `volume` on the
   result is wrong and the renderer draws it inside out. Assert it.
6. Keep the operand solids untouched, and record in the result what built it -- a
   `geom:history` accessor answering the operation and its operands is cheap here and is
   what lets a program re-run a model at a different parameter.

## Out of scope

Convex hulls, offsetting/shelling, filleting, and mesh repair for a `polyhedron` whose
facets were not a closed manifold to begin with. The last one is worth its own item the
first time someone imports a mesh.
