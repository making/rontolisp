# Clojure: string `\u` escape error shapes vs the oracle

Follow-up from b44 (which added the octal arm and refused `\'` like the
oracle). Measured on the host oracle `clj` 1.12.6.1673 during b44:

- `\u` with non-hex digits leaks `NumberFormatException` instead of a
  positioned read error (the oracle says `Invalid unicode escape: \uz`
  for the first digit, `Invalid digit: x` for a later one -- its string
  reader checks the first `\u` digit itself, then runs the shared
  `readUnicodeChar` with exact length 4).
- A short `\u12` (before the closing quote) says `truncated \u escape`
  here vs the oracle's `Invalid character length: 2, should be: 4`
  (the shared reader throws that when fewer than 4 digits precede a
  whitespace/macro/EOF stop).

## Acceptance

- `ClojureReaderTest` pins `"\uzzzz"` (`Invalid unicode escape`), a
  later-digit refusal (`Invalid digit`), and the short-`\u` length
  refusal with the oracle's exact message.
- `ClojureSession`'s incomplete-input sniff still treats a truly
  truncated `\u` at end of input as incomplete (check the message
  rename against its `truncated` match).

## Depends on

b44 (whose `readString` `\u` arm this reshapes).
