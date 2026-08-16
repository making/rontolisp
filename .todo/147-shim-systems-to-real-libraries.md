# Replace built-in dependency shims with real libraries where feasible

Design line (user decision, 2026-07-18): a built-in shim is justified only
for usocket-class libraries -- per-implementation portability layers that
absorb NON-STANDARD implementation differences and therefore cannot support
rontolisp from their side. Libraries expressible in portable CL should load
as REAL sources (Quicklisp / vendored), with rontolisp growing the missing
CL features instead.

Current built-in systems (`eval.BuiltinSystems` + `eval.ShimLibraries`):

- `trivial-gray-streams` -- KEEP as shim (adapter over rontolisp's own Gray
  protocol, `eval.GrayStreamsLibrary` / `gray.lisp`; the real library is
  `#+sbcl`/`#+ccl`... branches with no rontolisp arm).
- `closer-mop` -- KEEP as shim for the same reason (wraps per-implementation
  MOP packages). Possible upgrade: back `c2mop:class-slots` with real slot
  metadata from `ClosRegistry` (slot names/types ARE known) so serializers
  like jzon's `coerced-fields` see real fields instead of `nil` (today a
  CLOS instance stringifies as `{}`).
- `float-features` -- KEEP as shim (wraps per-implementation float bit
  intrinsics; rontolisp's are the `%ieee754-*` built-ins).
- `uiop` -- KEEP as a stub package/system (ASDF-implementation
  infrastructure; rontolisp's asdf is a subset). Grow individual functions
  (`uiop:native-namestring`, ...) only on demand.
- `flexi-streams` -- REPLACE: the real library is mostly portable CL over
  trivial-gray-streams. Try loading the real sources; expected gaps are the
  binary/external-format surface (rontolisp streams are character-only
  integer handles, no element types), so this likely needs the stream model
  to grow octet streams first. Until then the identity shim
  (`make-flexi-stream` = the stream) stays -- semantically consistent with
  character-only streams, but a lie for binary re-encoding.

- `bordeaux-threads` -- KEEP as shim (its own `.asd` hard-errors on an
  unknown implementation, so there is no route to loading it for real;
  rontolisp's locking subset rides the `rontolisp:*-mutex` primitives).
- `babel` -- KEEP as shim, and unusually the reason is not portability but
  that the library's PURPOSE does not exist here. babel converts between an
  implementation's internal string representation and 40+ external
  encodings; rontolisp has exactly ONE character model (a character IS a
  Unicode code point, the external form is UTF-8 on every backend), so
  there is nothing for the other 39 to convert between. Loading it for real
  would splice ~20,000 lines of code-page tables (jpn-table.lisp alone is
  17,637) that no rontolisp program can use into every artifact. (This bullet
  originally justified that with "an ASDF-spliced third-party tree is not
  tree-shaken", which is STALE: `LibraryDefunPruner` has covered third-party
  trees since 2026-08-09 (`.kb/library-defun-pruning.md`). Re-measure before
  quoting the cost half again -- the tables are data reachable from the
  encoding registry, not unreferenced defuns, so pruning may well not reach
  them. The capability half below is unchanged and is the real reason.) The
  shim therefore
  implements the UTF-8 codec plus the Latin-1/ASCII aliases and SIGNALS on
  any other `:encoding` rather than mis-coding silently. It becomes
  reviewable only if the string model ever grows a second external
  encoding; until then "real babel" would be cost with no capability.

Also: the shim `.lisp` sources live next to the core libraries in
`src/main/resources/am/ik/rontolisp/eval/`; if the shim set grows, consider
a dedicated `shims/` subfolder to keep the core/shim distinction visible.
