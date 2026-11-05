# i44. McCLIM's presentation types need built-in classes outside standard-class

Difficulty: High

Done: `built-in-class` and `forward-referenced-class` are metaclasses beside `standard-class`, and
`(ql:quickload :clim-core)` loads with presentation types working (`.kb/clos.md`, "McCLIM").

Left, measured 2026-11-05 on the interpreter: `(ql:quickload :clim)` is `clim-core` plus `drei-mcclim`,
which stops in `Libraries/ESA/utils.lisp:202`, the long form of `define-method-combination` (also
arnesi's `wrapping-standard` and `cc-standard`, serapeum's `standard/context`).
