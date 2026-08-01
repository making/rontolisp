# `subtypep` on class metaobjects (optional for Clack)

Difficulty: 低〜中 (the ancestor walk exists in the CLOS registry; this is
routing `subtypep` through it when the arguments are class metaobjects)

Part of the Clack milestone `.todo/223`, OPTIONAL — the only Clack call site is
`lack/builder:clack-middleware-symbol-p`, which detects OLD-Clack middleware
classes and is short-circuited by `(find-package :clack.middleware)` (nil in
any modern setup), so the interpreter milestone works without this.

Probed 2026-08-01: `(subtypep (find-class 'sub) (find-class 'super))` returns
NIL today for a genuine subclass (with symbols it presumably works; with CLASS
METAOBJECTS as arguments it does not consult the registry).

Work: `subtypep` (and `typep`'s type argument, for symmetry) accepts a class
metaobject where a type specifier is expected, resolving to the class's name /
ancestor set (`.kb/clos.md` `%find-class` + ancestor-set machinery). Pin
interpreter + compile paths together (the ci-spec CLOS case family).

Do this when a real old-style Clack middleware (or another library leaning on
class-metaobject `subtypep` — CLOS-heavy code does) enters the loadable corpus;
until then it would be speculative surface.
