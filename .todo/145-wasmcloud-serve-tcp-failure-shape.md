# wasmCloud serve+tcp: verify what actually fails and why

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

## RESOLVED by source reading (2026-07-17, wasmCloud repo @ 1b6a6826a,
## cross-checked against the v2.5.2 tag = the wash actually used)

The original "host is 0.2-only" story is WRONG. wash 2.5.2 DOES provide
`wasi:sockets@0.3` -- https://wasmcloud.com/docs/runtime/#wasi-03 is correct.
The connect fails for a different reason: **loopback is virtualized**.

1. **wasi:sockets 0.3 is linked.** `crates/wash-runtime/src/sockets/` has a
   full custom p3 implementation (`host_tcp_p3.rs`, `host_udp_p3.rs`,
   `host_ip_name_lookup_p3.rs`), registered on every component linker via
   `sockets::add_p3_to_linker` (`engine/mod.rs`, "Sockets with our custom P3
   implementation (with loopback)"). Present since "feat: update to wasmtime 46
   and enable wasip3 by default" (7b8f94edd, 2026-06-23), so in v2.5.x.
2. **The startup advertisement is stale, not authoritative.** The "Host
   provides interfaces" list is a hardcoded string set in
   `wash-runtime/src/host/mod.rs` `wit_world()`; at v2.5.2 it names
   `wasi:sockets@0.2.0` only and omits ALL p3 wasi (even `wasi:http@0.3.0`,
   which the same wash demonstrably serves). Do not read that log as the
   feature set. (Upstream bug candidate.)
3. **Why the connect failed: 127.0.0.1 inside a component is a per-workload
   in-memory virtual network, never the host's real loopback.**
   `sockets/tcp.rs start_connect`: a loopback destination is routed to
   `loopback::Network` (`sockets/loopback/` -- HashMaps of port -> mpsc
   channel endpoints), created `Arc::default()` per WORKLOAD in
   `engine/mod.rs` and shared by that workload's service + components. Our
   interpreter service-leet listened on the real OS loopback, which the
   virtual net cannot reach BY DESIGN (isolation is the point; the
   service-tcp template README: "services are never reachable from outside
   the host" -- and vice versa). Nothing bound at virtual :7777 =>
   `ErrorCode::ConnectionRefused` (`loopback/mod.rs connect_tcp`) => the
   result error arm => `rontolisp:wit-error` => `%tcp-connect-f`'s
   handler-case => nil. Chain fully consistent with the observation; the
   0.2/0.3 story played no part.
4. **Policy (linked_call.rs `build_ctx_from_template`):** `TcpConnect` is
   allowed for ANY address; a NON-loopback destination takes the real-OS-
   socket path (`tcp.rs` `Self::Network` + `is_loopback()==false`). So
   `rontolisp:tcp-connect` to a real remote address is expected to WORK under
   `wash dev` -- untested live, but the code path is unconditional.
   `TcpBind`: components = denied; services (`is_service`) = loopback only,
   with 0.0.0.0 silently rewritten to 127.0.0.1.
5. **How wasmCloud's own service-tcp template works** (`templates/service-tcp`
   in the wasmCloud repo): service-leet is a `wasi:cli/run` wasm component
   deployed as a wasmCloud SERVICE (`.wash/config.yaml` `dev.service_file`),
   binding the VIRTUAL loopback :7777; http-api components connect within the
   same workload's virtual net. That is the shape our example must adopt to
   run both halves on wasmCloud.
6. **The 500 (read of the code, not a live trace):** with sock nil,
   `leet-request` does `(write-line payload nil)` (stdout on serve, fine) then
   `(read-line nil)` against the serve stdin stub and `(close nil)` -- one of
   those signals, the handler has no handler-case, serve answers 500. The
   nil-reply 502 branch is never reached because the failure is a signal, not
   a nil reply.

## Still to verify (live, next wash session)

1. Probe route wrapping `%sock:` calls in handler-case: confirm the payload is
   `:connection-refused` at the connect call (expected per the chain above).
2. `rontolisp:tcp-connect` to a NON-loopback address from inside `wash dev`
   (e.g. the host's LAN IP where service-leet listens, or any public echo
   service) -- expected to succeed via the real-network path.
3. Trace the `POST /task` 500 to `(read-line nil)` vs `(close nil)`; guard a
   nil sock in `leet-request` and answer 502 explicitly either way.
4. The real wasmCloud path for the example: compile `service-leet.lisp` as a
   `--component` (needs tcp-listen/accept on the component backend -- the
   `%sock:tcp-socket-listen` surface exists in sockets.lisp) and register it
   as `dev.service_file` so both halves share one workload's virtual
   loopback. This re-opens the service-leet half of `.todo/053`.

## Docs state -- now known WRONG, fix after the live re-test

`examples/wasmcloud/README.md` (the "no (host has no `wasi:sockets` 0.3)"
cell + prose), the guides, and the `http-api.lisp` header all say wasmCloud
does not provide `wasi:sockets` 0.3. That is false for wash >= 2.5.x. The
correct statement: wasmCloud provides wasi:sockets 0.3, but loopback inside a
component is a per-workload virtual network -- a component cannot reach a
process on the HOST's 127.0.0.1; both halves must run inside wasmCloud (the
service model, item 4 above) or the connect must target a non-loopback
address. Rewrite those after items 1/2/4 confirm live.
