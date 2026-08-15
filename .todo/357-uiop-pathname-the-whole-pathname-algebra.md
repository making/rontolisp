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

## The prerequisite

`.kb/pathnames.md` "Known lite edges" is explicit that `wild-pathname-p`,
`pathname-host` / `-device` / `-version`, `translate-pathname` and
`*default-pathname-defaults*` **do not exist** (`.todo/036`). Roughly a third of
the list above is written directly over them: every `*wild-*`
constant, `wilden`, `translate-pathname*`, `pathname-host-pathname`,
`directorize-pathname-host-device`, `physicalize-pathname`, and
`with-pathname-defaults`.

Do not route around that. Widen the CL pathname model first -- as a scoped part
of THIS item, not as a `.todo/036` hand-off -- because a uiop layer that
pretends there is no host/device/version component will have to be rewritten the
day `.todo/036` lands. The `.todo/036` non-goals are its author's scope, not a
constraint on this one (see CLAUDE.md, working principles).

`uiop::get-pathname-defaults` is the visible symptom: it is an internal symbol
answering the literal `""` because there is no `*default-pathname-defaults*`.
It is external in real uiop (`uiop/filesystem`, `.todo/358`) and cannot stay a
constant once the special exists.

## Then the algebra

Everything else here is portable computation over the model and can come from
`pathname.lisp` nearly verbatim, once the components are there. The names real
libraries actually call -- and so the ones worth a doc detail page --
are `subpathname`, `subpathp`, `parse-unix-namestring`, `unix-namestring`,
`ensure-pathname`, `enough-pathname`, `pathname-directory-pathname`,
`pathname-parent-directory-pathname`, `split-name-type`, and the four
predicates (`absolute-` / `relative-` / `directory-` / `file-pathname-p`).

**Logical pathnames**: rontolisp has none. `logical-pathname-p` answers nil,
`physical-pathname-p` t, `physicalize-pathname` is identity, and
`make-pathname-logical` / `make-pathname-component-logical` are
`not-implemented-error`. Write that into `.kb/uiop.md` with the reason, so the
next visitor can see whether it still holds.

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
