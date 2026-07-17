# wasmCloud serve+tcp: verify what actually fails and why (the 0.2-only story)

Split out of `.todo/144`'s resolution log on 2026-07-17: the claim "the host
provides `wasi:sockets@0.2.0` only, so `tcp-connect` returns nil and
`POST /task` fails with 500" mixes observation with unverified inference, and
the user flagged it. The serve top-level-init FIX itself is done and gated
(`.todo/144`); this item is only about pinning down the wasmCloud behavior.

## Observed (2026-07-17, wash 2.5.2, after the serve top-level-init fix)

- `wash dev` hosts a serve+tcp component; non-tcp routes answer 200.
- A connect-only probe route answered "no-conn", i.e. `rontolisp:tcp-connect`
  returned nil -- while an interpreter `service-leet` WAS listening on the
  host's 127.0.0.1:7777.
- `examples/wasmcloud/service-tcp/http-api.lisp` `POST /task` answered 500,
  `GET /` 200.
- The `wash dev` startup log advertises `wasi:sockets@0.2.0` interfaces only
  (no 0.3) among "Host provides interfaces".

## Unverified inference (do not re-document until checked)

- WHY the connect fails: "no 0.3 provider behind the import -> host errors ->
  the wit-error -> nil convention" is plausible but no log line or probe
  proved which `%sock` call failed or with what error. wash may lazy-link the
  0.3 import over wRPC, reject it at link time, or stub it -- each would look
  like nil from the outside.
- WHY 500 and not 502: with a nil sock, `leet-request` does
  `(write-line payload nil)` (stdout on serve) then `(read-line nil)` (the
  serve stdin stub) -- the exact failure that turns that into 500 was never
  traced; it may be a different failure entirely.
- Whether an outbound connection from a wash-dev-hosted component to the
  host loopback is even PERMITTED by wash's policy, independent of 0.2/0.3.

## To verify

1. A debug route that wraps each `%sock:` call in `handler-case` and returns
   the `rontolisp:wit-error` payload/message -- which call fails, and how.
2. Read the wash host's debug logs (RUST_LOG) for the sockets import: linked?
   rejected? stubbed?
3. Trace the `POST /task` 500 (nil-sock path under serve: stdout write +
   stdin-stub read) and decide whether http-api should guard a nil sock and
   answer 502 explicitly.
4. Re-test on newer wash releases; wasmCloud gaining `wasi:sockets` 0.3 flips
   the `examples/wasmcloud/README.md` cell and re-opens the service-leet half
   (`.todo/053`).

## Docs state (kept deliberately vague until this is verified)

The guides / README / http-api header say only that wasmCloud does not
provide `wasi:sockets` 0.3 (its startup log's own advertisement) and that tcp
connections fail there; the nil/500 detail lives here, not in user docs.
