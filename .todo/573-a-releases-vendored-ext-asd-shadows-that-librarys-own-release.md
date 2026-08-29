# 573. A release's vendored `ext/` `.asd` shadows that library's own release

Difficulty: Medium (the sort is decided; what has to be decided here is WHICH
`.asd` files a release contributes at all, and the answer moves emitted bytes
for any program whose quickload order happens to hit the shadow)

`eval/DistClient.collectAsdDirs` walks the WHOLE extracted release and puts every
directory holding a `.asd` on the ASDF search path. Quicklisp does not: a
release contributes only the system files its dist index names (`systems.txt`'s
`system-file` column, `releases.txt`'s trailing `file...` list). The difference
is invisible until a release vendors another library under `ext/` -- and then
that snapshot competes with the library's own release for the same system name.

Found 2026-08-29 while measuring `.todo/572` (the search-path sort). The sort
did not cause it and does not fix it: the winner is decided by the PROJECT order
in `ensureAvailable`, which is dependency order across releases, and the two
candidate directories are in two different projects.

## Reproduced

`iterate-release-d27d7ff4-git` ships `ext/alexandria/alexandria.asd`, a snapshot
that differs from `alexandria-20241012-git` (both call themselves version
`1.0.1`; the release's `.asd` additionally defines the secondary system
`alexandria/tests`, and four sources differ). With the quicklisp cache warm:

```lisp
(ql:quickload "iterate")
(ql:quickload "alexandria")
(print (if (asdf:find-system "alexandria/tests") "found" "no"))
```
=> `ASDF:FIND-SYSTEM: system not registered: alexandria/tests`

Drop the `iterate` line and the same program prints `"found"`. The program got a
DIFFERENT alexandria because of what an unrelated system dragged onto the search
path ahead of it -- and a program that quickloads them the other way round gets
the other one. `iterate`'s `ext/` also carries `rt`, `fiveam`, `asdf-flv` and
`trivial-backtrace`; `cffi` carries `uffi-compat/uffi.asd` and `mgl-pax` carries
`dref/test/data/dref-test-package-inferred.asd`, none of which any dist index
names as that release's system file.

## The fix to weigh

Contribute only the `.asd` files the dist index attributes to the release
(`SystemEntry`/`ReleaseEntry` already carry the names; today they are parsed and
the walk ignores them), with the whole-release walk kept as the fallback for a
release whose index line names no file. That is the quicklisp semantics, and it
makes a release's search-path contribution a function of the dist metadata
rather than of its tarball's layout.

The catch, and why this is not a one-liner: a release can legitimately keep its
own `.asd` somewhere the index does not name (`jzon-v1.1.4/src/com.inuoe.jzon.asd`
is found today by the walk, and `.kb/asdf.md`'s secondary-name rule leans on
finding `NAME.asd` wherever it sits), so narrowing the contribution can make a
system that resolves today stop resolving. Measure before landing: compile every
quicklisp-backed example before and after and diff the bytes, the way `.todo/572`
did (`.kb/emitted-output-determinism.md`, "What the DistClient sort moved").

## Acceptance

- A test pins that a release's vendored copy of another library does not shadow
  that library's own release, whichever order they are quickloaded in.
- The `examples/` quicklisp programs still compile, and any emitted-bytes change
  is explained rather than absorbed.
- `.kb/dists.md` says what a release contributes to the search path and why.
