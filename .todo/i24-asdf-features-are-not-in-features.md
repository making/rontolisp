# i24. `:asdf`, `:asdf3` and the other ASDF features are not in `*features*`

Difficulty: Medium

Real ASDF pushes `:asdf`, `:asdf2`, `:asdf3`, `:asdf3.1`, `:asdf3.2` and `:asdf3.3`; nothing here does,
so a `#+asdf3` guard takes the branch for no ASDF. Measured 2026-11-04 in the 197 cached releases:
`#+asdf3` 14, `#-asdf3` 13, `#+asdf` 12, `#-asdf` 9, `#+asdf3.1` 7, `#+asdf2` 2.

Measured stop: `hu.dwim.def.asd:7` is `(unless (or #+asdf3 (uiop:version<= "2.31.1" (asdf-version)))
(error "You need ASDF >= 2.31.1 ..."))`, which reads as `(unless (or) (error ...))`. Its next stop is
`:class "hu.dwim.asdf:hu.dwim.system"` (`.todo/h93`).

Decide first what the features promise: a program under `#+asdf3` may reach names this ASDF answers
with a signalling stub, where `#-asdf3` was the branch that ran. Measure both over the cached releases
before choosing. `Features`' base sets also pin `(length *features*)` in ci-spec
`reader-features-variable`, and the compile paths read with the target's set
(`.kb/reader-features.md`).
