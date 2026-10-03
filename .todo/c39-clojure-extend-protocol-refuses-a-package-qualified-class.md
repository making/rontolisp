# c39. Clojure: `extend-protocol` refuses a package-qualified class spelling

Difficulty: Low

`(extend-protocol P java.lang.String (f [s] :str))` and `(extend-protocol P clojure.lang.IPersistentMap ...)`
fail at lower time with `extend-protocol needs a core type, not java.lang.String` (2026-10-03); the oracle
(`clj` 1.12.6) takes them, and so does `defmethod` here (`(defmethod m java.lang.String ...)` dispatches).
`ClojureProtocolLowering.extendKeyForm` matches the simple spelling only (`String`, `IPersistentMap`, ...),
while the multimethod side maps through `ClojureDispatchLowering.DISPATCH_CLASS_KEYWORDS` after its own
qualification handling. The same `extendKeyForm` serves `extend-type`, `extend` and `extends?`.

Strip a `java.lang.`/`clojure.lang.` package the way the multimethod side does (a record/deftype name
keeps its own resolution), and pin `extend-protocol`/`extend-type`/`extends?` over qualified spellings in
`clojure-spec.yaml`.
