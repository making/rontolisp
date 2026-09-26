# The reader reads a supplementary-plane character literal as one character

Difficulty: Low

Reported 2026-09-26 while working on the java: resolution model (not yet reproduced by
hand): the reader does not read a literal such as #\😀 (U+1F600, outside the BMP) as one
character. Likely a char-based scan in the #\ reader that sees the UTF-16 surrogate pair.

Plan: first a failing test on all four backends ((char-code #\😀) => 128512,
(length (string #\😀)) => 1), then fix the reader (code points, not chars), including
the formatter's CST front end if it scans #\ the same way (.kb/formatter.md).
