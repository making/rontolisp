# A pathname is not a distinct value, so `pathnamep` claims every string

Difficulty: High

`(pathnamep "hello")` is `T` here, and so is `(typep "hello" 'pathname)`. That
is deliberate and documented (`LispNames.PATHNAMEP`, `.kb/directory-listing.md`:
a rontolisp pathname IS its namestring, and the two predicates must agree as CL
requires). It is also the reason a third-party library cannot tell a FILE from
TEXT, and the codebase has already left the re-evaluation trigger for it, in
`LispMacroExpander.pathnameClauseYields`:

> This is a heuristic standing in for a type rontolisp does not have.
> **Re-evaluation trigger**: give rontolisp a distinct pathname VALUE (an
> instance carrying its namestring, the way defstruct instances already work on
> every backend) and this rule -- and the whole "is a namestring a pathname"
> question -- disappears with it.

This item is that trigger firing. What made it fire is lack's response
constructor, which is not a `typecase` and therefore out of reach of the
heuristic:

```lisp
;; lack-20260101-git/src/response.lisp
(cond ((and no-body (not body)) nil)
      ((or (consp body) (pathnamep body) (and (not (stringp body)) (vectorp body)))
       (list body))          ; a pathname body -> (status headers #P"...")
      (t (list (list body))))  ; a string body -> (status headers ("text"))
```

A STRING body takes the pathname branch here, so `finalize-response` answers
`(200 () "hello")` where every other implementation answers `(200 () ("hello"))`
— and a bare-string body is exactly what rontolisp's transport refuses, on
purpose, for exactly this reason (`.kb/http-server.md`, "The response
contract"). Measured, SBCL 2.6.5 vs the interpreter, same ningle sources:

| | SBCL | rontolisp |
| --- | --- | --- |
| string controller | `(200 NIL ("Welcome to ningle!"))` | `(200 NIL "Welcome to ningle!")` |
| list controller | `(200 (:CONTENT-TYPE "text/plain") ("as-list"))` | same |
| 404 | `(404 NIL (NIL))` | same |

Every ningle controller that returns a string — the first line of ningle's own
README — becomes a 500 (`.todo/300`). The same collapse points a second gun the
other way: `lack.util:content-length` is
`(etypecase body (list ...) (pathname (with-open-file (in body) (file-length in))) ...)`,
so a string body would send rontolisp to OPEN A FILE NAMED BY THE BODY TEXT.
And `lack-app-file` answers `(200 (...) FILE)` with a real pathname, which the
transport today cannot serve at all — it arrives as a string and is refused.

## The fix is the value, not the predicate

Flipping `pathnamep`/`typep` to answer NIL instead ("nothing is a pathname")
makes `finalize-response` byte-identical to SBCL — verified by spike, all four
backends, ningle included — and it is tempting because it is two lines and the
blast radius in this repository is three pinned assertions and nothing else
(`./mvnw test` with the predicate flipped: 6340 run, 3 failures —
`doc/en/reference/functions/pathnamep.md`'s own example,
`JvmLispCompilerTest.compileAndRunLiteStreamBuiltins`,
`WasmLispCompilerIntegrationTest.liteBuiltinsResidue`; no library, no E2E). It is
still wrong: it re-breaks what the current answer was introduced to fix, mito's
`(check-type directory pathname)` in `migrate` and its
`(etypecase file (null ...) (pathname ...))` in `migration-status`, both of
which are handed values that came out of `uiop:directory-files`. Those go
through `typep`/`check-type`, and `MitoE2eTest` is opt-in
(`RONTOLISP_POSTGRES_E2E=1`), so the green suite above is not evidence that they
survive — it is evidence that nothing else in the repository looks. The
predicate has no answer that is right for both call sites, because the two call
sites are asking about two different types that rontolisp has merged.

So: give rontolisp a pathname VALUE — an instance carrying its namestring, like
a defstruct instance, present on all four backends.

- `#P"..."`, `pathname`, `make-pathname`, `merge-pathnames`, `parse-namestring`,
  `truename`, `probe-file`, `directory`, `uiop:directory-files` and friends
  answer one; `namestring` / `native-namestring` unwrap it.
- Everything that takes a path keeps taking a STRING too (`open`,
  `with-open-file`, `load`, `probe-file`, `directory`, `ensure-directories-exist`
  …) — pathname support is additive, no existing program changes.
- `pathnamep` and `(typep x 'pathname)` become true for exactly that value.
- `pathname-name`/`-type`/`-directory` get something real to decompose;
  `.todo/222` (`make-pathname` has no runtime form on the compile paths) folds
  into this.
- `LispMacroExpander.pathnameClauseYields` and its `matchesAString` helper are
  DELETED, along with the jzon/mito special-casing they exist for.
- The Clack transport gains a real pathname arm: `%http-body-string` (and the
  interpreter's Java mirror `LispEvaluator.responseBody`) can serve a
  `lack-app-file` / `:static` body instead of refusing it, and can keep refusing
  a bare string with a clear conscience.

**Minimum slice that unblocks `.todo/300`**: the value plus the predicates plus
the producers `directory` / `uiop:directory-files` / `#P` — enough that
`finalize-response` shapes a string body correctly AND mito's `check-type` still
passes. Serving a pathname response body can follow.

## Also correct while here

`.kb/http-server.md` and the `http-response-normalizer` case in `ci-spec.yaml`
both say the two-element bodyless response is "what lack's `finalize-response`
answers for every ningle 404". It is not: ningle's `make-context` calls
`(make-response app 200 ())`, whose method passes the body argument on, so
`has-body` is true and a 404 is the three-element `(404 () (NIL))` — measured on
SBCL and on all four backends here. The two-element form is still legal and
still worth pinning; only the attribution is wrong.

## Work

- Design the value (a dedicated `LispVal` case vs. a registered struct type) and
  land it on the interpreter, the JVM and both WASM backends together.
- Sweep every producer/consumer of paths listed above; a path-taking builtin
  must accept both spellings.
- Delete the two heuristics; re-run the jzon and mito suites that motivated them.
- Pins: `ci-spec.yaml` for `pathnamep`/`typep`/`namestring`/`#P` round trips on
  all four backends, plus the `finalize-response` shape table above.
- `.kb`: `directory-listing.md` (the model changes), `http-server.md` (the
  response contract and the 404 attribution), `clack.md` (the bare-string
  paragraph's reason changes).

## Done when

`(pathnamep "x")` is NIL, `(pathnamep #P"x")` is T, mito's migration path still
passes, and lack's `finalize-response` answers what SBCL answers for a string,
a list and a pathname body on all four backends.
