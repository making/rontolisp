# k87. Java library: `java.text` beyond `MessageFormat` and `Normalizer`

Difficulty: High

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from `.todo/k72`.

`java.text` has `MessageFormat` (no format types), `Normalizer`, `Format`, `FieldPosition`,
`ParsePosition` and `ParseException`. Refused by name: `NumberFormat`, `DecimalFormat`,
`DecimalFormatSymbols`, `ChoiceFormat`, `DateFormat`, `SimpleDateFormat`, `BreakIterator`,
`Collator`, `CharacterIterator`, `StringCharacterIterator`, `AttributedString`, and with them
`MessageFormat`'s format types (`{0,number}`, `{0,date}`, `{0,choice,..}`).

- Most valuable first by what programs name: `DecimalFormat` / `NumberFormat` (patterns,
  rounding modes, grouping, parse), `SimpleDateFormat` over `java.util.Date` (its pattern
  letters beside `DateTimeFormatter`'s, which k43 wrote), `ChoiceFormat` and the format types
  of `MessageFormat`.
- The words and digits of the locales the subset has (`Locale`: the root and the English
  ones) are CLDR's as a running JDK writes them (the Unicode entry of
  `THIRD-PARTY-NOTICES.txt`); what `MessageFormat` already writes for a number with no type
  (`.kb/java-frontend.md`, "The library", k28) is `DecimalFormat`'s `#,##0.###` and is the
  place to start.
- Measure what each costs a program that names none of it.
