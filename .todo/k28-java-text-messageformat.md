# k28. Java front end: `java.text.MessageFormat`

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first.**

Second gap toward `.todo/k33`: every violation message of YAVI is
`new MessageFormat(pattern, locale).format(args)` (`message/SimpleMessageFormatter`), with
patterns such as `"{0}" must not be null` and `The size of "{0}" must be greater than or
equal to {1}. The given size is {2}`. Measured 2026-11-19 with a stand-in reading `{n}`
alone (`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`).

- `java.text` joins the library's packages. `MessageFormat(String)`,
  `(String, Locale)`, `format(Object[])`, the static `format(String, Object...)`,
  `applyPattern`, `toPattern`: quoting (`''`, `'{'`), `{n}`, and of the format types what
  a probe shows a number and a string print with no type given (an `Integer` argument
  prints grouped: `1,000`). `{n,number}`, `{n,date}`, `{n,choice}` are refused by name
  until something needs them; `NumberFormat` is not a prerequisite for the milestone if
  the grouping of an integer is written beside it.
- Learn the rules by a grid printed under JDK 25 and under the four backends, as j98 and
  k05 did.
- Done when the corpus pins it and `shim/shim/MessageFormat.java` is gone from the k24
  artefacts.
