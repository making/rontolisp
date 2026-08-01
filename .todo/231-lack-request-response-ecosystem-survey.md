# lack-request / lack-response + middleware ecosystem (stretch, survey first)

Difficulty: 高 (multi-library lineage — each dependency is its own
loadability grind on the cl-postgres model; survey before committing)

Part of the Clack milestone `.todo/223`, STRETCH. The core milestone serves a
bare Clack app (env plist in, `(status headers body)` out). Real Clack
applications and the web frameworks above Clack (ningle, caveman2, jingle,
CLOG) also want the request/response layer and the standard middleware, each
with its own dependency tail (from the Quicklisp systems index):

- `lack-request`: circular-streams, cl-ppcre (DONE), http-body, quri
- `lack-response`: local-time, quri
- `lack-middleware-static` / `lack-app-file`: trivial-mimes, trivial-rfc-1123
- `lack-middleware-session`: lack-util only (memory store) — likely the
  CHEAPEST real middleware after backtrace; session/store/dbi wants cl-dbi
- `lack-middleware-csrf`, `-auth-basic` (cl-base64 DONE, split-sequence DONE),
  `-accesslog` (local-time), `-mount`
- http-body additionally: fast-http, jonathan or jzon-adjacent JSON, quri,
  flexi-streams (shim exists), trivial-gray-streams (shim exists)

Key new lineages, in rough value order:

1. **quri** (URI library) — the most-shared dependency above; its `.asd` uses a
   `uri-classes` module shape `.kb/asdf.md` already parses. Biggest unknown:
   its etld data file + CLOS class tree. Unlocks lack-request AND
   lack-response.
2. **local-time** — timestamps; heavy reader-macro + defstruct usage.
3. **http-body** — multipart/urlencoded/json request-body parsing; pulls
   fast-http (a hand-rolled HTTP parser with heavy optimization declarations —
   may be the hardest single library here).
4. **circular-streams** — Gray-streams based; the shim'd Gray protocol may
   carry it already.
5. **trivial-mimes** — data-file driven, should be easy.

Survey task (do first, one session): for each of quri / local-time /
http-body / circular-streams / trivial-mimes, attempt `ql:quickload` on the
interpreter, record the blocker chain (the clack spike method: patch-probe in
the quicklisp cache, throw the patches away, keep the list), then split into
per-library todos with difficulty. Do NOT start fixing mid-survey.

Framework note: ningle additionally needs myway (routing) + cl-annot-style
metaprogramming in some versions; caveman2 needs cl-project/dbi. Evaluate
against the ecosystem-first lens (prefer widely-depended-on real libraries
over bespoke one-off shims) before
committing to any framework-level target.
