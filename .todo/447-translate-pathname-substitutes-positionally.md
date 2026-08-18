# 447. `translate-pathname` substitutes its captures positionally

Difficulty: Medium

Came out of `.todo/441` (wild pathname components), not out of the `.todo/436`
spike. Read `.kb/pathnames.md` ("The algebra over the flat namestring") first.

## The defect

CL's `translate-pathname` maps DIRECTORY to directory, NAME to name and TYPE to
type. This one matches over the flat namestring and substitutes what each
wildcard captured into the to-wildcard left to right, so a to-wildcard holding
fewer wildcards than the from-wildcard takes the wrong ones:

```lisp
(namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*"))
;; rontolisp => "x/a-y.b"
;; SBCL      => "x/b-y.c"
```

The literal `x` in the to-wildcard consumes no capture, so every later
substitution is off by one. `LispEvaluatorTest#translatePathnameSubstitutesThe`
`CapturedWildcards`, the `pathname-algebra-over-the-flat-namestring` ci-spec
case and the `translate-pathname` doc page all currently PIN the wrong answer
(the doc page names the divergence; the test's "checked against SBCL" comment
does not hold for that one form).

## Why it survives

Every shape a library actually writes has the SAME wildcard sequence on both
sides -- `"/src/**/*.*"` -> `"/out/**/*.*"`, `"src/*.lisp"` -> `"build/*.fasl"`
-- and there positional and component-wise agree. `**/` became a single token in
todo-441, which is what keeps the wild-inferiors shapes right.

## The fix

Split source, from-wildcard and to-wildcard with `%pathname-split` +
`%path-dir-parts`, match component by component (a `**` component consuming a
RUN of source directories), then substitute per component. `%wild-captures`
stays the within-a-component matcher.

## Watch

- The three pins above change together with the doc pages (en + ja).
- `uiop:translate-pathname*` rides on this; its ci-spec case must stay green.
- Keep `%wild-captures`'s `:no-match` failure answer -- it is what
  `translate-pathname` reports the error from.

## Acceptance

The five shapes in the pins plus the asymmetric pair above agree with SBCL on
all four backends.
