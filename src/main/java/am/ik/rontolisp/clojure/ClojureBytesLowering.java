package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Byte arrays in the lowering: the arms a verb reading a byte array carries, the verbs of
 * the array itself ({@code aget}, {@code aset}, {@code alength}, {@code bytes?}), the
 * {@code String} construction over one and {@code .getBytes}. The value and its runtime
 * are {@code clojure.lisp}'s ("Byte arrays"); every verb reaches one through the
 * byte-array family's arm test ({@link ClojureArms.Family#BYTES}), and a verb whose
 * lowered form predates byte arrays stands for that form through the family's alias or
 * setter, so a program making no byte array compiles as before.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureBytesLowering {

	private static final String PREFIX = "RONTOLISP::%CLOJURE-";

	/** The family's arm test: whether a value is a byte array. */
	static final String BYTES_P = PREFIX + "BYTES-P";

	/**
	 * The family's arm test of the byte stream functions ({@code clojure.lisp}, "Byte
	 * streams"): whether a byte stream is a {@code ByteArrayInputStream} or a
	 * {@code ByteArrayOutputStream}, which only a program making a byte array can
	 * construct (the first over one, the second to answer one).
	 */
	static final String ARRAY_STREAM_P = PREFIX + "IO-ARRAY-STREAM-P";

	/**
	 * The family's arm test of the output functions: whether a byte stream is the
	 * {@code BufferedOutputStream} {@code clojure.java.io/output-stream} answers over a
	 * {@code ByteArrayOutputStream}.
	 */
	static final String BUFFERED_OUTPUT_P = PREFIX + "IO-BUFFERED-OUTPUT-P";

	/** {@code (aget array i)} of one index: the alias of {@code aref}. */
	static final String AGET = PREFIX + "AGET";

	/** {@code (aset array i value)} of one index: the setter of {@code aref}. */
	static final String ASET = PREFIX + "ASET";

	/** {@code (alength array)}: the alias of {@code array-dimension}. */
	static final String ALENGTH = PREFIX + "ALENGTH";

	/**
	 * {@code bytes?}'s call {@code (is-bytes value false)}: the alias of {@code progn}.
	 */
	static final String IS_BYTES = PREFIX + "IS-BYTES";

	/**
	 * A {@code java.lang.String} construction: the alias of {@code java:new}, whose
	 * operands it keeps.
	 */
	static final String STRING_NEW = PREFIX + "STRING-NEW";

	/** Whether a value is a char array: a vector of characters, never a string. */
	static final String CHAR_VECTOR_P = PREFIX + "CHAR-VECTOR-P";

	/** {@code (String. x ...)} of a char array: the string of its characters. */
	static final String STRING_OF_CHARS = PREFIX + "STRING-OF-CHARS";

	/**
	 * An argument of a {@code java.lang.String} construction: a byte array as it is,
	 * which the construction decodes, anything else as a {@code java:} member takes it.
	 * The alias of {@link ClojureInteropLowering#HOST_VALUE}.
	 */
	static final String HOST_VALUE = PREFIX + "BYTES-HOST-VALUE";

	/** {@code .getBytes} of a string: its byte array in a charset. */
	static final String STRING_BYTES = PREFIX + "STRING-BYTES";

	/** {@code (byte-array size-or-seq)}. */
	static final String BYTE_ARRAY = PREFIX + "BYTE-ARRAY";

	/**
	 * The class of a byte array, {@code byte[]}'s binary name, which {@code class} keys.
	 */
	static final String CLASS_NAME = "[B";

	/**
	 * The kind-aware helpers, each to the form its verb lowered to before byte arrays
	 * existed.
	 */
	static final Map<String, String> ALIASES = Map.of(AGET, LispNames.AREF, ALENGTH, "ARRAY-DIMENSION", IS_BYTES,
			"PROGN", STRING_NEW, ClojureInteropLowering.JAVA_NEW.name(), HOST_VALUE, ClojureInteropLowering.HOST_VALUE);

	/** The kind-aware setters, each to the place it stored into before. */
	static final Map<String, String> SETTERS = Map.of(ASET, LispNames.AREF);

	/**
	 * What makes a byte array: {@code byte-array} (and {@code make-array} of
	 * {@code Byte/TYPE}) as a call and a value, {@code .getBytes}, the byte streams'
	 * {@code readAllBytes}, {@code readNBytes} and {@code toByteArray},
	 * {@code ring.util.codec/base64-decode}'s kernel and the HTTP client's request, whose
	 * {@code :as :bytes} answers one -- and the constructions of a
	 * {@code ByteArrayInputStream} and a {@code ByteArrayOutputStream}, the byte streams
	 * whose arms the family's {@link #ARRAY_STREAM_P} guards; and a host boundary's
	 * {@code :bytes} or WIT {@code list<u8>} coming back, by its conversion or the
	 * descriptor the WIT walker reads.
	 */
	static final Set<String> PRODUCERS = Set.of(BYTE_ARRAY, BYTE_ARRAY + "-2", BYTE_ARRAY + "-V", STRING_BYTES,
			PREFIX + "IO-M-READ-ALL-BYTES", PREFIX + "IO-M-READ-N-BYTES", PREFIX + "IO-M-TO-BYTE-ARRAY",
			PREFIX + "RING-BASE64-DECODE", ClojureKernelLowering.HTTP_REQUEST, PREFIX + "IO-BYTES-INPUT",
			PREFIX + "IO-BYTES-INPUT-3", PREFIX + "IO-BYTES-OUTPUT", ClojureWasmLowering.BYTES_FROM_HOST,
			ClojureWitLowering.BYTE_ARRAY.name());

	private ClojureBytesLowering() {
	}

	/**
	 * {@code (aget array i ...)}: of one index the family's alias of {@code aref} (a byte
	 * array's element read signed), of several the {@code aref} of a multi-dimensional
	 * array.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal agetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "aget takes an array and subscripts");
		List<LispVal> ref = new ArrayList<>();
		ref.add(items.size() == 3 ? new LispSymbol(AGET) : ClojureLowerUtil.sym("aref"));
		ref.addAll(ctx.lowers(items, 1));
		return ClojureLowerUtil.list(ref);
	}

	/**
	 * {@code (aset array i ... value)}: of one index the family's setter of {@code aref}
	 * (a byte array takes a value in the byte range), of several the store into the
	 * {@code aref} of a multi-dimensional array.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal asetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 4, "aset takes an array, subscripts and a value");
		if (items.size() == 4) {
			return ClojureLowerUtil.list(new LispSymbol(ASET), ctx.lower(items.get(1)), ctx.lower(items.get(2)),
					ctx.lower(items.get(3)));
		}
		List<LispVal> ref = new ArrayList<>();
		ref.add(ClojureLowerUtil.sym("aref"));
		for (int i = 1; i < items.size() - 1; i++) {
			ref.add(ctx.lower(items.get(i)));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"), ClojureLowerUtil.list(ref),
				ctx.lower(items.get(items.size() - 1)));
	}

	/**
	 * {@code (alength array)}: the family's alias of {@code (array-dimension array 0)}.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal alengthOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "alength takes an array");
		return ClojureLowerUtil.list(new LispSymbol(ALENGTH), ctx.lower(items.get(1)), new LispInteger(0));
	}

	/**
	 * {@code aget}/{@code alength}/{@code aset} as a value, or null for any other name:
	 * over the same arguments as the call, so a higher-order use runs what a call runs.
	 * Of several subscripts the flat index walks {@code row-major-aref}, the call's
	 * nested {@code aref} over a dynamic count; any other count signals, like a call's
	 * arity refusal.
	 * @param ctx the hub
	 * @param name {@code aget}, {@code alength} or {@code aset}
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(ClojureLowering ctx, String name) {
		return switch (name) {
			case "alength" -> {
				LispSymbol array = new LispSymbol(ClojureLowering.mangle("alength-array"));
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(array),
						ClojureLowerUtil.list(new LispSymbol(ALENGTH), array, new LispInteger(0)));
			}
			case "aget" -> agetValue();
			case "aset" -> asetValue();
			default -> null;
		};
	}

	/**
	 * The flat row-major index of the subscript list over the dimension list: the call's
	 * nested {@code aref} over a dynamic subscript count.
	 * @param subs the subscript list form
	 * @param dims the dimension list form
	 * @return the index form
	 */
	private static LispVal flatIndex(LispVal subs, LispVal dims) {
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("row-major-rest"));
		LispSymbol left = new LispSymbol(ClojureLowering.mangle("row-major-dims"));
		LispSymbol flat = new LispSymbol(ClojureLowering.mangle("row-major-flat"));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("+"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("*"), flat,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest));
		List<LispVal> bindings = List.of(
				ClojureLowerUtil.list(rest, subs, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest)),
				ClojureLowerUtil.list(left, dims, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left)),
				ClojureLowerUtil.list(flat, new LispInteger(0), step));
		LispVal end = ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), flat);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("do"), ClojureLowerUtil.list(bindings), end);
	}

	/**
	 * {@code aget} as a value: over an array and subscripts -- of one the family's read,
	 * of several the flat row-major read, like the call.
	 * @return the value form
	 */
	private static LispVal agetValue() {
		LispSymbol array = new LispSymbol(ClojureLowering.mangle("aget-array"));
		LispSymbol subs = new LispSymbol(ClojureLowering.mangle("aget-subs"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("aget takes an array and subscripts"));
		LispVal one = ClojureLowerUtil.list(new LispSymbol(AGET), array,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), subs));
		LispVal many = ClojureLowerUtil.list(ClojureLowerUtil.sym("row-major-aref"), array,
				flatIndex(subs, ClojureLowerUtil.list(ClojureLowerUtil.sym("array-dimensions"), array)));
		LispVal read = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("null"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), subs)), one, many);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(array, ClojureLowering.AMPERSAND_REST, subs)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), subs), arity, read));
	}

	/**
	 * {@code aset} as a value: over an array, subscripts and a value -- of one subscript
	 * the family's store, of several the flat row-major store, like the call. Anything
	 * shorter signals, like a call's arity refusal.
	 * @return the value form
	 */
	private static LispVal asetValue() {
		LispSymbol array = new LispSymbol(ClojureLowering.mangle("aset-array"));
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("aset-args"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("aset takes an array, subscripts and a value"));
		LispVal one = ClojureLowerUtil.list(new LispSymbol(ASET), array,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)));
		LispSymbol value = new LispSymbol(ClojureLowering.mangle("aset-value"));
		LispSymbol subs = new LispSymbol(ClojureLowering.mangle("aset-subs"));
		LispVal many = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(value,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("last"), args))),
						ClojureLowerUtil.list(subs, ClojureLowerUtil.list(ClojureLowerUtil.sym("butlast"), args)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("row-major-aref"), array,
								flatIndex(subs,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("array-dimensions"), array))),
						value));
		LispVal store = ClojureLowerUtil.list(
				ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("cdr"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
				one, many);
		return ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"),
					ClojureLowerUtil.list(List.of(array, ClojureLowering.AMPERSAND_REST, args)),
					ClojureLowerUtil
						.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
												ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
								arity, store));
	}

	/**
	 * Whether a {@code make-array} class spelling names the primitive {@code byte}:
	 * {@code Byte/TYPE}, the class object of {@code byte}, through any spelling of
	 * {@code java.lang.Byte}.
	 * @param ctx the hub
	 * @param spelling the class argument as written
	 * @return whether the array is a byte array
	 */
	static boolean namesByteType(ClojureLowering ctx, String spelling) {
		if (!spelling.endsWith("/TYPE")) {
			return false;
		}
		String cls = spelling.substring(0, spelling.length() - "/TYPE".length());
		return ClojureInteropLowering.isClassSpelling(cls)
				&& ClojureNamespaceLowering.resolveClass(ctx, cls).equals("java.lang.Byte");
	}

	/**
	 * {@code (make-array Byte/TYPE size)}: a byte array of the size, the oracle's
	 * {@code byte[]}.
	 * @param size the lowered size
	 * @return the call
	 */
	static LispVal byteArrayOfSize(LispVal size) {
		return ClojureLowerUtil.list(new LispSymbol(BYTE_ARRAY), size);
	}

	/**
	 * {@code bytes?} over a lowered value: {@code (is-bytes value false)}, the
	 * {@code (progn value false)} it was before byte arrays where the program makes none.
	 * @param ctx the hub
	 * @param value the lowered value
	 * @return the call
	 */
	static LispVal bytesPredicate(ClojureLowering ctx, LispVal value) {
		return ClojureLowerUtil.list(new LispSymbol(IS_BYTES), value, ctx.falseVariable);
	}

	/**
	 * A {@code java.lang.String} construction of one to four arguments over its
	 * {@code java:new} call: the same operands under the family's alias, each argument
	 * the host value of a {@code java:} member under the family's alias of it, so a byte
	 * array first argument reaches the construction as it is and decodes on every
	 * backend, and anything else reaches the host's constructor as it would. Null for
	 * another class or count, or a call the lowering built otherwise.
	 * @param cls the resolved class name
	 * @param argCount the argument count
	 * @param hostCall the {@code java:new} call
	 * @return the construction, or null
	 */
	static @Nullable LispVal stringConstruction(ClojureLowering ctx, String cls, int argCount, LispVal hostCall) {
		if (!cls.equals("java.lang.String") || argCount < 1 || argCount > 4 || !(hostCall instanceof LispCons call)
				|| !ClojureLowerUtil.isSymbolNamed(call.car(), ClojureInteropLowering.JAVA_NEW.name())) {
			return null;
		}
		List<LispVal> operands = new ArrayList<>();
		for (LispVal operand = call.cdr(); operand instanceof LispCons cell; operand = cell.cdr()) {
			LispVal each = unviewed(cell.car());
			operands.add(each instanceof LispCons value
					&& ClojureLowerUtil.isSymbolNamed(value.car(), ClojureInteropLowering.HOST_VALUE)
							? LispCons.rebuilt(value, new LispSymbol(HOST_VALUE), value.cdr()) : each);
		}
		LispVal string = LispCons.rebuilt(call, new LispSymbol(STRING_NEW), ClojureLowerUtil.list(operands));
		if (argCount != 1 && argCount != 3 || operands.get(1) instanceof LispString) {
			return string;
		}
		// (String. x [off len]) of a char array reads its characters on every
		// backend (a char[] is a vector of characters here); anything else keeps
		// the host's constructor, which a program making no byte array calls
		// directly (the family's alias). The call's trailing markers (:function,
		// :java-false) stay out of the characters' range.
		LispSymbol one = ctx.freshTemp();
		List<LispVal> chars = new ArrayList<>();
		chars.add(new LispSymbol(STRING_OF_CHARS));
		chars.add(one);
		chars.addAll(operands.subList(2, 1 + argCount));
		List<LispVal> rebuilt = new ArrayList<>();
		rebuilt.add(operands.get(0));
		rebuilt.add(one);
		rebuilt.addAll(operands.subList(2, operands.size()));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, operands.get(1)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(new LispSymbol(CHAR_VECTOR_P), one), ClojureLowerUtil.list(chars),
						LispCons.rebuilt(call, new LispSymbol(STRING_NEW), ClojureLowerUtil.list(rebuilt))));
	}

	/**
	 * An operand of a String construction without the io family's view of it: the
	 * construction reads a charset value as it is (and views it itself where it hands the
	 * host constructor the argument), so the view is no host object's to cross as -- and
	 * no {@code java:} call for a program that makes no other.
	 */
	private static LispVal unviewed(LispVal operand) {
		if (!(operand instanceof LispCons cell)) {
			return operand;
		}
		if (ClojureLowerUtil.isSymbolNamed(cell.car(), ClojureIoLowering.HOST_VIEW)
				&& cell.cdr() instanceof LispCons inner) {
			return inner.car();
		}
		if (ClojureLowerUtil.isSymbolNamed(cell.car(), ClojureInteropLowering.HOST_VALUE)
				&& cell.cdr() instanceof LispCons inner && inner.car() instanceof LispCons view
				&& ClojureLowerUtil.isSymbolNamed(view.car(), ClojureIoLowering.HOST_VIEW)
				&& view.cdr() instanceof LispCons argument) {
			return LispCons.rebuilt(cell, cell.car(), ClojureLowerUtil.list(argument.car()));
		}
		return operand;
	}

	/**
	 * {@code .getBytes} over an already-bound string receiver: its byte array in UTF-8,
	 * or in the charset an argument names. Null for any other count.
	 * @param recv the bound receiver
	 * @param args the lowered arguments
	 * @return the call, or null
	 */
	static @Nullable LispVal getBytes(LispVal recv, List<LispVal> args) {
		return switch (args.size()) {
			case 0 -> ClojureLowerUtil.list(new LispSymbol(STRING_BYTES), recv, ClojureLowering.NIL_CONST);
			case 1 -> ClojureLowerUtil.list(new LispSymbol(STRING_BYTES), recv, args.get(0));
			default -> null;
		};
	}

}
