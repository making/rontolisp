# 685. The deletion-history index's Entries column drifts from the rows it counts

Difficulty: Low

Found 2026-09-03 while closing `.todo/483`. `.todo/.history.md` is a month index over
`.todo/history/YYYY-MM.md`, and each month's row carries an `Entries` count. Measured
that day, before the fix that closing made:

| month | Entries column said | rows actually present |
| --- | --- | --- |
| 2026-07 | 78 | 78 |
| 2026-08 | 342 | 344 |
| 2026-09 | 7 | 65 |

2026-07 agrees because it is closed; nothing appends to it any more. The two open
months do not, and 2026-09 was off by a factor of nine.

**The cause is structural, not carelessness.** Closing an item appends one row to the
month file AND edits one number in the index -- two changes that several sessions make
concurrently against the same two files. `git merge` resolves two appended rows cleanly
(different lines) but a total updated from the same base twice keeps one of the two
increments. Every lost race silently subtracts one. The count can therefore only drift
DOWNWARD, forever, and no test or script looks at it.

`.todo/483`'s closing corrected both open months by counting the rows. That is a
snapshot, not a fix: the same drift resumes on the next concurrent close.

## Do

The count is derived data living beside its source, which is the whole problem. Options,
cheapest first:

1. **Drop the column.** Nothing reads it; `wc -l` answers it exactly. The index's job is
   to say which months exist and where their files are.
2. **Generate it.** A line in `.todo/claim-number.sh` (or a tiny sibling script) that
   rewrites every month's count from `.todo/history/*.md` -- run when a row is appended,
   so the number is never typed by hand. Still races, but a race now produces a count
   that is merely stale rather than one that is permanently short, and the next run
   repairs it.
3. Leave it and re-count periodically. This is what has been happening implicitly, and
   it is why the number was off by nine.

Prefer 1 unless someone actually reads the column. Ask before choosing 2: a script that
rewrites a file on every close is a new failure mode in the one place that must keep
working (`claim-number.sh` is how numbers stop colliding).

Whichever is chosen, the row format and the two-commit closing rule
(`.todo/.history.md`'s own text) do not change -- this is about one derived number.

## Verify

- The counts agree with `wc -l` of each month file, and stay agreeing after two sessions
  close an item concurrently (simulate: append two rows from two clones, merge, check).
- `.todo/claim-number.sh` still claims numbers correctly, and a failure in whatever
  maintains the count cannot make it fail.

## Measured 2026-09-03, and the cause is not what the item assumed

Counted after a day in which eight items closed across two orchestrators:

| month | index says | rows in the file |
| --- | --- | --- |
| 2026-07 | 78 | 78 |
| 2026-08 | 344 | 344 |
| **2026-09** | **70** | **72** |

Two closed months agree exactly; only the live month drifts, and it drifts DOWN, as this
item predicted. But the mechanism is the opposite of "nobody updates it".
`.todo/.history.md` says in so many words that **the column is not maintained per
deletion -- appending the row is the whole job**. Two workers updated it by hand anyway
on 2026-09-03, one to 69 and one to 70, each incrementing what they found.

**A number that is documented as approximate and then maintained by some closers is worse
than either policy.** Left alone it would read as obviously stale and nobody would trust
it. Updated on most closes, it looks maintained, and the two it missed are invisible.
The fix has to remove the choice -- derive the number or delete the column -- because as
long as a human CAN update it, some will, and the ones who do make the ones who did not
undetectable.

Not corrected to 72 here on purpose: the drift is this item's evidence, and setting it
right without changing the mechanism only restarts the clock.
