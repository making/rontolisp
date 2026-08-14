# `uiop/package`: the symbol and package surgery family

Difficulty: High

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **3 / 37 across the three package sub-packages (`symbol-call`, `define-package`, `add-package-local-nickname`)**.

Depends on `.todo/353`, `.todo/354`. The item with the deepest coupling to
rontolisp's own model, and the one to read `.kb/packages.md` +
`.kb/symbol-runtime-api.md` + `.todo/156` before starting.

37 externals across the three package-ish sub-packages; three present
(`define-package`, `symbol-call`, `add-package-local-nickname`). The **34**
missing:

```
uiop/package        DELETE-PACKAGE* ENSURE-PACKAGE ENSURE-PACKAGE-UNUSED EXPORT*
                    FIND-PACKAGE* FIND-SYMBOL* FRESH-PACKAGE-NAME HOME-PACKAGE-P
                    IMPORT* INTERN* MAKE-SYMBOL* NUKE-SYMBOL NUKE-SYMBOL-IN-PACKAGE
                    PACKAGE-DEFINITION-FORM PACKAGE-NAMES PACKAGES-FROM-NAMES
                    PARSE-DEFINE-PACKAGE-FORM REHOME-SYMBOL REIFY-PACKAGE
                    REIFY-SYMBOL RENAME-PACKAGE-AWAY SHADOW* SHADOWING-IMPORT*
                    STANDARD-COMMON-LISP-SYMBOL-P SYMBOL-PACKAGE-NAME
                    SYMBOL-SHADOWING-P UNINTERN* UNREIFY-PACKAGE UNREIFY-SYMBOL
uiop/package-local- PACKAGE-LOCAL-NICKNAMES REMOVE-PACKAGE-LOCAL-NICKNAME
  nicknames
uiop/package*       DEFINE-PACKAGE-STYLE-WARNING NO-SUCH-PACKAGE-ERROR
                    PACKAGE-DESIGNATOR
```

## The constraint that shapes everything

**A rontolisp symbol is a STRING, not an interned object** -- `.todo/156` landed
A2 (uppercase-canonical, string identity) and deferred A1 (a real intern table
with symbol identity) to a go/no-go. `*package*` is folded at RESOLUTION time
(`.kb/packages.md`), so most package questions are answered by the compiler, not
at runtime.

That splits the 34 cleanly, and the split -- not the code -- is the work:

**Answerable now** (name-level questions the registry can serve):
`find-package*`, `find-symbol*`, `intern*`, `make-symbol*`,
`symbol-package-name`, `package-names`, `packages-from-names`,
`fresh-package-name`, `ensure-package-unused`, `standard-common-lisp-symbol-p`,
`home-package-p`, `symbol-shadowing-p`, `package-designator`,
`no-such-package-error`, `define-package-style-warning`,
`parse-define-package-form`, `package-definition-form`, `ensure-package`, and
the three local-nickname functions (`add-package-local-nickname` already exists,
lite-global -- `package-local-nicknames` and `remove-package-local-nickname`
must agree with whatever scoping it actually has, and if that is "global", say
so rather than pretending per-package).

**Blocked on symbol identity** -- the whole point of these is to MOVE a symbol
between packages while every existing reference follows: `rehome-symbol`,
`nuke-symbol`, `nuke-symbol-in-package`, `reify-symbol`, `unreify-symbol`,
`reify-package`, `unreify-package`, `unintern*`, `shadow*`, `shadowing-import*`,
`import*`, `export*`, `delete-package*`, `rename-package-away`.

Upstream needs them for ONE reason: hot-upgrading ASDF inside a running image.
rontolisp has no image to upgrade. So the honest answer for most of that group
is `not-implemented-error` naming that reason -- **but decide it deliberately,
per name, not as a blanket**: `export*` / `import*` / `shadow*` over a package
being built are ordinary operations a library may perform at load time, and
those should be real if the registry can take a mutation at all. Whichever way
each goes, `.kb/uiop.md` records the reason, and `.todo/156` Phase 5 (the A1
go/no-go) gets a line saying this sub-package is one of the things A1 would
unblock -- that is the re-evaluation trigger.

## Gate

`UiopCoverageTest` reports 37/37 across the three sub-packages with no
undefined-function residue, and every `not-implemented-error` in the group
carries the "no image to upgrade" reason in its report. `LispEvaluatorTest`
covers the name-level half; `parse-define-package-form` is pinned against a real
`uiop:define-package` header from the cached Quicklisp corpus.
