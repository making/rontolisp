# m31. --emit-wit omits the async-called functions of a user import

Difficulty: Medium

Found by m20. The world `--emit-wit` prints for a user import (`WitImportWorldEmitter`) says
less than the component's type where the program calls a function through an async lowering
(`WasmComponentImportCompiler.Import.calls()`): the emitter walks `distinctDecls()` alone.
Measured 2026-11-25 (wasm-tools 1.263.0, `--component --emit-wit` against
`wasm-tools component wit` of the same bytes; the same before m20):

- `examples/net/echo-server.lisp`: `wasi:sockets/types` lacks
  `connect: async func(remote-address: ip-socket-address) -> result<_, error-code>` in
  `resource tcp-socket`; `wasi:sockets/ip-name-lookup` lacks `resolve-addresses: async func`
  and the `ip-address` and `error-code` variants only it reaches.
- `examples/net/dexador-client.lisp` (TLS): the connector's
  `connect: static async func(this: connector, server-name: string) -> result<_, error>` and
  the `use types.{error};` it needs are missing, and a resource with no member prints
  `resource error {` `}` where the tool prints `resource error;`.

Not this item: the type aliases the emitter keeps (`type ipv4-address = ...`) and the serve
template's restored `use` clause are deliberate (`WitOracleE2eTest`, the fetch and serve
cases).

- Emit the async-called functions with the types they reach, in the encoder's declaration
  order (`WitComponentTypeEncoder`).
- Pin against the tool: `WitOracleE2eTest`'s sockets and fetch cases compare structure today;
  compare the function set of each interface with the tool's.
