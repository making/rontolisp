# 418. `asdf:load-system` never falls back to Quicklisp for a missing dependency

Difficulty: Medium

`(ql:quickload "cl-postgres")` inside a program works. A `:depends-on
("cl-postgres")` in a `.asd` does not: `asdf:load-system` resolves a dependency
ONLY against the `--system-path` / `RONTOLISP_SOURCE_REGISTRY` search path,
which is a flat list of directories each holding one `NAME.asd`. Nothing
consults the Quicklisp index the sibling `ql:quickload` already knows how to
download from and cache.

So a project whose only dependency is a Quicklisp library cannot just declare
it. It has to be pre-fetched by a separate `rontolisp -e '(ql:quickload ...)'`
run and then every release directory in the cache has to be named on the search
path — and `rontolisp test SYSTEM` has no `--eval` of its own to quickload from,
so the pre-fetch cannot even live in the same invocation. What that costs
downstream, measured on cl-postgres-client (`.todo/408`, the rove suite): a
Makefile that runs a fetch target first and then passes all ~140 cached release
directories as one colon-joined `--system-path`, built by a shell loop because
`$(wildcard)` would answer from the listing make read before the download.

## The essential fix

`asdf:load-system` (and a `:depends-on` edge it walks) should fall back to
`ql:quickload` when the search path has no `NAME.asd` — the same fetch-and-cache
path `ql:quickload` already runs, and the cache directory it writes into should
then be on the search path for the rest of the run without the caller naming it.
An unresolvable name still signals what it signals today.

That fetch-and-cache path is `eval/DistClient` over a LIST of Quicklisp-format
dists now (`.kb/dists.md`), so the fallback resolves against whatever the run
installed — quicklisp plus any `--dist` / `ql-dist:install-dist` — and needs
nothing dist-specific of its own.

Watch:

- **A silent network fetch from `load-system` is a behavior change**, and a
  compile-path one has to happen at COMPILE time (`LoadInliner`). Decide whether
  the fallback is unconditional or behind an opt-in flag before implementing,
  and make the recommendation and the default agree — do not ship "recommend on,
  default off".
- The cache directory should join the search path AS A RESULT of the fetch, so
  the `--system-path`-per-release-directory dance disappears rather than moving
  one level down.
- All four backends: the interpreter resolves at run time, both compile paths at
  splice time, and the browser playground has no filesystem and no network of
  this shape at all — it must keep signalling rather than reaching for one.
- `.kb/asdf.md` (the search-path and shim-system sections) and `.kb/load-inliner.md`
  record the current "flat directories only" contract; both change with this.

Reduction vehicle: cl-postgres-client's `Makefile` — when this lands its
`rontolisp-deps` target and its `RONTOLISP_SYSTEM_PATH` shell loop both go away
and the three `rontolisp-test*` targets become one-liners.
