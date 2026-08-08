# A tiny-routes Cloudflare Worker under 1 MB raw

Difficulty: Medium

The goal is a USER-STATED target (2026-08-08): a Clack Worker that routes
through the real tiny-routes API must come in under 1,000,000 B raw. Today's
floor with the verbatim library is 1,219,894 B (`--no-wasi --optimize=size`,
the routed probe: hello-clack's three forms plus three routes, one an `:id`
template) -- 219,895 B over. gzip is NOT the problem (286,621 B, 9.5% of the
free plan's compressed limit); the target is the raw module.

## Already measured -- do not re-derive

`.kb/optimize-dead-code-elimination.md`, "What ROUTING costs a clack module"
(2026-08-08), settled the map:

- The whole gap is cl-ppcre, tiny-routes' only dependency, and in a REAL routed
  app the engine is LIVE: `create-scanner` / `scan-to-strings` /
  `regex-replace-all` run at route-build and request time, so no shaker --
  present or future -- can remove it. The 823,589 B CLOS-anchoring ceiling
  recorded there is the ZERO-REFERENCE case only; it does not apply here.
- The remaining general levers cannot reach 1 MB on this module: string-blob
  dead ranges cap at the 58,756 B blob segment, the probe refinement is a few
  KB, `=size` is already taken.
- **The one measured configuration under the target keeps the tiny-routes API
  and swaps `path-template.lisp` for a ppcre-free matcher AND drops the
  `:cl-ppcre` dependency from the `.asd`: 536,895 B raw / 146,809 B gzip**,
  `_initialize` 54 -> 14 ms (the load-time `*path-token-scanner*` build goes).
  Both tiers are needed together: the file shim alone is -0.9%, because the
  loaded-but-unreferenced engine stays anchored through its CLOS surface.
- A compiler-macro rewrite of literal templates (every `define-VERB` template
  IS a literal at macroexpansion) cannot get there alone: the top-level
  `(defvar *path-token-scanner* (create-scanner ...))` in `path-template.lisp`
  runs at load time whatever the routes are and anchors the whole builder
  pipeline. Any solution must replace that FILE; rewriting call sites is not
  enough. (Same finding as "an application whose routes are all EXACT paths
  still pays", re-stated so nobody retries it from the other end.)
- Hand-rolled routing (no tiny-routes) is 463,370 B and already documented
  (`examples/cloudflare-workers/httpbin-clack` is the 5-endpoint example) --
  it is the no-work alternative, not this item.

## The shape this item proposes: an OPT-IN substitution that is loud, never wrong

todo-295 rejected the substitution AS A DEFAULT because upstream's keyword
matcher interprets the non-token template text as a REGEX, and a silent
behavior change on a verbatim-loaded library is unacceptable. Both objections
dissolve if the substitution is (a) asked for explicitly and (b) signals on
everything it does not implement, so that no template ever matches
DIFFERENTLY from upstream -- it either matches identically or refuses loudly
at route-build time:

1. **Faithful-subset matcher.** Accepted templates: token =
   `:([A-Za-z_][A-Za-z0-9_-]*)` ANYWHERE in the template (mid-segment tokens
   included -- `/files/v:version` is `/files/v([^/]+)` upstream); every
   non-token character must be regex-INERT (reject the template when it
   contains any of `. \ [ ] ( ) { } | ^ $ * + ?`). Within the subset the
   upstream scanner is `^...$` with greedy `([^/]+)` groups, so the matcher
   must reproduce greedy-with-backtracking semantics for multi-token segments
   (`/a/:x-:y` against `/a/b-c-d` binds x=`b-c`, y=`d`). Outside the subset,
   and for `:regex t`, SIGNAL a self-describing error at route-build time that
   names the substitution and the escape (load the real tiny-routes). Result:
   zero silent divergence by construction.
2. **Opt-in mechanism -- the real decision of this item.** Constraints that
   rule options out: the choice must be visible AT COMPILE TIME to both system
   loaders (`cli.LoadInliner.spliceSystem` and `LispEvaluator.loadSystem`) and
   on the native binary; a `(push :x *features*)` in the program is invisible
   to the reader and the compile path (`.todo/181`), so a feature flag is out.
   Candidates: a separately-named bundled system (`tiny-routes-lite`, same
   packages and exports, no `:cl-ppcre` dependency, every component except
   `path-template.lisp` resolved from the real tree -- the program then OPTS IN
   in its own source, which is the least magical spelling) vs. a CLI flag
   (works, but a per-library flag is a smell). Whichever is chosen: a new
   shim `.lisp`/`.asd` resource needs its
   `META-INF/native-image/.../resource-config.json` entry, or it fails only on
   the native binary at load time (`.kb/asdf.md`, the ironclad-slice lesson).
3. **Pinning.** One (template, path) corpus with expectations pinned as data;
   two tests run the SAME corpus -- one against real tiny-routes (interpreter,
   cl-ppcre engine), one against the lite matcher on all backends
   (`AsdfLibraryE2eSupport` if it ships as a system). The corpus must cover:
   exact, single-token, multi-token-per-segment (backtracking), mid-segment
   token, empty-segment rejection (`([^/]+)` needs 1+ chars), the `*` and
   `""`/`t`/`nil` passthroughs, and one of each REJECTED shape asserting the
   error message. The two-tests-one-corpus structure keeps lite and upstream
   aligned without co-loading them (their packages collide).
4. **Docs.** `doc/{en,ja}/guides/asdf-systems.md` entry (mirrored, same
   fences), the `examples/asdf/README.md` tiny-routes row gains the pointer,
   and the Worker README that demonstrates it records raw+gzip the same way as
   todo-295's tables (`gzip -9 -n`).

## Non-goals

- Changing what plain `(ql:quickload "tiny-routes")` loads. The verbatim
  library, cl-ppcre included, stays the default; todo-295's rejection of a
  silent substitution stands.
- A rontolisp-native general regex engine.
- Shrinking the clack base itself (`.todo/290`); at 536,895 B the target has
  ~463 KB of headroom, so this item does not depend on it.

## Done when

- The routed Worker probe (three routes incl. `:id`), built the opt-in way at
  `--no-wasi --optimize=size`, is under 1,000,000 B raw -- expected ~537 KB --
  and the number (raw AND gzip) lands in the commit message.
- The corpus test pins lite == upstream on every accepted template shape, and
  the rejected shapes fail loudly with the documented message, on all four
  backends.
- The opt-in spelling, its exact accepted subset, and the rejection behavior
  are documented in the asdf-systems guide (en+ja) and recorded in
  `.kb/asdf.md` beside the other substitution tiers.
