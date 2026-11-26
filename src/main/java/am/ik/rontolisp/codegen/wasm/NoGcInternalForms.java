package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Rewrites the internal string forms a front end or a spliced library emits into the
 * public operators the scalar backend compiles, before any of its passes reads a body: a
 * {@code %string-concat} is a {@code concatenate} there, a {@code %decimal-piece} or a
 * {@code %princ-piece} a {@code princ-to-string}, a {@code %substring} a {@code subseq},
 * a {@code %normalize-string} its argument (the backend has one string representation, no
 * mutable one), a {@code %string-code} the reads it expands to; a {@code write-line} or
 * {@code write-string} to the standard output its {@code princ}, and a
 * {@code (funcall #'f x)} the call {@code (f x)}. The other backends keep the internal
 * forms for what they spare there -- a mutable result's wrap, a {@code *print-base*} test
 * -- which this backend never pays.
 */
final class NoGcInternalForms {

	private NoGcInternalForms() {
	}

	/**
	 * The form with its internal string forms rewritten, the original where it has none.
	 * Quoted data and a function's name are left alone. In an exact module (one written
	 * for Common Lisp, whose nil and integer the scalar backend holds apart) the sequence
	 * functions that answer an index or nil are rewritten too: {@code position} of a
	 * character in a string and {@code search} of a string in a string are the backend's
	 * {@link #STRING_SEARCH}, {@code digit-char-p} the arithmetic of an ASCII digit's
	 * weight. The dialect, whose nil is 0, keeps refusing them: an index of 0 would read
	 * as nil there.
	 * @param form a form of a body
	 * @param exact whether the module is exact
	 * @return the rewritten form
	 */
	static LispVal normalize(LispVal form, boolean exact) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head) {
			String name = head.name();
			if (LispNames.isQuote(name) || LispNames.FUNCTION.equals(name)) {
				return form;
			}
			List<LispVal> parts = cons.toList();
			switch (name) {
				case LispNames.STRING_CONCAT -> {
					List<LispVal> rewritten = new ArrayList<>();
					rewritten.add(new LispSymbol(LispNames.CONCATENATE));
					rewritten.add(list(new LispSymbol(LispNames.QUOTE), new LispSymbol(LispNames.STRING)));
					for (LispVal part : parts.subList(1, parts.size())) {
						rewritten.add(normalize(part, exact));
					}
					return SourceProvenance.inherit(cons, list(rewritten));
				}
				case LispNames.DECIMAL_PIECE_INTERNAL, LispNames.PRINC_PIECE_INTERNAL -> {
					if (parts.size() == 2) {
						return SourceProvenance.inherit(cons,
								list(new LispSymbol(LispNames.PRINC_TO_STRING), normalize(parts.get(1), exact)));
					}
				}
				case LispNames.SUBSTRING_INTERNAL -> {
					List<LispVal> rewritten = new ArrayList<>();
					rewritten.add(new LispSymbol(LispNames.SUBSEQ));
					for (LispVal part : parts.subList(1, parts.size())) {
						rewritten.add(normalize(part, exact));
					}
					return SourceProvenance.inherit(cons, list(rewritten));
				}
				case LispNames.NORMALIZE_STRING -> {
					if (parts.size() == 2) {
						return normalize(parts.get(1), exact);
					}
				}
				case LispNames.STRING_CODE -> {
					if (parts.size() == 3) {
						return normalize(SourceProvenance.inherit(cons, LispMacroExpander.expandStringCode(cons)),
								exact);
					}
				}
				case LispNames.WRITE_LINE, LispNames.WRITE_STRING -> {
					// to the standard output, the one stream here: (write-line s) is
					// (prog1 (princ s) (terpri)), (write-string s) is (princ s)
					if (parts.size() == 2) {
						LispVal written = list(new LispSymbol(LispNames.PRINC), normalize(parts.get(1), exact));
						return SourceProvenance.inherit(cons,
								LispNames.WRITE_STRING.equals(name) ? written : list(new LispSymbol(LispNames.PROG1),
										written, list(new LispSymbol(LispNames.TERPRI))));
					}
				}
				case LispNames.FUNCALL -> {
					// (funcall #'f x) and (funcall 'f x) call f as (f x) does
					if (parts.size() >= 2 && parts.get(1) instanceof LispCons named && named.isProperList()
							&& named.toList().size() == 2 && named.car() instanceof LispSymbol quote
							&& (LispNames.FUNCTION.equals(quote.name()) || LispNames.isQuote(quote.name()))
							&& named.toList().get(1) instanceof LispSymbol function) {
						List<LispVal> rewritten = new ArrayList<>();
						rewritten.add(function);
						for (LispVal part : parts.subList(2, parts.size())) {
							rewritten.add(normalize(part, exact));
						}
						return SourceProvenance.inherit(cons, list(rewritten));
					}
				}
				case LispNames.POSITION -> {
					if (exact) {
						return normalize(SourceProvenance.inherit(cons, position(parts)), exact);
					}
				}
				case LispNames.SEARCH -> {
					if (exact) {
						return normalize(SourceProvenance.inherit(cons, search(parts)), exact);
					}
				}
				case LispNames.DIGIT_CHAR_P -> {
					if (exact) {
						return normalize(SourceProvenance.inherit(cons, digitCharP(parts)), exact);
					}
				}
				default -> {
				}
			}
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> rewritten = new ArrayList<>(parts.size());
		boolean changed = false;
		for (LispVal part : parts) {
			LispVal normalized = normalize(part, exact);
			changed |= normalized != part;
			rewritten.add(normalized);
		}
		return changed ? SourceProvenance.inherit(cons, list(rewritten)) : form;
	}

	/**
	 * The scalar backend's search of a string in a string, {@code (%no-gc-search needle
	 * hay start end from-end)}: the index of the first character of the first match of
	 * the needle in the hay's characters from {@code start} below {@code end} (nil: the
	 * hay's end), the last where {@code from-end} is true; nil where there is none. Each
	 * bound outside the hay is an error, a trap here. What {@code search} and a
	 * character's {@code position} are in an exact module
	 * ({@link #normalize(LispVal, boolean)}).
	 */
	static final String STRING_SEARCH = "%NO-GC-SEARCH";

	// (position item seq &key start end from-end) of a character in a string: the search
	// of the character's one-character string, made and given back around the search
	private static LispVal position(List<LispVal> parts) {
		Bound bound = bind("position", parts,
				List.of(LispNames.START_KEYWORD, LispNames.END_KEYWORD, LispNames.FROM_END_KEYWORD));
		// the item a character: a string's is refused, where (string item) would search
		// for it as a substring
		LispVal character = list(new LispSymbol(LispNames.CHAR_CODE), bound.arguments().get(0));
		LispVal search = list(new LispSymbol(STRING_SEARCH), list(new LispSymbol(LispNames.STRING), character),
				bound.arguments().get(1), bound.keyword(LispNames.START_KEYWORD, new LispInteger(0)),
				bound.keyword(LispNames.END_KEYWORD, LispNil.INSTANCE),
				bound.keyword(LispNames.FROM_END_KEYWORD, LispNil.INSTANCE));
		return bound.around(list(new LispSymbol(LispNames.WITH_ARENA_QUALIFIED), LispNil.INSTANCE, search));
	}

	// (search needle hay &key start2 end2 from-end) of a string in a string
	private static LispVal search(List<LispVal> parts) {
		Bound bound = bind("search", parts,
				List.of(LispNames.START2_KEYWORD, LispNames.END2_KEYWORD, LispNames.FROM_END_KEYWORD));
		return bound.around(list(new LispSymbol(STRING_SEARCH), bound.arguments().get(0), bound.arguments().get(1),
				bound.keyword(LispNames.START2_KEYWORD, new LispInteger(0)),
				bound.keyword(LispNames.END2_KEYWORD, LispNil.INSTANCE),
				bound.keyword(LispNames.FROM_END_KEYWORD, LispNil.INSTANCE)));
	}

	// (digit-char-p char [radix]): the weight of an ASCII digit or letter, as the wasm-GC
	// backend reads it (no Unicode digit there either), where it is below the radix; else
	// nil. A radix no literal in 2 to 36 is checked, the type error a trap here.
	private static LispVal digitCharP(List<LispVal> parts) {
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException("--no-gc: digit-char-p takes a character and an optional radix");
		}
		LispSymbol code = new LispSymbol("__digit-char-p-code");
		LispSymbol weight = new LispSymbol("__digit-char-p-weight");
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(code, list(new LispSymbol(LispNames.CHAR_CODE), parts.get(1))));
		LispVal radix = parts.size() == 3 ? parts.get(2) : new LispInteger(10);
		boolean literal = radix instanceof LispInteger r && r.value() >= 2 && r.value() <= 36;
		if (!literal) {
			LispSymbol named = new LispSymbol("__digit-char-p-radix");
			bindings.add(list(named, radix));
			radix = named;
		}
		LispVal weighed = list(new LispSymbol(LispNames.LET), list(list(weight,
				range(code, '0', '9', '0',
						range(code, 'A', 'Z', 'A' - 10, range(code, 'a', 'z', 'a' - 10, new LispInteger(36)))))),
				list(new LispSymbol(LispNames.IF), list(new LispSymbol(LispNames.LT), weight, radix), weight,
						LispNil.INSTANCE));
		List<LispVal> body = new ArrayList<>();
		body.add(new LispSymbol(LispNames.LET_STAR));
		body.add(list(bindings));
		if (!literal) {
			body.add(list(new LispSymbol(LispNames.IF),
					list(new LispSymbol(LispNames.IF), list(new LispSymbol(LispNames.GE), radix, new LispInteger(2)),
							list(new LispSymbol(LispNames.LE), radix, new LispInteger(36)), LispNil.INSTANCE),
					LispNil.INSTANCE, list(new LispSymbol(LispNames.ERROR), new LispString("digit-char-p: no radix"))));
		}
		body.add(weighed);
		return list(body);
	}

	// (if (<= low code high) (- code base) otherwise), the <= spelled as two tests
	private static LispVal range(LispSymbol code, char low, char high, int base, LispVal otherwise) {
		LispVal inside = list(new LispSymbol(LispNames.IF),
				list(new LispSymbol(LispNames.GE), code, new LispInteger(low)),
				list(new LispSymbol(LispNames.LE), code, new LispInteger(high)), LispNil.INSTANCE);
		return list(new LispSymbol(LispNames.IF), inside,
				list(new LispSymbol(LispNames.SUB), code, new LispInteger(base)), otherwise);
	}

	/**
	 * A sequence function's arguments, each that is no literal bound in the order it is
	 * written -- the positional ones, then each keyword's value -- so the rewrite
	 * evaluates them once and in that order, whatever order it reads them in.
	 *
	 * @param bindings the bindings, in order
	 * @param arguments the positional arguments, a binding's name for each bound one
	 * @param keywords each keyword given to its value, a binding's name for each bound
	 * one
	 */
	private record Bound(List<LispVal> bindings, List<LispVal> arguments, java.util.Map<String, LispVal> keywords) {

		LispVal keyword(String name, LispVal absent) {
			return this.keywords.getOrDefault(name, absent);
		}

		LispVal around(LispVal form) {
			return this.bindings.isEmpty() ? form : list(new LispSymbol(LispNames.LET_STAR), list(this.bindings), form);
		}

	}

	// The two positional arguments of a sequence function and its keyword arguments
	// among the known ones; any other is refused by name.
	private static Bound bind(String function, List<LispVal> parts, List<String> known) {
		if (parts.size() < 3 || (parts.size() - 3) % 2 != 0) {
			throw new UnsupportedOperationException("--no-gc: " + function + " takes two arguments and keywords");
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> arguments = new ArrayList<>();
		java.util.Map<String, LispVal> keywords = new java.util.HashMap<>();
		for (int i = 1; i < 3; i++) {
			arguments.add(bound(function, i == 1 ? "first" : "second", parts.get(i), bindings));
		}
		for (int i = 3; i < parts.size(); i += 2) {
			if (!(parts.get(i) instanceof LispSymbol key) || !known.contains(key.name())) {
				throw new UnsupportedOperationException(
						"--no-gc: " + function + "'s " + parts.get(i).print() + " is not compiled here (only "
								+ String.join(", ", known).toLowerCase(java.util.Locale.ROOT) + ")");
			}
			if (keywords.containsKey(key.name())) {
				// a keyword's first value is the one; a later one still runs
				bound(function, key.name().substring(1).toLowerCase(java.util.Locale.ROOT) + "-again", parts.get(i + 1),
						bindings);
				continue;
			}
			keywords.put(key.name(), bound(function, key.name().substring(1).toLowerCase(java.util.Locale.ROOT),
					parts.get(i + 1), bindings));
		}
		return new Bound(bindings, arguments, keywords);
	}

	// A literal as it is; any other form bound to a name of its own, which stands for it.
	private static LispVal bound(String function, String role, LispVal value, List<LispVal> bindings) {
		if (value instanceof LispInteger || value instanceof LispChar || value instanceof LispNil
				|| value instanceof LispTrue || value instanceof LispString) {
			return value;
		}
		LispSymbol name = new LispSymbol("__" + function + "-" + role);
		bindings.add(list(name, value));
		return name;
	}

	private static LispVal list(LispVal... items) {
		return list(List.of(items));
	}

	private static LispVal list(List<LispVal> items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			result = new LispCons(items.get(i), result);
		}
		return result;
	}

}
