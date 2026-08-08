# A tiny-routes application pays ~850 KB of cl-ppcre, and the tree-shaker recovers almost none of it

Difficulty: High

Adding ROUTING to a Clack application nearly triples the WASM module. Measured
2026-08-08 on the native binary, `examples/cloudflare-workers/hello-clack`'s own
build line (`--no-wasi --optimize`), the base being that example verbatim and the
routed one the same three forms with three tiny-routes routes:

| Cloudflare Worker reactor build | raw | gzip | functions | code |
|---|---|---|---|---|
| clack, one handler lambda (`hello-clack`) | 514,999 | 140,789 | 544 | 457,849 |
| + tiny-routes, three routes | 1,388,429 | 339,453 | 1,238 | 1,305,008 |
| **delta** | **+873,430 (2.70x)** | **+198,664 (2.41x)** | **+694** | **+847,159** |

The same factor on the other two serve shapes:

| | raw | gzip |
|---|---|---|
| `--component` clack only | 1,234,582 | 260,723 |
| `--component` clack + tiny-routes | 2,387,768 | 506,066 |
| Preview 1, routing only (`examples/asdf/tiny-routes-demo.lisp`) | 1,222,337 | 297,421 |
| Preview 1, `(print (+ 1 2))` floor | 256,800 | 66,995 |

## It is not tiny-routes -- it is cl-ppcre, its only dependency

Section dump of the Preview-1 modules:

| module | functions | code | data |
|---|---|---|---|
| `(print (+ 1 2))` | 398 | 232,447 | 23,111 |
| cl-ppcre alone (`asdf:load-system :cl-ppcre` + one `scan`) | 1,155 | 1,093,876 | 50,439 |
| the tiny-routes demo (which loads cl-ppcre) | 1,227 | 1,165,473 | 54,495 |

**tiny-routes itself is 72 functions and ~72 KB of code.** cl-ppcre is 757
functions and ~861 KB, and a Clack application does not otherwise pull it in --
so routing is where a Clack program first meets the regex engine.

## The shaker cannot see it as dead, and an app with no regexes still pays

- `--optimize` buys nothing more here: `build.sh` already passes it, and both
  rows above are shaken. `--optimize=size` is the one lever that helps today and
  the example does not use it: **1,388,429 -> 1,232,626 raw, 339,453 -> 289,055
  gzip (-11%/-15%)**. Same story on the component build (2,387,768 -> 2,079,538).
- **An application whose routes are all EXACT paths still pays it**: dropping the
  `:id` template from the routed Worker gives 1,372,465 B, 16 KB off 1,388,429.
  `path-template.lisp` has a TOP-LEVEL `(defvar *path-token-scanner*
  (ppcre:create-scanner ":([A-Za-z_][A-Za-z0-9_-]*)"))` that runs at load time
  whatever the routes are, and `wrap-request-matches-path-template`'s `cond`
  reaches the keyword and regex matchers from any route.
- The whole cl-ppcre pipeline (lexer -> parser -> converter -> optimizer ->
  closure compiler) is genuinely reachable, because a route template is compiled
  to a scanner at RUN time. Nothing is dead; the shaker is right.

## Levers, none decided

1. **`--optimize=size` in the Worker examples.** Free -11%/-15%, already
   implemented. Needs the measurement above re-taken and the READMEs' size
   figures updated with it. The cheapest half of this item and arguably its own
   small change.
2. **cl-ppcre's own `define-compiler-macro`s** (`scan`, `scan-to-strings`,
   `count-matches`, `all-matches`, ... in `api.lisp`) hoist a LITERAL regex into
   a `load-time-value` scanner, and rontolisp implements that pair
   (`.kb/compiler-macros.md`). MEASURE whether they fire here and record the
   answer either way -- the expectation is that they do NOT shrink anything (the
   scanner BUILDER still has to be in the module to run at load time), and
   writing that down is what stops the next visitor re-deriving it. They may
   still be worth having for start-up time.
3. **A leaf-module substitution of `path-template.lisp`.** The `:name` template
   is a segment match, not a regex; the ShimLibraries leaf-module tier
   (`.kb/asdf.md`, third rung of the ladder) is exactly the mechanism. It would
   take the routing common case off cl-ppcre entirely -- and it DIVERGES from
   upstream (`:regex t` templates, and any user code calling `ppcre:` directly,
   must keep working, so the engine would come back the moment either appears).
   Decide whether a size win is worth a substitution on a library we otherwise
   load verbatim, and record the reason.
4. **The two whole-program levers `.todo/290` already lists**, whose share of
   THIS module has not been measured: the string-blob dead-range pass standing
   down under `usesIntern` (lever 1 there), and CLOS-aware shaking (lever 3).
   The routed Worker's data section is 81,279 B against the base's 55,943.
5. **Splitting cl-ppcre's parse half from its match half.** Only reachable if a
   scanner could be built at COMPILE time, which a closure tree cannot be. Record
   as investigated, not as a plan.

## Non-goals

- Replacing cl-ppcre with a rontolisp-native regex engine. It loads verbatim
  today and that is the point of the ASDF work; a shim would be a much bigger
  decision than a size item.
- Anything about tiny-routes' own 72 KB. It is not where the bytes are.
- The clack/lack baseline itself -- that is `.todo/290`.

## Done when

- Whichever levers are taken are measured the same way as the table above (raw
  AND gzip, on the Worker reactor build, since that is the size-sensitive one),
  and the numbers land in the commit message.
- `.kb/optimize-dead-code-elimination.md` (or `.kb/asdf.md` if lever 3 is taken)
  records what was tried, what it bought, and what was rejected WITH the reason
  -- a size finding with only a number and no "why not the others" is the same
  dead end this item started from.
- `examples/cloudflare-workers/*/README.md` size figures are re-taken if any
  lever moves them, and `examples/asdf/README.md`'s tiny-routes row gains the
  caveat if the answer turns out to be "it costs what it costs".
