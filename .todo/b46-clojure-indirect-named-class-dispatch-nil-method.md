# Clojure: an inline dispatch wrapper calling a named class fn still misses the nil method

Difficulty: Small (inline the recorded datum at the call site when `inDispatchFn`, or map at dispatch).

## Gap (oracle `clj` 1.12.6.1673, measured 2026-10-01 during b40)

b40 re-lowers a recorded class-calling definition when the `defmulti` dispatch
datum IS its name, but a call to that name nested inside an inline dispatch
datum keeps the old shape: the inner call lowers to a direct call of the
already-lowered `defun` (which answers the `:nil` keyword for nil outside the
dispatch lowering), so the dispatcher's null test misses the nil method:

```clojure
(defn myclass [x] (class x))
(defmulti wi (fn [x] (myclass x)))
(defmethod wi nil [_] "was-nil")
(defmethod wi :default [x] "dflt")
(println (wi nil))  ; oracle "was-nil", repo "dflt"
(println (wi :nil)) ; oracle "dflt", repo "dflt" (already correct)
```

## Acceptance

- `clojure-spec.yaml`: indirect-named-class-dispatch pin (nil hits the nil
  method, `:nil` still falls to the default), green on all four backends.
- `.kb` rows updated; docs en+ja same commit if user-facing.

## Depends on

b19 (host-class dispatch), b32 (the `(:C%NIL)` marker), b38 (inline wrapped
`class` answers nil itself in dispatch position), b40 (named `defn` / `def`'d
fn recording in `ClojureLowering.classDispatchFns`).
