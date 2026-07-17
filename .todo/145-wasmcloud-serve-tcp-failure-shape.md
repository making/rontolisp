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

## Live verification (2026-07-17, wash 2.5.2, wasmtime 46.0.1) -- ALL CONFIRMED

A probe serve component (scratch `wash-probe/probe.lisp`: routes /loop /raw
/lan /svc /nilread /nilclose) run under both `wasmtime serve` (baseline,
real loopback) and `wash dev`:

1. **`:connection-refused` confirmed.** Under wash, a raw
   `%sock:tcp-socket-create` + await `tcp-socket-connect` to 127.0.0.1:7777
   signals `rontolisp:wit-error` with payload `:connection-refused` -- the
   virtual-loopback chain exactly. (`%sock:` is directly callable from user
   source once any tcp-* built-in triggers the sockets.lisp splice.)
   `tcp-connect` on the same target = nil. Under wasmtime serve (real
   loopback, interpreter service-leet listening) both connect.
2. **Real-network path confirmed.** `tcp-connect "192.168.11.76" 7777` (the
   host's LAN IP, interpreter service-leet) from inside `wash dev` connects
   and completes the leet roundtrip -> "H3110 W0r1d". wasi:sockets@0.3 works
   under wash 2.5.2; only loopback destinations are virtualized.
3. **The 500 = a cast-failure TRAP, not a signaled condition.** With a nil
   sock, `(read-line nil)` AND `(close nil)` each die on a serve component
   with `wasm trap: cast failure` -- handler-case does NOT catch it (wasm-GC
   catches signaled conditions only; traps stay uncatchable), so the host
   answers its own 500 page. Identical under wasmtime serve and wash. The
   http-api 502 branch is unreachable on this backend; the fix is an
   explicit `(if sock ...)` guard in `leet-request` answering 502. (The
   nil-as-stream trap itself is kin to the fixed `.todo/144` trap family --
   arguably read-line/close should reject a nil handle cleanly on serve;
   separate item if we care.)
4. **The full wasmCloud path WORKS -- both halves in one `wash dev`.**
   `service-leet.lisp` compiled `--component` (world exports
   `wasi:cli/run@0.3.0`) and registered as `.wash/config.yaml`
   `dev.service_file` runs as a wasmCloud SERVICE, binds the virtual
   loopback :7777, and the probe component's
   `tcp-connect "127.0.0.1" 7777` roundtrips -> "H3110 W0r1d". ONE caveat:
   `(rontolisp:tcp-listen 7777)` -- host defaulted to "0.0.0.0" -- is DENIED
   (wash's p3 bind check `addr.ip().is_loopback()` runs BEFORE the
   0.0.0.0->127.0.0.1 rewrite in `tcp.rs start_bind`, unlike what the
   template README advertises; possible upstream p2/p3 inconsistency), so
   the service must bind explicitly: `(rontolisp:tcp-listen 7777
   "127.0.0.1")`. This resolves the service-leet half of `.todo/053`.

## Docs + example follow-through -- ALL DONE 2026-07-17

- `examples/wasmcloud/service-tcp/`: service-leet.lisp listens on an explicit
  "127.0.0.1"; a new `.wash/config.yaml` chains both `--component` builds
  (wash runs `build.command` through a shell, `&&` works) and registers
  `dev.service_file: service-leet.wasm`; http-api's `leet-request` guards a
  nil sock -> 502 "leet service unavailable" (was the read-line-nil trap).
- `examples/wasmcloud/README.md`: wasmCloud cell flipped to "yes (both
  halves; service-leet runs as a v2 service)", prose rewritten to the
  virtual-loopback story, `wash dev` quick start added.
- `http-api.lisp` / `service-leet.lisp` headers rewritten (wasmCloud run
  instructions included).
- `.todo/053` service-tcp section rewritten.
- `doc/{en,ja}/guides/tcp-sockets.md` + `.kb/tcp-sockets.md` corrected.
- Verified live 2026-07-17 with the actual example files: `wash dev` in the
  example dir answers `POST /task` -> "H3110 W0r1d" (both halves in one
  host); http-api under `wasmtime serve` answers 502 without a leet service
  and 200 with the interpreter one; interpreter http-api and `wasmtime run`
  service-leet unchanged-green. NOTE: the wash build takes `rontolisp` from
  the PATH -- an installed binary older than the `.todo/144` serve fix
  (400654d) produces a serve component whose handler traps; reinstall after
  pulling.

Nothing remains in this item. Possible upstream reports (optional, not ours
to track here): the stale "Host provides interfaces" advertisement, and the
p3 TcpBind check running before the 0.0.0.0->loopback rewrite that the
service-tcp template README promises.
