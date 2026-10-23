# g67: lispresen breaks a line between a number and its counter, and inside a date

Difficulty: Medium

lispresen's phrase-based line breaking (BudouX segments plus the spaces written between
half-width and full-width text) splits units a reader takes as one:

- A half-width number and the counter after it, written with the customary space:
  the flow label `"TrueSource 版は 1 時間以内"` wrapped as `TrueSource 版は 1` /
  `時間以内`; `"... (1 時間以内)"` wrapped as `... (1` / `時間以内)`. Writing `1時間`
  without the space avoids it.
- A date: a `source` line ending in `2026-10-22` wrapped as `2026-10-` / `22` (a break
  after a hyphen inside a digit run).

Seen 2026-10-23 on flow labels and a `source` line; body text, list items, table cells and
callouts share the same segmenter and likely the same breaks.

## Goal

The segmenter keeps a half-width number together with the word that follows it across a
single space (`1 時間`, `2 営業日`, `211 件`), and never breaks inside a digit run joined
by `-`, `/`, `.`, `:` or `,` (dates, versions, times, `1,000`) -- in every element that
wraps text, with the build-time measurement and the browser agreeing. Extend the existing
segmentation unit tests; the golden deck stays byte-identical across backends.
