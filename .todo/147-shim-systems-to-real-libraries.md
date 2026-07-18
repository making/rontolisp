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

Also: the shim `.lisp` sources live next to the core libraries in
`src/main/resources/am/ik/rontolisp/eval/`; if the shim set grows, consider
a dedicated `shims/` subfolder to keep the core/shim distinction visible.
