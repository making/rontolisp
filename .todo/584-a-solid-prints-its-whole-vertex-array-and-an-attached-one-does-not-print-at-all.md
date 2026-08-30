# A solid prints its whole vertex array, and an attached one does not print at all

Difficulty: High

## Measured 2026-08-30, interpreter, `java -jar`

`(print (geom:box 2 :label "b"))` writes **520 characters**: every slot of
`geom:solid`, including the full `:VERTICES` array and the full `:FACETS` list.
`(geom:cylinder :radius 3.0)` writes **2,180**. An arrow is worse, and a solid whose
mesh cache has been built carries 18 floats per triangle in `:MESH-CACHE` --
142 triangles for a default `geom:arrow`. `:USER-DATA` holds a renderer's GPU
buffer handles. None of it is what a caller wanted to see.

**And a solid in a scene graph cannot be printed at all.** `geom:node` has a
`parent` slot and a `children` slot, so after `(geom:attach a b)`:

```lisp
(print b)   ; => java.lang.StackOverflowError
```

`LispInstance.render` -> `LispInstance.print` -> ... walks parent, then the
parent's children, then back. 61 KB of stack trace, no output. That is the more
serious half: the verbosity is noise, this is a value the program cannot print.

## What to decide

1. **`print-object` methods for `geom`'s classes.** `geom:solid` should print
   something a caller can read -- the label if it has one, and the two counts that
   say what it is (`#<GEOM:SOLID "b" 8 vertices 6 facets>` is the shape, the exact
   spelling is yours). `geom:node` and `geom:transform` want the same treatment;
   decide which of the three need a method and say why in `.kb/geom.md`.

   **The cost is real and must be measured, not assumed.** A `defmethod
   print-object` anywhere in a spliced library turns on the printer route
   (`printObjectTags`, `.kb/clos.md`, "`print-object` -- the printer consults it")
   for EVERY program that splices `geom`, on all three compile backends: every
   `print` / `princ` / `format ~A` in that program stops being the raw renderer and
   becomes `%print-object-str`. Measure what that does to a geom program's `.wasm`
   and `.class` size, and to programs that do NOT use geom (it must be zero there).
   If the cost is not payable, the finding is the deliverable -- say so with the
   numbers and propose the alternative you would take instead.

2. **The cycle is a defect of the DEFAULT printer, not of `geom`.** Any two
   instances that point at each other stack-overflow every printing operator, with
   no `geom` in sight. A `print-object` method on `geom:node` hides this
   particular instance of it and fixes nothing. Decide what the default renderer
   should do -- CL's answer is `*print-circle*` and `*print-level*`, and
   `.kb/pretty-printer.md` will say which of those already exist here -- and
   whether that is this todo or a separate one. **Whatever you decide, a cycle
   must not be a `StackOverflowError`**: either it prints something finite or it
   signals a condition a program can handle. Behaviour must be the same on all
   four backends (`.kb/clos.md`'s generated `%print-object-str` walk is the
   compile-path half and has to move with the interpreter's `LispInstance`).

## Closing conditions

- A failing test first for each half.
- Cross-backend pinning where behaviour changed: a ci-spec case, byte-identical
  on interpreter / JVM class / WASM preview 1 / WASI 0.3 component.
- Size measurements recorded in the `.kb` file that owns the claim.
- Docs mirrored en/ja if a printed representation any doc shows changes; the
  solid-modeling guide and the `geom` package page both print solids.
