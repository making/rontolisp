# i60. `search` over a string allocates per byte, which an interpreting host cannot afford

Difficulty: Medium

On a JIT `search` over a held body costs little. `sluice-client` interprets plugins with Endive, where it decides what a
filter may do. Measured 2026-11-05 on a JVM warmed by a few exchanges, a 64KiB body through a filter holding it, best of 5:

| The filter | Time |
| --- | ---: |
| The Rust SDK's `http_body`, its search included | 17ms |
| `get-buffer` alone | 47ms |
| `get-buffer-octets` alone | 33ms |
| `get-buffer-octets` and a typed loop over the octets | 218ms |
| `sdk-examples/http_body`: `get-buffer` and `search`, `--optimize` | 1.46s |
| The same, `--optimize=size` | 2.82s |

sluice's default `call-timeout` is 1s, so `http_body` fails there from a few tens of KiB. It also allocates about 2 structs
per byte of the body (196,834 over 6 exchanges of 16KiB), all garbage, which reaches sluice's default `max-memory` of 16MB
within one call: sluice cannot count a running call's garbage out yet (its `.todo`, "Count a plugin's garbage inside a call").

## What to find out

- What those structs are, and whether `search` on two strings can run without them, as the typed loop does.
- What the typed loop's remaining 2us per byte is. It runs at `(safety 0)`, and Rust's search takes about a tenth of it.

## Also

`http_headers` costs 20-24ms per exchange against the Rust SDK's 1.4-2.0ms, allocating 350-390KiB. That is the run-time
`format` of `log-*`: lever 1 of i51 ought to answer it, not measured here.

## Done when

`sdk-examples/http_body` holds a 64KiB body on `sluice-client` within the default `call-timeout` and `max-memory`.
