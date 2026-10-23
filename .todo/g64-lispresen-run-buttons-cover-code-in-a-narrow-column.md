# g64: lispresen's Run/Reset buttons cover code in a narrow column

Difficulty: Medium

A runnable code block (`code` without `:run nil`) carries the player's Run/Reset tools
over its top-right corner. Inside a narrow `column`, the column's width is solved from
the content alone, so the tools overlap the code's first lines. The column width solver
does not reserve the tools' width (or the code panel's top padding) for a runnable block.

## Goal

The fit solver accounts for the tools of a runnable block -- by reserving their width or
height in the layout it solves, so the build-time check and the browser agree -- and a
runnable block in the narrowest legal column shows no overlap in Chrome and Safari. Pin
the reserved size with the unit tests, not with a visual test.
