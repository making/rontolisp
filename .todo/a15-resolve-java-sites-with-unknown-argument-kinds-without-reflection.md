# Resolve java: sites with unknown argument kinds without reflection

Difficulty: Medium

Depends on a14.

a13 note: an unresolved site today is resolved from the RUN-TIME receiver class
(Clojure's reflective fallback) even when its receiver has a static type; a closed
candidate set from that static type with a decision tree over the kinds is a new
semantic (the documented upper-bound difference, now for partially typed sites
too) -- the interpreter must adopt it in the same change. JavaSiteResolver already
enumerates the kind combinations (JavaStaticType.Kinds) the tree needs.
Plan: for a closed candidate set, emit an instanceof / getClass()== decision
tree over Lisp representations reproducing the cost model exactly; then
sequences -> arrays (static component type, newarray) and List, and varargs
packing. Pin parity with the interpreter on mixed-kind call sites.
