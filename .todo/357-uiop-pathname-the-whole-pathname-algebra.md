# `uiop/pathname`: the whole pathname algebra

Difficulty: High

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **4 / 50 (`merge-pathnames*`, now Lisp source and REAL on all four backends,
`ensure-directory-pathname`, and -- since `.todo/375` -- `absolute-pathname-p` +
`ensure-absolute-pathname`, which rove's `resolve-file` calls on EVERY `deftest`
once `*load-pathname*` is real, so a stub there was a hard error)**. The two new
ones carry a deliberate divergence to keep in mind for the rest of this family:
rontolisp absolutizes NOWHERE, so `ensure-absolute-pathname` answers a relative
path as itself where upstream signals (`.kb/uiop.md`, `doc/*/reference/uiop.md`).

Depends on `.todo/353`, `.todo/354`. The largest single-subject item of the
twelve, and the one with a real prerequisite outside uiop.

50 externals; four present (`merge-pathnames*`, `ensure-directory-pathname`,
`absolute-pathname-p`, `ensure-absolute-pathname`). The **46** missing (the list
below still names the last two; strike them when you work it):

```
ABSOLUTE-PATHNAME-P CALL-WITH-ENOUGH-PATHNAME
DENORMALIZE-PATHNAME-DIRECTORY-COMPONENT DIRECTORIZE-PATHNAME-HOST-DEVICE
DIRECTORY-PATHNAME-P DIRECTORY-SEPARATOR-FOR-HOST ENOUGH-PATHNAME
ENSURE-ABSOLUTE-PATHNAME ENSURE-PATHNAME FILE-PATHNAME-P HIDDEN-PATHNAME-P
LOGICAL-PATHNAME-P MAKE-PATHNAME* MAKE-PATHNAME-COMPONENT-LOGICAL
MAKE-PATHNAME-LOGICAL MERGE-PATHNAME-DIRECTORY-COMPONENTS *NIL-PATHNAME*
NIL-PATHNAME NORMALIZE-PATHNAME-DIRECTORY-COMPONENT
*OUTPUT-TRANSLATION-FUNCTION* PARSE-UNIX-NAMESTRING PATHNAME-DIRECTORY-PATHNAME
PATHNAME-EQUAL PATHNAME-HOST-PATHNAME PATHNAME-PARENT-DIRECTORY-PATHNAME
PATHNAME-ROOT PHYSICAL-PATHNAME-P PHYSICALIZE-PATHNAME RELATIVE-PATHNAME-P
RELATIVIZE-DIRECTORY-COMPONENT RELATIVIZE-PATHNAME-DIRECTORY SPLIT-NAME-TYPE
SPLIT-UNIX-NAMESTRING-DIRECTORY-COMPONENTS SUBPATHNAME SUBPATHNAME* SUBPATHP
TRANSLATE-PATHNAME* UNIX-NAMESTRING *UNSPECIFIC-PATHNAME-TYPE* WILDEN *WILD*
*WILD-DIRECTORY* *WILD-FILE* *WILD-FILE-FOR-DIRECTORY* *WILD-INFERIORS*
*WILD-PATH* WITH-ENOUGH-PATHNAME WITH-PATHNAME-DEFAULTS
```

## The prerequisite -- DONE (2026-08-15, `.todo/036`)

The CL pathname model was the prerequisite and it has been widened, so this item
no longer has to. `wild-pathname-p`, `pathname-host` / `-device` / `-version`,
`translate-pathname`, `translate-logical-pathname`, `logical-pathname`,
`enough-namestring`, `rename-file` and the special `*default-pathname-defaults*`
(`#P""`, a genuine dynamic variable on all four backends) are all real -- prelude
Lisp over the namestring, one definition per operator. Read the "algebra over the
flat namestring" section of `.kb/pathnames.md` before writing the uiop layer over
them; in particular `%wild-captures` is the capturing matcher
`translate-pathname` (and therefore `translate-pathname*`) is built on, and
`%wild-component-p` is the one "this component is wild" rule.

Roughly a third of the list above is written directly over that model: every
`*wild-*` constant, `wilden`, `translate-pathname*`, `pathname-host-pathname`,
`directorize-pathname-host-device`, `physicalize-pathname`, and
`with-pathname-defaults`. All of them now have something to stand on.

`uiop::get-pathname-defaults` is what is LEFT of the symptom: it is an internal
symbol answering the literal `""` because it predates the special. It is external
in real uiop (`uiop/filesystem`, `.todo/358`) and must now READ
`*default-pathname-defaults*` (whose namestring is `""`, so nothing observable
changes today) rather than stay a constant -- that retirement is this item's.

## Then the algebra

Everything else here is portable computation over the model and can come from
`pathname.lisp` nearly verbatim, once the components are there. The names real
libraries actually call -- and so the ones worth a doc detail page --
are `subpathname`, `subpathp`, `parse-unix-namestring`, `unix-namestring`,
`ensure-pathname`, `enough-pathname`, `pathname-directory-pathname`,
`pathname-parent-directory-pathname`, `split-name-type`, and the four
predicates (`absolute-` / `relative-` / `directory-` / `file-pathname-p`).

**Logical pathnames**: rontolisp has none, and the CL half already committed to
that (`.todo/036`): `translate-logical-pathname` is the identity and
`logical-pathname` always signals, because no logical host can be defined and no
`logical-pathname-translations` table exists. So `logical-pathname-p` answers
nil, `physical-pathname-p` t, `physicalize-pathname` is identity, and
`make-pathname-logical` / `make-pathname-component-logical` are
`not-implemented-error`. Write that into `.kb/uiop.md` with the reason (pointing
at the `.kb/pathnames.md` paragraph), so the next visitor can see whether it
still holds.

**All four backends.** These are pure functions over a value type, so the bar is
the full four -- and the fold in `cli/CompileTimePathnameFolder` should learn the
uiop constructors it can fold (`subpathname` over literals is a literal), the
same way it already folds `merge-pathnames`.

## Gate

`UiopCoverageTest` reports `uiop/pathname 50/50` and `merge-pathnames*` no
longer signals on a compiled backend. `ci-spec.yaml` grows a case running
`subpathname` / `subpathp` / `parse-unix-namestring` / `enough-pathname` /
the four predicates on all four backends -- this family is exactly the kind that
diverges silently between interpreter and codegen.
