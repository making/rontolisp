package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * {@code format} under {@code --no-gc}: a literal control string of {@code ~a},
 * {@code ~d}, {@code ~%} and {@code ~~}, spelled out as the {@code concatenate} and
 * {@code princ-to-string} (or, to {@code t}, the {@code princ}) this backend has. The
 * text is the one {@code .kb/format.md} promises, because {@code ~a} and {@code ~d} of
 * what this backend can hold are both {@code princ}. It runs before the compiler's scans
 * of the bodies, which read the forms as written and need to see what is really there.
 * Anything else is refused by name: the shared lowering's temporaries and type tests have
 * no place here.
 */
final class NoGcFormatLowering {

	private NoGcFormatLowering() {
	}

	/**
	 * Lowers every {@code format} in a form.
	 * @param form the form
	 * @return the form with no {@code format} left in it, or the form itself if it held
	 * none
	 */
	static LispVal lower(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.FORMAT.equals(head.name())) {
			return lowerCall(cons);
		}
		LispVal car = lower(cons.car());
		LispVal cdr = lower(cons.cdr());
		return car == cons.car() && cdr == cons.cdr() ? cons : new LispCons(car, cdr);
	}

	private static LispVal lowerCall(LispCons cons) {
		List<LispVal> call = cons.toList();
		if (call.size() < 3 || !(call.get(2) instanceof LispString control)) {
			throw new UnsupportedOperationException(
					"--no-gc: format takes a destination and a literal control string: " + cons.print());
		}
		boolean toStdout = call.get(1) instanceof LispTrue;
		if (!toStdout && !(call.get(1) instanceof LispNil)) {
			throw new UnsupportedOperationException("--no-gc: format writes to t or nil only: " + cons.print());
		}
		List<LispVal> arguments = new ArrayList<>();
		for (LispVal argument : call.subList(3, call.size())) {
			arguments.add(lower(argument));
		}
		List<LispVal> pieces = pieces(control.value(), arguments, cons, toStdout);
		return toStdout ? printed(pieces) : concatenated(pieces);
	}

	private static List<LispVal> pieces(String control, List<LispVal> arguments, LispCons cons, boolean toStdout) {
		List<LispVal> pieces = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		int next = 0;
		for (int i = 0; i < control.length(); i++) {
			char c = control.charAt(i);
			if (c != '~') {
				text.append(c);
				continue;
			}
			char directive = ++i < control.length() ? Character.toLowerCase(control.charAt(i)) : '\0';
			switch (directive) {
				case '%' -> text.append('\n');
				case '~' -> text.append('~');
				case 'a', 'd' -> {
					if (next == arguments.size()) {
						throw new UnsupportedOperationException(
								"--no-gc: format has too few arguments: " + cons.print());
					}
					if (!text.isEmpty()) {
						pieces.add(new LispString(text.toString()));
						text.setLength(0);
					}
					LispVal argument = arguments.get(next++);
					pieces.add(toStdout ? argument : call(LispNames.PRINC_TO_STRING, List.of(argument)));
				}
				default -> throw new UnsupportedOperationException(
						"--no-gc: format takes only ~a, ~d, ~% and ~~: " + cons.print());
			}
		}
		if (next != arguments.size()) {
			throw new UnsupportedOperationException("--no-gc: format has too many arguments: " + cons.print());
		}
		if (!text.isEmpty()) {
			pieces.add(new LispString(text.toString()));
		}
		return pieces;
	}

	private static LispVal concatenated(List<LispVal> pieces) {
		if (pieces.isEmpty()) {
			return new LispString("");
		}
		if (pieces.size() == 1) {
			return pieces.get(0);
		}
		List<LispVal> arguments = new ArrayList<>();
		arguments.add(call(LispNames.QUOTE, List.of(new LispSymbol(LispNames.STRING))));
		arguments.addAll(pieces);
		return call(LispNames.CONCATENATE, arguments);
	}

	private static LispVal printed(List<LispVal> pieces) {
		List<LispVal> forms = new ArrayList<>();
		for (LispVal piece : pieces) {
			forms.add(call(LispNames.PRINC, List.of(piece)));
		}
		forms.add(LispNil.INSTANCE);
		return call(LispNames.PROGN, forms);
	}

	private static LispVal call(String operator, List<LispVal> arguments) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = arguments.size() - 1; i >= 0; i--) {
			tail = new LispCons(arguments.get(i), tail);
		}
		return new LispCons(new LispSymbol(operator), tail);
	}

}
