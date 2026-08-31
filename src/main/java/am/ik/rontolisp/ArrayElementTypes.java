package am.ik.rontolisp;

import org.jspecify.annotations.Nullable;

/**
 * The UPGRADED array element types, as a small closed code space shared by every backend.
 *
 * <p>
 * {@code make-array} selects a specialized representation for only a handful of declared
 * element types, and every other request lands in the general boxed array. The code here
 * is what a general array REMEMBERS about the type it was asked to hold: it is the answer
 * {@code array-element-type} gives back and the fill an unsupplied element takes, and it
 * is stored per array on all four backends (the interpreter in a {@link LispArray} field,
 * the JVM in an extra header slot, wasm in the meta marker word).
 *
 * <p>
 * The space is closed because the upgrade is: {@code character} (and its base/standard
 * spellings) upgrades to {@code character}, {@code (unsigned-byte 8|16|32)} and the two
 * float widths keep their own name, and EVERYTHING else -- {@code fixnum},
 * {@code integer}, {@code bit}, a class, an unsupported {@code (unsigned-byte 4)} --
 * upgrades to {@code t}, which is {@link #T} and is remembered as nothing at all.
 */
public final class ArrayElementTypes {

	/** The general element type: {@code t}. Nothing is remembered for it. */
	public static final int T = 0;

	/** {@code character}. */
	public static final int CHARACTER = 1;

	/** {@code (unsigned-byte 8)}. */
	public static final int UNSIGNED_BYTE_8 = 2;

	/** {@code (unsigned-byte 16)}. */
	public static final int UNSIGNED_BYTE_16 = 3;

	/** {@code (unsigned-byte 32)}. */
	public static final int UNSIGNED_BYTE_32 = 4;

	/** {@code single-float}. */
	public static final int SINGLE_FLOAT = 5;

	/** {@code double-float}. */
	public static final int DOUBLE_FLOAT = 6;

	private ArrayElementTypes() {
	}

	/**
	 * The code a {@code make-array :element-type} argument upgrades to. The argument is
	 * the value as written at the call site, so a literal {@code 'character} /
	 * {@code '(unsigned-byte 8)} quote wrapper is unwrapped and a package qualifier
	 * ignored; a {@code deftype} alias must already have been resolved by the caller.
	 * @param elementType the {@code :element-type} argument, or null when absent
	 * @return one of the codes in this class ({@link #T} when nothing is specialized)
	 */
	public static int codeOf(@Nullable LispVal elementType) {
		LispVal spec = unquote(elementType);
		if (spec instanceof LispSymbol sym) {
			return switch (localName(sym.name())) {
				case "CHARACTER", "BASE-CHAR", "STANDARD-CHAR" -> CHARACTER;
				case LispNames.SINGLE_FLOAT -> SINGLE_FLOAT;
				case LispNames.DOUBLE_FLOAT -> DOUBLE_FLOAT;
				default -> T;
			};
		}
		return switch (LispNames.unsignedByteWidth(spec)) {
			case 8 -> UNSIGNED_BYTE_8;
			case 16 -> UNSIGNED_BYTE_16;
			case 32 -> UNSIGNED_BYTE_32;
			default -> T;
		};
	}

	/**
	 * The value {@code array-element-type} answers for a code: the boolean {@code t}, a
	 * type-name symbol, or the list {@code (unsigned-byte n)}.
	 * @param code one of the codes in this class
	 * @return the element type value
	 */
	public static LispVal valueOf(int code) {
		return switch (code) {
			case CHARACTER -> new LispSymbol(LispNames.CHARACTER_TYPE);
			case UNSIGNED_BYTE_8 -> unsignedByte(8);
			case UNSIGNED_BYTE_16 -> unsignedByte(16);
			case UNSIGNED_BYTE_32 -> unsignedByte(32);
			case SINGLE_FLOAT -> new LispSymbol(LispNames.SINGLE_FLOAT);
			case DOUBLE_FLOAT -> new LispSymbol(LispNames.DOUBLE_FLOAT);
			default -> LispTrue.INSTANCE;
		};
	}

	/**
	 * The element an unsupplied slot of an array of this type takes. Common Lisp leaves
	 * an uninitialized element undefined, and an array the program asked to hold
	 * characters, bytes or floats should hold one rather than {@code nil}.
	 * @param code one of the codes in this class
	 * @return the default element, or null for {@link #T} (which has no natural zero)
	 */
	public static @Nullable LispVal defaultElement(int code) {
		return switch (code) {
			case CHARACTER -> new LispChar(' ');
			case UNSIGNED_BYTE_8, UNSIGNED_BYTE_16, UNSIGNED_BYTE_32 -> new LispInteger(0);
			case SINGLE_FLOAT, DOUBLE_FLOAT -> new LispDouble(0.0);
			default -> null;
		};
	}

	private static LispVal unsignedByte(int width) {
		return new LispCons(new LispSymbol(LispNames.UNSIGNED_BYTE),
				new LispCons(new LispInteger(width), LispNil.INSTANCE));
	}

	// Strips the (quote x) a literal :element-type argument still carries at a call site;
	// an alias resolved through the deftype registry arrives bare and passes through.
	private static @Nullable LispVal unquote(@Nullable LispVal elementType) {
		if (elementType instanceof LispCons cons && cons.car() instanceof LispSymbol q
				&& LispNames.QUOTE.equals(q.name()) && cons.cdr() instanceof LispCons rest
				&& rest.cdr() instanceof LispNil) {
			return rest.car();
		}
		return elementType;
	}

	private static String localName(String name) {
		int colon = name.lastIndexOf(':');
		return colon >= 0 ? name.substring(colon + 1) : name;
	}

}
