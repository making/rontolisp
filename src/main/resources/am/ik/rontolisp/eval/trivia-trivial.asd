;;;; Hand-authored replacement for trivia.asd that maps system "trivia" to the
;;;; trivia.trivial contents (the :trivial optimizer route), the
;;;; postmodern-deps.asd precedent.
;;;;
;;;; Upstream system "trivia" depends on trivia.balland2006 (the optimizing
;;;; compiler for match clauses) and switches it on with a load-op :after hook
;;;; -- a :perform clause AsdfSystems ignores as metadata anyway. balland2006
;;;; additionally needs iterate (a whole loop DSL) and type-i, a large,
;;;; separate substrate investment that buys only match-clause OPTIMIZATION and
;;;; zero semantics: trivia.trivial is upstream's own sanctioned base ("Systems
;;;; that intend to enhance Trivia should depend on this package, not the
;;;; TRIVIA system, in order to avoid the circular dependency"), and the
;;;; sxql/mito match semantics are identical, just unoptimized.
;;;;
;;;; Re-evaluation trigger (recorded in .kb/asdf.md): if a real consumer needs
;;;; iterate itself, or interpreter match performance becomes the bottleneck
;;;; (the interpreter re-expands user macros every evaluation, multiplying
;;;; unoptimized match cost), do iterate + type-i + balland2006 as
;;;; their own milestone and delete this override.
;;;;
;;;; No components: the trivia.trivial dependency chain (level2 -> level1 ->
;;;; level0) carries all the sources; this system is pure metadata, like the
;;;; upstream trivia.trivial system itself.

(defsystem "trivia"
  :description "Pattern matcher (trivia.trivial route: the :trivial optimizer)"
  :depends-on ("trivia.trivial"))
