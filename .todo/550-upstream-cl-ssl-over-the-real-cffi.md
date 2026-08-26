# 550. Upstream cl+ssl over the real CFFI: the last blocker

Difficulty: Medium

`.todo/542` probed upstream cl+ssl against the real CFFI backend and recorded the
result in `.kb/cffi.md`: it does NOT load, and **not one blocker was in CFFI**. Three of
the five were fixed by that item; this is the fourth and the fifth.

This is still a PROBE, not a migration. The `cl+ssl` shim over `rontolisp:tls-upgrade`
(`.kb/tcp-sockets.md`) stays the default whatever happens here -- it works on the WASM
component backend, where CFFI never will, and it needs no OpenSSL on the machine. The
value of finishing the probe is the measurement: cl+ssl is the large, old,
`defcvar`-and-callback-heavy binding, and how far it gets is how far the backend
reaches.

## What is left

1. **`flexi-streams:flexi-stream` as a real wrapper CLASS.** `src/streams.lisp` has
   `(defmethod ssl-stream-handle ((stream flexi-streams:flexi-stream)) (ssl-stream-handle
   (flexi-streams:flexi-stream-stream stream)))`, and `make-ssl-client-stream` wraps its
   answer in `make-flexi-stream` when an `:external-format` is given. The shim's whole
   design is that a flexi WRAPPER IS the underlying stream (`.kb/gray-streams.md`), so
   there is no class and no `flexi-stream-stream`. Either the shim grows a real wrapper
   -- an encoding-carrying Gray stream delegating to the inner one, which is what the
   name means everywhere else -- or the probe stops here for good and says so.
2. **The `CL+SSL` package is pre-registered** (`PackageRegistry`, for the shim's own
   symbols), and `defpackage` over an existing package SIGNALS here where CL says it
   redefines. The probe stepped around it by renaming the package in a scratch copy.
   Whichever way (1) goes, this one is worth settling on its own: CLHS is explicit that
   `defpackage` on an existing package modifies it, and a pre-registered name that no
   library may redeclare is a trap for every shimmed package, not just this one.

## How to reproduce the probe

Copy the cached release out of the dist cache, rename the systems (and, for the moment,
the package) in the scratch copy, quickload the dependencies, then
`(asdf:load-system "...")` with `--system-path` pointed at the copy. The blockers arrive
one at a time, each as an unresolvable qualified symbol -- which is the useful property:
the failure names exactly what is missing.
