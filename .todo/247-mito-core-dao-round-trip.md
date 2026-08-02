# mito-core: `(ql:quickload "mito-core")` + DAO CRUD against PostgreSQL

Difficulty: 高 (the integration session where every substrate piece meets;
uuid is the only new library, but mito-core's dao/class layers are the
largest single body of CLOS code the runtime will have loaded — recommend a
Fable-class model)

Part of the Mito milestone `.todo/238`. Blocked by `.todo/241` (uuid's .asd
chain), `.todo/243` (trivia), `.todo/244` (sxql), `.todo/245` (dbi +
dbd-postgres), `.todo/246` (MOP widening).

## Goal

```lisp
(ql:quickload '("mito-core" "dbd-postgres"))
(mito:connect-toplevel :postgres :database-name "mydb" :username "u" ...)
(mito:deftable user () ((name :col-type (:varchar 64)) (email :col-type (or (:varchar 128) :null))))
(mito:ensure-table-exists 'user)
(mito:insert-dao (make-instance 'user :name "a" :email "a@example.com"))
(print (mito:find-dao 'user :name "a"))
(mito:select-dao 'user (sxql:where (:like :name "a%")))
(mito:save-dao ...) (mito:delete-dao ...)
```

on interpreter + JVM + component, PostgreSQL only.

## New library: uuid (serial-pk alternative / uuid-pk-mixin)

uuid-2012.12.26 <- ironclad + trivial-utf-8. The ironclad SLICE has md5;
uuid.lisp also wants sha1 for v5 (check `make-digest` sites — the slice
policy from `.todo/115` says the next real consumer decides the widening;
uuid IS that consumer, widen exactly to what it calls). trivial-utf-8's real
source loads once `.todo/241` clears the mgl-pax dep. Note mito only NEEDS
uuid when a table uses `(:auto-pk :uuid)` — the default is serial; if the
slice widening balloons, a documented "uuid pk needs X" limitation is
acceptable for this session, not for the milestone.

## Integration watch list (grep evidence)

- dao/mixin.lisp defclasses with `(:metaclass dao-table-mixin)` run at LOAD
  time — the `.todo/246` hooks fire during quickload, first proof in anger.
- src/core/type.lisp:52 symbol-macrolet (`.todo/240`), parse of col-types via
  trivia match + ppcre.
- Timestamp conversion: local-time (loads today) <-> cl-postgres timestamptz
  wire format, `record-timestamps-mixin` (created-at/updated-at) — pin one
  round trip; the component leg's clock source differs (CLAUDE.md's "all
  four backends" note), so pin DATE math not wall-clock values.
- `mito.logger:with-trace-sql` dynamic binding around every query
  (`.kb/dynamic-special-variables.md`); `*mito-logger-stream*` nil default.
- dao.lisp:569 `deftable` interns `USER-` conc-names via `~@:(~A-~)` format —
  format directive coverage check.
- `getf`/`setf getf` on initarg plists inside :around methods — confirm setf
  getf place support (used at dao/table.lisp:108).
- Compile backends: the whole quickload + deftable happens at compile time
  (the postmodern DAO precedent, MopEvalCapture); `select-dao`'s runtime
  lambda/`funcall` of sxql clause builders should ride `.kb/symbol-runtime-api.md`
  if late-bound.

## Acceptance

- The goal program: identical output on the three backends against
  `postgres:17-alpine` (manual run; the automated E2E is `.todo/250`).
- `deftable` with explicit `:primary-key`, serial auto-pk, and
  `record-timestamps-mixin` variants each create the expected DDL
  (`mito:table-definition` output pinned as unit tests — no DB needed).
- `mito:object-id`, `mito:object=`, relation-less `:references` column type
  accepted. (Relationships/`:has-many` etc. are mito-core too — smoke one
  `mito:retrieve-dao` join if time allows, else note explicitly.)
