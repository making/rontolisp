# g72: lispresen always declares its page Japanese

Difficulty: Low

`write-html` writes `<html lang="ja">` for every deck (`lispresen/src/html.lisp`), while
`lispresen new --lang en` writes an English starter deck and English decks are said to
work. A screen reader then reads an English deck with a Japanese voice, the browser
picks Japanese glyph variants and hyphenation rules for it, and the browser's translate
offer misfires. The deck header has no way to say what language it is in.

## Value

- For the user: an English (or mixed) talk is announced correctly by assistive
  technology, and the published page is indexed and offered for translation as what it
  is. The accessibility the lints already enforce (alt text, contrast) is undone by a
  wrong `lang`.
- Design: the language is content, not geometry; it does not touch measurement.
  Japanese stays the default and the tuning target, so a deck without the option is
  byte-identical to today's.
- Cost: one option and one attribute, plus the starter deck.

## Shape

- `(deck :lang "en")`: a BCP 47 tag, written as given into `<html lang>`; a malformed
  tag (not letters, digits and `-`) is an error. Default `ja`.
- `lispresen new --lang en` writes `:lang "en"` into the English starter deck
  (`lispresen/starter/en.lisp`).
- An inline `(lang "en" inline...)` for a phrase in another language inside a Japanese
  slide is a possible follow-up; decide while doing this whether it is worth a form.
- The phrase segmentation stays BudouX's Japanese model whatever the language; note in
  DESIGN.md that `:lang` changes the declaration only.

## Verification

- Extend the deck-header unit test in `lispresen/test/lispresen-test.lisp` with the
  attribute; the starter-deck leg of `lispresen/check.sh` covers `--lang en`.
- `lispresen/check.sh`: byte-identical output on every backend; the golden deck is
  unchanged.
- README.md form table and DESIGN.md (Deck format, HTML output, Starter deck) updated.
