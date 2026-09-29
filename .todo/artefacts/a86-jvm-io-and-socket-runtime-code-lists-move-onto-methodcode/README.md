# Moving a raw `List<Integer>` builder onto `MethodCode`: the tools

The recipe is `.kb/jvm-method-size-limits.md`, "How a slice moves"; these extend a85's tools
(`../a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode/`, its `README.md`) from a builder on
an assembler to one writing bytes. Run from the repository, in this order:

- `raw.py FILE...` -- the instructions, the position/patch pairs as labels (forward, listed,
  backward) and the signatures; prints what is left for a hand (handler bounds, `int p = -1`
  sentinels, an opcode passed as a value, a `.index()` under a conditional).
- `pool.py FILE...` -- the pool wrappers to entries (a85's `migrate_pool`), same minting order.
- `records.py FILE RECORD` -- drops a method record's declared max_stack/max_locals.
- a85's `jc.sh`, then `unentry.py` (the `.entry()` calls code already on `MethodCode` made on a
  field that is now an entry) and a85's `fix.py` (a wrapper still meeting an entry), in turns.

Verify with a85's `extract.py` + `runchunks.sh` + `rediff.sh` and `cmpcli.sh`, plus
`gates/*.lisp`: programs that switch on every gate of the I/O and socket runtimes at once (tcp and
tls, `*error-output*`, `:direction :io`, `file-position` over a character stream, the bulk
transfers, the quantized buffer), compiled in process by a85's `Cmp.java`.

Expected differences: the build's timestamp in version strings only.
