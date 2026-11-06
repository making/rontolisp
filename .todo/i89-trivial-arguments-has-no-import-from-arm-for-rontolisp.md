# i89. trivial-arguments: no `:import-from` arm for rontolisp

Difficulty: Low

`trivial-arguments` (20260101) imports its MOP names from a per-implementation package with no
fallback arm:

    (:import-from #+abcl #:mop ... #+sbcl #:sb-mop ... #:method-generic-function ...)

Here no arm fires, so `#:method-generic-function` is read as the package: `No such package:
METHOD-GENERIC-FUNCTION` (measured 2026-11-06). Recorded in `.todo/152` round 1 for spinneret;
it also blocks typo (so petalisp). With a local `#+rontolisp #:closer-mop` arm plus a closer-mop
dependency, both get further (typo stops next on i90, spinneret on in-nomine's `describe-object`).

Choose the substitution tier (`.kb/asdf.md`, "The substitution ladder"): a derived form for this
one `defpackage` is likely enough, since closer-mop's shim exports the three names. Also offer
the arm upstream. Check `arglist` against SBCL on a few functions and generics.
