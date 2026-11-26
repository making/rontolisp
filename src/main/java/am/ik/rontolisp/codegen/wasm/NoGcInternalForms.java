package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
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
	 * Quoted data and a function's name are left alone.
	 * @param form a form of a body
	 * @return the rewritten form
	 */
	static LispVal normalize(LispVal form) {
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
						rewritten.add(normalize(part));
					}
					return SourceProvenance.inherit(cons, list(rewritten));
				}
				case LispNames.DECIMAL_PIECE_INTERNAL, LispNames.PRINC_PIECE_INTERNAL -> {
					if (parts.size() == 2) {
						return SourceProvenance.inherit(cons,
								list(new LispSymbol(LispNames.PRINC_TO_STRING), normalize(parts.get(1))));
					}
				}
				case LispNames.SUBSTRING_INTERNAL -> {
					List<LispVal> rewritten = new ArrayList<>();
					rewritten.add(new LispSymbol(LispNames.SUBSEQ));
					for (LispVal part : parts.subList(1, parts.size())) {
						rewritten.add(normalize(part));
					}
					return SourceProvenance.inherit(cons, list(rewritten));
				}
				case LispNames.NORMALIZE_STRING -> {
					if (parts.size() == 2) {
						return normalize(parts.get(1));
					}
				}
				case LispNames.STRING_CODE -> {
					if (parts.size() == 3) {
						return normalize(SourceProvenance.inherit(cons, LispMacroExpander.expandStringCode(cons)));
					}
				}
				case LispNames.WRITE_LINE, LispNames.WRITE_STRING -> {
					// to the standard output, the one stream here: (write-line s) is
					// (prog1 (princ s) (terpri)), (write-string s) is (princ s)
					if (parts.size() == 2) {
						LispVal written = list(new LispSymbol(LispNames.PRINC), normalize(parts.get(1)));
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
							rewritten.add(normalize(part));
						}
						return SourceProvenance.inherit(cons, list(rewritten));
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
			LispVal normalized = normalize(part);
			changed |= normalized != part;
			rewritten.add(normalized);
		}
		return changed ? SourceProvenance.inherit(cons, list(rewritten)) : form;
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
