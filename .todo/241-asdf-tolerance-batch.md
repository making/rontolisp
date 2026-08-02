# ASDF front-end tolerance batch (mito closure)

Difficulty: 低〜中 (AsdfSystems.java parse-level work with clear probe
reproductions; the only judgment call is esrap's feature pushes)

Part of the Mito milestone `.todo/238` (substrate; no dependencies).

Four independent parse-level gaps, each reproduced by a one-line
`(ql:quickload ...)` probe on 2026-08-02. NOTE what already works: dbi.asd's
`#1=`/`#1#` labels, `(:feature <expr> "sys")` depends-on entries and
`:if-feature` components all parse today — do not redo them.

## 1. `(:version "dbi" "0.11.1")` depends-on entry

`(ql:quickload "mito")` dies in AsdfSystems.designator (AsdfSystems.java:728):
`:depends-on expects a literal system name ... got (:VERSION "dbi" "0.11.1")`.
Both mito.asd and mito-core.asd use it. Parse it as the plain dependency name;
version CHECKING is optional (systems here have no reliable :version anyway —
if skipped, say so in the code comment).

## 2. Tolerated non-defsystem top-level forms in .asd files

The parser rejects everything outside its whitelist. Newly needed:

- iterate.asd: `(defmethod perform ((o test-op) ...) ...)` — test-only, safe
  to ignore. (iterate itself is NOT on the v1 path — `.todo/243` skips the
  balland2006 optimizer — but the .asd still gets PARSED when the trivia
  project directory is scanned, and the tolerance is generally useful.)
- chipz.asd: `(defclass txt-file (doc-file) ...)` + doc-file components —
  documentation-file machinery, ignore the defclass and any component whose
  type is not :file/:module.
- esrap.asd: `(defmethod perform :after ((op load-op) (sys (eql ...)))
  (pushnew :esrap.lookahead *features*) ... (provide :esrap))` — NOT ignorable
  blindly: the 6 pushed features describe capabilities and esrap sources /
  downstreams may read them at load time. Decide: either recognize this exact
  pushnew-in-perform shape and fold the keywords into the system's
  :rontolisp-features equivalent (the postmodern-deps.asd precedent shows why
  a *features* push must be visible to the READER, `.todo/181`), or ship a
  hand-authored esrap override .asd declaring them statically. Grep esrap's
  sources for `#+esrap.` first — if nothing reads them, a plain ignore + a
  comment is the honest minimum.

## 3. mgl-pax-bootstrap (`:defsystem-depends-on`)

`(ql:quickload "uuid")` (a mito-core dep) fails because uuid -> trivial-utf-8,
and trivial-utf-8.asd declares `:depends-on (#:mgl-pax-bootstrap)` — a
documentation-only system whose own .asd uses `:defsystem-depends-on`.
cl-postgres never hit this because cl-postgres-deps.asd hand-authors its
closure. Options: (a) hand-authored trivial-utf-8 override .asd dropping the
pax dep (check what its source actually calls — likely `mgl-pax:define-package`
or a defsection macro that needs a tiny stub), or (b) support enough of
mgl-pax-bootstrap to load. (a) matches the postmodern-deps precedent; write
the reason in the file.

## Acceptance

- `(ql:quickload "uuid")`, `"esrap"` and `"chipz"` reach their SOURCE loading
  (their remaining blockers are `.todo/248`/`.todo/249`, not the .asd).
- mito.asd / mito-core.asd parse past :depends-on.
- Unit tests in the AsdfSystems test class for each tolerated shape, plus one
  pinning that an UNKNOWN top-level form still errors loudly (tolerance must
  stay a whitelist, not a sink).
