# 708. `LispFormatterTest` walks `.claude/worktrees/`, so the suite's test count is a property of the box

Difficulty: Low

Filed 2026-09-05 from the cross-box comparison `.todo/670`'s protocol depends on.
Dorian ran `./mvnw test` green at `6a6ebbac` and reported **26359 tests**; GB10 ran it
green at `4358af09` and reported **10607**. Same source, 231 report files on both sides,
zero failures on both. **The gap is not in the code. It is 25 stale agent worktrees on
dorian's disk.**

## The mechanism

`LispFormatterTest.repositoryLispSources()` is a `@MethodSource` that does
`Files.walk(Path.of("."))` and takes every `.lisp` / `.asd` under the working directory.
It filters two things and only two:

```java
.filter(path -> !path.toString().contains("/target/"))
.filter(path -> !path.toString().contains("/ansi-test/suite/"))
```

`.claude/worktrees/` is not filtered. Measured on dorian:

| | count |
| --- | --- |
| `git ls-files '*.lisp'` | 618 |
| on disk, excluding `target/` and worktrees | 628 |
| on disk, excluding `target/` only | **22098** |
| inside `.claude/worktrees/` | **21470**, across **25** worktrees |

`LispFormatterTest` alone ran **17014** of dorian's 26359 tests -- 65% of the suite,
formatting the same files 25 times over.

## Why it matters more than a slow suite

1. **The suite's test count is a property of the box, not of the commit.** Every
   cross-box comparison in `.todo/670` -- 9977 against 9976, 10000, 10607, 26359 -- was
   comparing numbers that include however many worktrees happened to exist. The protocol
   of reading an "accounted-for delta" cannot work while one term is unbounded.
2. **It is a live cross-lane interference channel.** The walk reads other agents' WORKING
   TREES. A lane mid-edit with a not-yet-formatted `.lisp` file makes the MAIN tree's
   `./mvnw test` fail, in a file the main tree does not contain, with no indication that
   the source is another worktree. Nobody has hit this yet; nothing prevents it.
3. It formats ~590 files 25 times for no coverage.

**The composition is the reason this matters, not the 17014 tests.** Two individually
harmless mechanisms meet: a rename can change formatting (682 shortened
`examples/llama2` to `examples/llm`, which changed javadoc line lengths in `LinalgBlas`
and `TokenizersLibraryTest` and failed the build until `spring-javaformat` was
re-applied -- a formatting consequence of a RENAME that nobody would predict), and the
walk reads other lanes' trees. Together: **a worktree is not private to the lane that
owns it.** An unformatted file sitting mid-edit in one lane's worktree fails the MAIN
tree's suite, in a file the main tree does not contain, and the failure names a path the
person reading it has never heard of. Nearly undiagnosable from the main tree.

## Do

1. Add a filter for `/.claude/` -- one line, beside the two that exist. Prefer excluding
   the whole `.claude/` directory rather than `worktrees` specifically, since anything the
   harness puts there is equally foreign. The existing `ansi-test/suite/` comment already
   states the principle: *"a developer who ran `ansi-test/fetch.sh` must not get a
   different verdict from `./mvnw test` than one who did not"*. A developer who has agent
   worktrees must not either.
2. Pin it: assert the corpus size is within a small factor of `git ls-files '*.lisp'`, or
   assert no selected path contains `/.claude/`. Without a pin this returns the first time
   someone adds a directory the walk does not know about -- the same shape the filter list
   already has twice.
3. **Separately, clean up the 25 stale worktrees** -- they are finished agents' trees from
   earlier sessions, ~21470 Lisp files and whatever else. Not part of the fix; the fix must
   work with them present.

## Not in scope

- Whether the suite should format the repo's own Lisp at all. It should; that is
  `.kb/formatter.md`'s invariant and it works. The defect is the corpus boundary.
- Re-running `.todo/670`'s certified numbers. They are green either way -- **zero failures
  on both boxes, 231 report files on both** -- and only the COUNTS are incomparable. Say so
  in 670 rather than re-running anything.
