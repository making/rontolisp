# Resolve java: sites with unknown argument kinds without reflection

Difficulty: Medium

Depends on a14.
Plan: for a closed candidate set, emit an instanceof / getClass()== decision
tree over Lisp representations reproducing the cost model exactly; then
sequences -> arrays (static component type, newarray) and List, and varargs
packing. Pin parity with the interpreter on mixed-kind call sites.
