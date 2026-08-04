# Mito + SxQL support milestone: `(ql:quickload "mito")` + DAO CRUD on rontolisp

Difficulty: 高 (this is the tracking umbrella; the per-unit files carry their own
ratings — the substrate items 240/246 are the hard ones)

Goal: Eitaro Fukamachi's Mito (https://github.com/fukamachi/mito) and its SQL
generator SxQL (https://github.com/fukamachi/sxql) load verbatim via
`ql:quickload` and a DAO round trip (deftable / insert-dao / select-dao /
migration) works against a live PostgreSQL. Sources come from the real
Quicklisp dist ONLY (`~/.rontolisp/quicklisp/software/`, mito-20260101-git +
sxql-20260101-git; re-run `(ql:quickload "mito")` to restore the cache — never
read GitHub master).

## Scope (user decisions, 2026-08-02)

- **Backends: interpreter + JVM + WASM `--component`. WASI Preview 1 is OUT of
  scope** (no TCP by design, `.kb/tcp-sockets.md` — same stance as the
  cl-postgres/postmodern/clack milestones).
- **Database: PostgreSQL ONLY.** `dbd-postgres` rides the already-working
  cl-postgres driver (`.todo/115` closed). dbd-mysql / dbd-sqlite3 need FFI and
  stay out; mito's own `src/core/db/{mysql,sqlite3}.lisp` still LOAD (they only
  import `dbi`, verified) — they are just never selected.

## Spike result (2026-08-02, all conclusions probe- or grep-verified)

Dependency closure and its status:

```
mito <- mito-core, mito-migration, lack-middleware-mito
mito-core <- (:version dbi), sxql, cl-ppcre*, closer-mop(shim), dissect*,
             trivia, local-time*, uuid, alexandria*
mito-migration <- mito-core, sxql, dbi, esrap, chipz(crc32 only), uiop(shim)
sxql <- trivia, alexandria*, cl-package-locks*
dbi <- split-sequence*, closer-mop(shim), cl-ppcre*, [bordeaux-threads(shim)]
dbd-postgres <- dbi, cl-postgres(works), trivial-garbage(gap: tg:finalize)
trivia -> trivia.balland2006 -> trivia.trivial -> level2 -> level1 -> level0
          (balland2006 additionally needs iterate + type-i — see .todo/243)
trivia.level2 <- lisp-namespace(gaps), closer-mop(shim, 2 names missing),
                 trivial-cltl2*
* = probed loading TODAY with zero work
```

Blockers found (each maps to one work unit below):

- Reader: `',@` inside a backquote template — trivia level0/impl.lisp:54,
  type-i. `LispReadException` at LispReader.readWrappedTemplate.
- `symbol-macrolet` undefined — trivia level1+level2 EXPANSIONS (load-bearing),
  dbi driver.lisp:295 (with setf through the symbol macro), mito
  core/type.lisp:52. The single biggest substrate item.
- `(setf (find-class ...))` (dbi utils.lisp:14,21) and
  `(setf (macro-function ...))` (lisp-namespace) unsupported places; both are
  ALIAS-only use cases.
- ASDF front-end: `(:version "dbi" "0.11.1")` depends-on entry (mito.asd,
  mito-core.asd) is a hard parse error; top-level `defmethod`/`defclass`/
  `provide` in .asd (iterate, esrap, chipz) rejected; trivial-utf-8.asd (a uuid
  dep) depends on mgl-pax-bootstrap whose .asd uses `:defsystem-depends-on`.
  NOTE dbi.asd's `#1=`/`(:feature ...)`/`:if-feature` already parse (probed).
- MOP: mito's metaclasses need protocol pieces the static subset lacks:
  `ensure-class-using-class` routing (mito's :around injects the `dao-class`
  superclass), user `initialize-instance :around` on the metaclass,
  REdefinition (`reinitialize-instance` path), `slot-definition-initfunction`,
  `class-direct-subclasses`, `subtypep`/`typep` on class metaobjects
  (= `.todo/230`, promoted from optional to REQUIRED: mito
  `contains-class-or-subclasses`).
- esrap (PEG parser DSL) is on the POSTGRES path too: migration
  versions.lisp:365 `parse-statements` splits migration SQL files.
- chipz: migration/util.lisp uses ONLY make-crc32/update-crc32/produce-crc32
  (advisory lock ids) — a slice, not the decompressor.

Probed fine already (no work): `#.` reader eval (sxql uses it heavily),
non-circular `#1=` labels, BOA `&rest` defstruct constructors, load-time-value,
local-time / dissect / cl-package-locks / trivial-cltl2 quickloads.

## Work units (order; difficulty in each file)

Substrate (independent of each other, any order):

1. `.todo/239` reader: `',@` / `#',@` in backquote templates — 中
2. `.todo/240` `symbol-macrolet` on all four backends — 高
3. `.todo/241` ASDF front-end tolerance batch — 低〜中
4. `.todo/242` `(setf find-class)` / `(setf macro-function)` aliases +
   lisp-namespace — 中
5. `.todo/246` MOP protocol widening for mito's metaclasses (incl. `.todo/230`)
   — 高
6. `.todo/248` esrap — 高

Library chain (each blocked by the listed units):

7. `.todo/243` trivia (trivia.trivial route) — 高 — needs 239, 240, 242
8. `.todo/244` sxql — 中〜高 — needs 243
9. `.todo/245` cl-dbi + dbd-postgres — 中〜高 — needs 240, 242
10. `.todo/247` uuid + mito-core DAO round trip — 高 — needs 241, 243, 244,
    245, 246
11. `.todo/249` mito-migration + chipz slice + full `mito` + lack-middleware-mito
    — 中〜高 — needs 247, 248 — **DONE 2026-08-03**
12. `.todo/250` MitoE2eTest + docs — 中 — needs 249 — **DONE 2026-08-04**

Interpreter leg lands first inside each unit, but a unit is DONE only when the
JVM and component legs are green too (or the divergence is recorded with its
reason + re-evaluation trigger in `.kb`).

## Status after `.todo/249` (2026-08-03)

`(ql:quickload "mito")` loads the FULL system and the migration workflow runs on
the interpreter, the JVM and the WASM component against a live PostgreSQL:
`deftable` -> `ensure-table-exists` -> redefine -> `migration-expressions` ->
`migrate-table` -> clean re-run, `generate-migrations` -> `migration-status` ->
`migrate` (esrap re-reading the generated `.sql`), plus `lack-middleware-mito`
inside a `lack:builder` app. `.kb/mito.md` is the topic file: the chipz CRC32
slice, the `generate-migrations` filesystem scoping (interpreter+JVM), the three
UPSTREAM defects reproduced faithfully, and the SCRAM caveat every DB E2E needs.

Getting there closed `.todo/222`'s core (a runtime `make-pathname`) and fixed
four rontolisp defects the mito path was the first to reach, each SBCL-checked
and each owned by its own `.kb` file: two CLOS dispatch bugs (`call-next-method`
dropping `&optional`; no dispatch branch for the MEET of two incomparable
specializer vectors), the case-folding string designators keeping a package
qualifier on the compiled backends, and the Gray-streams rewrite treating a
binding form's lambda list as a call. Follow-ups: `.todo/251` (DAO accessors,
relational `:col-type`), `.todo/252` (Gray output protocol), `.todo/253` (SCRAM
cost, cached-connection lifetime).

## MILESTONE COMPLETE (2026-08-04, `.todo/250` closed the last unit)

Every acceptance item below is met and every unit's divergence carries its
reason + re-evaluation trigger in a `.kb` file: `.kb/mito.md` (the chipz CRC32
slice, the `generate-migrations` filesystem scoping, the three upstream defects,
the SCRAM caveat), `.kb/asdf.md` (the trivia `trivia.trivial` route from
`.todo/243`, the `dbi-deps.asd` per-backend cache choice from `.todo/245`,
sxql), `.kb/clos.md` (the MOP widening and the redefinition scope from
`.todo/246`) and `.kb/packages.md` (`uiop:define-package` + `:use-reexport`).

Coverage landed with `.todo/250`: **`MitoE2eTest`** (three live backends,
byte-identical, plus the Preview 1 compile-error pin) and the bilingual
`doc/{en,ja}/guides/mito.md` + the rewritten `asdf-systems.md` row.

ONE gap was found while writing that E2E and is filed rather than fixed here:
sxql's SQL FUNCTION operators (`(:count ...)` and friends, hence
`mito:count-dao`) are interpreter-only because 2-argument `find-symbol` answers
a SYMBOL for an unknown name on the compiled backends — `.todo/254`, which is
also the counter-example `.todo/156` was waiting for before deciding its A1
intern-table axis. It is a pre-existing symbol-model deviation that mito is the
first consumer to make visible, not a mito defect.

Remaining follow-ups, all filed: `.todo/251` (DAO accessors, relational
`:col-type`), `.todo/252` (Gray output protocol), `.todo/253` (SCRAM cost,
cached-connection lifetime), `.todo/254` (the `find-symbol` gap above).

## Acceptance (interpreter + JVM + WASM component; P1 = out of scope)

- `(ql:quickload "mito")` completes with ZERO userland workarounds on UNPATCHED
  cached sources. (Hand-authored `*-deps.asd`-style overrides in
  `src/main/resources` are allowed where the precedent exists — postmodern-deps,
  ironclad-slice — each with the reason written in the file.)
- The milestone program: `mito:connect-toplevel` (:postgres), `mito:deftable`,
  `ensure-table-exists`, `insert-dao` / `find-dao` / `select-dao` (with sxql
  `where`), `save-dao`, `delete-dao`, and `mito.migration:migration-status` +
  `migrate` — identical output on the three backends against a live PostgreSQL.
- `sxql:yield` output pinned for select/insert/update/delete/join/where.
- E2E: `MitoE2eTest` (Testcontainers `postgres:17-alpine`, the
  ClPostgresE2eTest/PostmodernE2eTest shape) + docs per the asdf-library
  checklist.

## Out of scope (documented limitations for the first release)

- WASI Preview 1 (call-time error policy, as everywhere).
- MySQL / SQLite3 drivers (FFI).
- The trivia balland2006 OPTIMIZER (iterate + type-i) — `.todo/243` maps
  system `trivia` to `trivia.trivial` (upstream-sanctioned substitution);
  re-evaluation trigger recorded there.
- `dbi:with-connection-pool` thread-affinity niceties beyond what the bt shim
  gives; `tg:finalize`-driven connection reclamation (explicit `disconnect`
  documented instead) — see `.todo/245`.
