# h50. cl-fad: implementation-only branches and `logical-pathname-translations` (blocks hunchentoot)

Difficulty: Medium

Found 2026-10-30 probing `hunchentoot` and `cl-fad`. cl-fad is a per-implementation
portability layer:

1. `temporary-files.lisp:37`: `getenv` has no portable arm.
   `#-(or abcl ... sbcl ...) (error "~S is not supported on your implementation" 'getenv)`.
   It runs at load time.
2. With that one arm patched to `uiop:getenv`, the next failure is
   `The function (SETF CL-FAD::LOGICAL-PATHNAME-TRANSLATIONS) is undefined`. That is
   standard CL. `logical-pathname-translations` / `load-logical-pathname-translations`
   are also absent (h39 list).
3. More `#-(or :sbcl :cmu ...)` arms remain in `fad.lisp` (lines 117, 153, 294:
   directory listing, `directory-exists-p`, `delete-directory`). They are not reached yet.

cl-fad matches the `.todo/147` shim criterion (it absorbs implementation differences). Choose
between a tier-4 derived-forms rewrite of those arms onto `uiop`/rontolisp primitives
(`.kb/asdf.md` "The substitution ladder") and a tier-1 shim. Implement
`(setf logical-pathname-translations)` for real or as a recorded table
(`.kb/pathnames.md`). hunchentoot's next blockers after cl-fad are unmeasured.
