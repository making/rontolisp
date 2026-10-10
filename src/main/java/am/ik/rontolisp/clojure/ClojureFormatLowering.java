package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;

/**
 * {@code format}: {@code java.util.Formatter} over Clojure values.
 *
 * <p>
 * The literal format string is parsed here as Formatter parses it, and every refusal of
 * that parse becomes the run-time refusal of the oracle's class and words, signalled
 * after the arguments are evaluated (the oracle's {@code format} is a function). The
 * print resolves each specifier's argument as Formatter does (ordinary, {@code n$} and
 * {@code <} indexes; a missing one refused in its place) and renders it through one
 * {@code clojure.lisp} helper per conversion family ({@code %clojure-format-text},
 * {@code -char}, {@code -integer}, {@code -float}), which take the flags, width and
 * precision and check the argument's class.
 */
final class ClojureFormatLowering {

	/**
	 * Formatter's specifier: {@code %[index$][flags][width][.precision][t]conversion}. A
	 * {@code %} it does not match is refused naming the character after it.
	 */
	private static final Pattern SPECIFIER = Pattern
		.compile("%(\\d+\\$)?([-#+ 0,(<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");

	/** The flags in Formatter's own order, the order its messages spell them in. */
	private static final String FLAGS = "-#+ 0,(<";

	/** Every conversion Formatter knows; any other letter is refused. */
	private static final String CONVERSIONS = "bBhHsScCdoxXeEfgGaAtT%n";

	private static final LispSymbol FORMAT_TEXT = new LispSymbol("RONTOLISP::%CLOJURE-FORMAT-TEXT");

	private static final LispSymbol FORMAT_CHAR = new LispSymbol("RONTOLISP::%CLOJURE-FORMAT-CHAR");

	private static final LispSymbol FORMAT_INTEGER = new LispSymbol("RONTOLISP::%CLOJURE-FORMAT-INTEGER");

	private static final LispSymbol FORMAT_FLOAT = new LispSymbol("RONTOLISP::%CLOJURE-FORMAT-FLOAT");

	private ClojureFormatLowering() {
	}

	/**
	 * One parsed specifier.
	 *
	 * @param index the argument: 0 the next ordinary one, {@code n} the {@code n$} one,
	 * -1 the previous one ({@code <}), -2 none ({@code %%}, {@code %n})
	 * @param flags the flags in Formatter's order
	 * @param width the width, or -1
	 * @param precision the precision, or -1
	 * @param conversion the conversion as written
	 */
	record Specifier(int index, String flags, int width, int precision, char conversion) {

		boolean has(char flag) {
			return this.flags.indexOf(flag) >= 0;
		}

		char kind() {
			return Character.toLowerCase(this.conversion);
		}

		/** Formatter's {@code FormatSpecifier.toString}, the words of its refusals. */
		@Override
		public String toString() {
			StringBuilder text = new StringBuilder("%").append(this.flags);
			if (this.index > 0) {
				text.append(this.index).append('$');
			}
			if (this.width != -1) {
				text.append(this.width);
			}
			if (this.precision != -1) {
				text.append('.').append(this.precision);
			}
			return text.append(this.conversion).toString();
		}

	}

	/** A refusal of the parse or of the print: the carrier and its message. */
	static final class Refused extends Exception {

		private static final long serialVersionUID = 1L;

		final String carrier;

		final String words;

		Refused(String carrier, String words) {
			super(words, null, false, false);
			this.carrier = carrier;
			this.words = words;
		}

		LispVal form() {
			return ClojureRefusals.refusal(this.carrier, LispString.literal(this.words));
		}

	}

	/**
	 * {@code (format fmt args...)}: the arguments evaluated in order, then the text.
	 */
	static LispVal formatOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "format takes a format string and arguments");
		if (!(items.get(1) instanceof LispString fmt)) {
			throw new LispReadException("format takes a literal format string, not " + items.get(1).print());
		}
		List<LispVal> args = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			args.add(ctx.lower(items.get(i)));
		}
		List<Object> parts;
		Refused stop = null;
		try {
			parts = parse(fmt.value());
		}
		catch (Refused refused) {
			parts = List.of();
			stop = refused;
		}
		// the print: each specifier's argument resolved as Formatter resolves it
		List<Object> pieces = new ArrayList<>();
		int[] uses = new int[args.size()];
		int last = -1;
		int ordinary = -1;
		for (Object part : parts) {
			if (part instanceof String text) {
				pieces.add(text);
				continue;
			}
			Specifier spec = (Specifier) part;
			if (spec.kind() == '%') {
				pieces.add(justified("%", spec));
				continue;
			}
			if (spec.kind() == 'n') {
				pieces.add("\n");
				continue;
			}
			int at;
			if (spec.index() == -1) {
				at = last;
			}
			else if (spec.index() == 0) {
				ordinary++;
				at = ordinary;
				last = at;
			}
			else {
				at = spec.index() - 1;
				last = at;
			}
			if (at < 0 || at >= args.size()) {
				stop = new Refused(ClojureRefusals.MISSING_FORMAT_ARGUMENT, "Format specifier '" + spec + "'");
				break;
			}
			if (spec.kind() == 's' && spec.has('#')) {
				// no Clojure value is a java.util.Formattable
				stop = new Refused(ClojureRefusals.FORMAT_FLAGS_CONVERSION_MISMATCH, "Conversion = s, Flags = #");
				break;
			}
			uses[at]++;
			pieces.add(new Use(spec, at));
		}
		// the arguments evaluate before any is converted, like the oracle's call
		boolean computed = false;
		for (LispVal arg : args) {
			computed |= arg instanceof LispCons && !ClojureLowering.isQuoteForm(arg);
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> values = new ArrayList<>(args);
		if (computed && (args.size() > 1 || uses[0] != 1 || stop != null)) {
			for (int i = 0; i < args.size(); i++) {
				if (args.get(i) instanceof LispCons && !ClojureLowering.isQuoteForm(args.get(i))) {
					LispSymbol temp = ctx.freshTemp();
					bindings.add(ClojureLowerUtil.list(temp, args.get(i)));
					values.set(i, temp);
				}
			}
		}
		StringBuilder control = new StringBuilder();
		StringBuilder plain = new StringBuilder();
		List<LispVal> forms = new ArrayList<>();
		for (Object piece : pieces) {
			if (piece instanceof String text) {
				control.append(text.replace("~", "~~"));
				plain.append(text);
			}
			else {
				Use use = (Use) piece;
				control.append("~A");
				forms.add(conversion(ctx, use.spec(), values.get(use.at())));
			}
		}
		LispVal body;
		if (stop != null) {
			List<LispVal> checks = new ArrayList<>(forms);
			checks.add(stop.form());
			body = ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), checks);
		}
		else if (forms.isEmpty()) {
			body = LispString.literal(plain.toString());
		}
		else {
			List<LispVal> call = new ArrayList<>();
			call.add(ClojureLowerUtil.sym("format"));
			call.add(ClojureLowering.NIL_CONST);
			call.add(LispString.literal(control.toString()));
			call.addAll(forms);
			body = ClojureLowerUtil.list(call);
		}
		if (bindings.isEmpty()) {
			return body;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), body);
	}

	/** A specifier converting the argument at the index. */
	private record Use(Specifier spec, int at) {
	}

	/** The conversion of one argument: its helper call. */
	private static LispVal conversion(ClojureLowering ctx, Specifier spec, LispVal arg) {
		LispVal conv = new LispChar(spec.conversion());
		LispVal flags = LispString.literal(spec.flags().replace("<", ""));
		LispVal width = spec.width() == -1 ? ClojureLowering.NIL_CONST : new LispInteger(spec.width());
		LispVal precision = spec.precision() == -1 ? ClojureLowering.NIL_CONST : new LispInteger(spec.precision());
		return switch (spec.kind()) {
			case 's' ->
				text(spec, ClojureStringLowering.strOf(ctx, arg, LispString.literal("null"), ClojureLowering.NIL_CONST),
						conv, flags, width, precision);
			case 'b' -> {
				LispSymbol test = ctx.freshTemp();
				LispVal spelled = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(test, arg))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), test),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), test, ctx.falseVariable)),
								LispString.literal("false"), LispString.literal("true")));
				yield text(spec, spelled, conv, flags, width, precision);
			}
			case 'c' -> ClojureLowerUtil.list(FORMAT_CHAR, arg, conv, flags, width);
			case 'd', 'o', 'x' -> ClojureLowerUtil.list(FORMAT_INTEGER, arg, conv, flags, width);
			default -> ClojureLowerUtil.list(FORMAT_FLOAT, arg, conv, flags, width, precision);
		};
	}

	/** A general conversion's text: itself, or cut, upcased and justified. */
	private static LispVal text(Specifier spec, LispVal text, LispVal conv, LispVal flags, LispVal width,
			LispVal precision) {
		if (spec.width() == -1 && spec.precision() == -1 && !Character.isUpperCase(spec.conversion())) {
			return text;
		}
		return ClojureLowerUtil.list(FORMAT_TEXT, text, conv, flags, width, precision);
	}

	/** Constant text justified to the specifier's width ({@code %%}). */
	private static String justified(String text, Specifier spec) {
		if (spec.width() <= text.length()) {
			return text;
		}
		String pad = " ".repeat(spec.width() - text.length());
		return spec.has('-') ? text + pad : pad + text;
	}

	/**
	 * Formatter's parse of a format string: its literal runs (strings) and specifiers in
	 * order.
	 * @throws Refused the first refusal of the parse, in Formatter's class and words
	 */
	static List<Object> parse(String pattern) throws Refused {
		List<Object> parts = new ArrayList<>();
		int i = 0;
		int n = pattern.length();
		while (i < n) {
			int pct = pattern.indexOf('%', i);
			if (pct < 0) {
				parts.add(pattern.substring(i));
				break;
			}
			if (pct > i) {
				parts.add(pattern.substring(i, pct));
			}
			if (pct + 1 == n) {
				throw new Refused(ClojureRefusals.UNKNOWN_FORMAT_CONVERSION, "Conversion = '%'");
			}
			Matcher m = SPECIFIER.matcher(pattern).region(pct, n);
			if (!m.lookingAt()) {
				throw new Refused(ClojureRefusals.UNKNOWN_FORMAT_CONVERSION,
						"Conversion = '" + pattern.charAt(pct + 1) + "'");
			}
			parts.add(specifier(m));
			i = m.end();
		}
		return parts;
	}

	private static Specifier specifier(Matcher m) throws Refused {
		int index = 0;
		if (m.group(1) != null) {
			index = number(m.group(1).substring(0, m.group(1).length() - 1), m);
			if (index == 0) {
				throw new Refused(ClojureRefusals.ILLEGAL_FORMAT_ARGUMENT_INDEX, "Illegal format argument index = 0");
			}
		}
		String written = (m.group(2) != null) ? m.group(2) : "";
		for (int i = 0; i < written.length(); i++) {
			if (written.indexOf(written.charAt(i)) < i) {
				throw new Refused(ClojureRefusals.DUPLICATE_FORMAT_FLAGS, "Flags = '" + written.charAt(i) + "'");
			}
		}
		StringBuilder flags = new StringBuilder();
		for (char flag : FLAGS.toCharArray()) {
			if (written.indexOf(flag) >= 0) {
				flags.append(flag);
			}
		}
		if (flags.indexOf("<") >= 0) {
			index = -1;
		}
		int width = (m.group(3) != null) ? number(m.group(3), m) : -1;
		int precision = (m.group(4) != null) ? number(m.group(4).substring(1), m) : -1;
		if (m.group(5) != null) {
			throw new LispReadException("format directive %" + m.group(5) + " is not supported yet");
		}
		char conversion = m.group(6).charAt(0);
		if (CONVERSIONS.indexOf(conversion) < 0) {
			throw new Refused(ClojureRefusals.UNKNOWN_FORMAT_CONVERSION, "Conversion = '" + conversion + "'");
		}
		char kind = Character.toLowerCase(conversion);
		if (kind == '%' || kind == 'n') {
			index = -2;
		}
		Specifier spec = new Specifier(index, flags.toString(), width, precision, conversion);
		switch (kind) {
			case 'b', 'h', 's' -> checkGeneral(spec);
			case 'c' -> checkCharacter(spec);
			case 'd', 'o', 'x' -> checkInteger(spec);
			case 'e', 'f', 'g', 'a' -> checkFloat(spec);
			default -> checkText(spec);
		}
		if (kind == 'h' || kind == 'a') {
			throw new LispReadException("format directive %" + conversion + " is not supported yet");
		}
		return spec;
	}

	private static int number(String digits, Matcher m) {
		try {
			return Integer.parseInt(digits);
		}
		catch (NumberFormatException ex) {
			throw new LispReadException("format specifier " + m.group() + " is out of range");
		}
	}

	private static void checkGeneral(Specifier spec) throws Refused {
		if ((spec.kind() == 'b' || spec.kind() == 'h') && spec.has('#')) {
			throw mismatch(spec, "#");
		}
		requireWidthFor(spec, "-");
		checkBadFlags(spec, "+ 0,(");
	}

	private static void checkCharacter(Specifier spec) throws Refused {
		if (spec.precision() != -1) {
			throw new Refused(ClojureRefusals.ILLEGAL_FORMAT_PRECISION, Integer.toString(spec.precision()));
		}
		checkBadFlags(spec, "#+ 0,(");
		requireWidthFor(spec, "-");
	}

	private static void checkInteger(Specifier spec) throws Refused {
		checkNumeric(spec);
		if (spec.precision() != -1) {
			throw new Refused(ClojureRefusals.ILLEGAL_FORMAT_PRECISION, Integer.toString(spec.precision()));
		}
		checkBadFlags(spec, spec.kind() == 'd' ? "#" : ",");
	}

	private static void checkFloat(Specifier spec) throws Refused {
		checkNumeric(spec);
		switch (spec.kind()) {
			case 'e' -> checkBadFlags(spec, ",");
			case 'g' -> checkBadFlags(spec, "#");
			case 'a' -> checkBadFlags(spec, ",(");
			default -> {
			}
		}
	}

	private static void checkNumeric(Specifier spec) throws Refused {
		requireWidthFor(spec, "-0");
		if ((spec.has('+') && spec.has(' ')) || (spec.has('-') && spec.has('0'))) {
			throw illegalFlags(spec);
		}
	}

	private static void checkText(Specifier spec) throws Refused {
		if (spec.precision() != -1) {
			throw new Refused(ClojureRefusals.ILLEGAL_FORMAT_PRECISION, Integer.toString(spec.precision()));
		}
		if (spec.kind() == '%') {
			if (!spec.flags().isEmpty() && !spec.flags().equals("-")) {
				throw illegalFlags(spec);
			}
			requireWidthFor(spec, "-");
		}
		else {
			if (spec.width() != -1) {
				throw new Refused(ClojureRefusals.ILLEGAL_FORMAT_WIDTH, Integer.toString(spec.width()));
			}
			if (!spec.flags().isEmpty()) {
				throw illegalFlags(spec);
			}
		}
	}

	/**
	 * {@code MissingFormatWidthException} when one of the flags is given without a width.
	 */
	private static void requireWidthFor(Specifier spec, String flags) throws Refused {
		if (spec.width() == -1) {
			for (char flag : flags.toCharArray()) {
				if (spec.has(flag)) {
					throw new Refused(ClojureRefusals.MISSING_FORMAT_WIDTH, spec.toString());
				}
			}
		}
	}

	/**
	 * The conversion's refusal of the given flags it does not take, all of them named.
	 */
	private static void checkBadFlags(Specifier spec, String bad) throws Refused {
		StringBuilder given = new StringBuilder();
		for (char flag : spec.flags().toCharArray()) {
			if (bad.indexOf(flag) >= 0) {
				given.append(flag);
			}
		}
		if (!given.isEmpty()) {
			throw mismatch(spec, given.toString());
		}
	}

	private static Refused mismatch(Specifier spec, String flags) {
		return new Refused(ClojureRefusals.FORMAT_FLAGS_CONVERSION_MISMATCH,
				"Conversion = " + spec.kind() + ", Flags = " + flags);
	}

	private static Refused illegalFlags(Specifier spec) {
		return new Refused(ClojureRefusals.ILLEGAL_FORMAT_FLAGS, "Flags = '" + spec.flags() + "'");
	}

}
