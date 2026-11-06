# i82. A feature pushed by one loaded file must reach the next file's `#+`

Difficulty: High

CL reads each file with `*features*` as it stands when the read happens. Here a push in one
file is seen by the run-time `*features*` but not by the read of the next file (measured
2026-11-06, interpreter):

    ;; a.lisp
    (eval-when (:compile-toplevel :load-toplevel :execute) (pushnew :my-feat *features*))
    ;; b.lisp
    #+my-feat (defun f () :full)
    #-my-feat (defun f () :none)

`(load "a.lisp") (load "b.lisp") (f)` answers `:NONE` (SBCL `:FULL`), and so does the same pair as
two `:serial` components of a system, while `(member :my-feat *features*)` is true afterwards.
`.kb/reader-features.md` ("A COMPUTED push decides the forms below it") records the same-file
case as done and the cross-file case as reaching "only through `*features*` at run time".

Consumer: cl-environments 20250622 (`src/walker/cl-environments.lisp` pushes
`:cl-environments-full`; every later walker file guards on it), so `generic-cl`,
`static-dispatch`, `cl-form-types` stop at
`The function CL-ENVIRONMENTS.CLTL2::INITIAL-DECLARATIONS is undefined`.

Interpreter: a file's read starts from the live `*features*`. Compile paths: the build image
holds the pushes of the files spliced before, so the next component's read can start from them
(`ReadTimeImage`, `FeaturePushes.computesAPush`). Watch `.kb/asdf.md`'s "A push is the FILE's
declaration" for `.asd` files: that binding is a deliberate deviation and stays.

Done when: the pair above answers `:FULL` on all four (ci-spec case, two files), and cl-environments
gets past `initial-declarations` (next stop: i88).
