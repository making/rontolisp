# h67. `pathname` / `truename` of a file stream

Difficulty: Medium

Found 2026-10-31 loading cl-fad: `(pathname stream)` on a stream `open` returned signals
`PATHNAME: not a pathname designator: #<STREAM>` (prelude `pathname`, `LispPreludeLibrary`).
CLHS makes a file stream a pathname designator: `pathname` answers the pathname used to open
it, `truename` the file's truename. A stream value is `LispLayout.STREAM` (`HANDLE`, `KIND`)
and records no path.

Blocks cl-fad's `with-output-to-temporary-file` (its value is `(pathname stream)`) and
`with-open-temporary-file` (deletes `(pathname stream)`); `open-temporary` itself works
(`ClFadE2eTest`). Also ANSI `OPEN.66`-family tests (`.kb/read-load-streams.md`).

Record the path per stream on all four backends -- a slot on the stream value, or a registry
keyed by handle like `%file-stream-types` (`.kb/read-load-streams.md`, "Element types wider
and narrower than one octet"), selected only when the program can ask -- and make `pathname`,
`namestring`, `truename` and the path-taking operators accept a file stream.
