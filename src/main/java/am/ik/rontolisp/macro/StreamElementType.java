package am.ik.rontolisp.macro;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * What a file stream opened with a given {@code :element-type} moves per element: a
 * character, or an integer of {@code octets} little-endian octets, two's complement when
 * {@code signed}. One classification for all four backends
 * ({@code .kb/read-load-streams.md}, "Element types wider and narrower than one octet").
 *
 * <p>
 * The widths are SBCL's, measured: an integer type needing {@code b} bits is stored in 1,
 * 2, 4 or 8 octets for {@code b <= 64} and in {@code ceil(b/8)} octets beyond, with no
 * packing below one octet and no bias -- a one-bit type and {@code (integer 100 200)}
 * both move one raw octet, a 20-bit type four, a 100-bit type thirteen.
 * {@code stream-element-type} answers the widened type ({@link #spec()}), exactly as SBCL
 * does.
 *
 * @param octets the octets per element, {@code 0} for a character stream
 * @param signed whether an element is two's complement
 */
public record StreamElementType(int octets, boolean signed) {

	/** A character stream. */
	public static final StreamElementType CHARACTER = new StreamElementType(0, false);

	/** The one-octet unsigned stream {@code (unsigned-byte 8)}. */
	public static final StreamElementType OCTET = new StreamElementType(1, false);

	/**
	 * Classifies an (unquoted) element-type specifier, or answers null for one no stream
	 * here carries: a non-integer, non-character type, an unbounded integer type, or an
	 * empty one.
	 * @param spec the evaluated type specifier
	 * @return the classification, or null
	 */
	public static @Nullable StreamElementType of(LispVal spec) {
		if (spec instanceof LispSymbol sym) {
			switch (IntegerTypeRange.plainName(sym)) {
				case "CHARACTER", "BASE-CHAR", ":DEFAULT" -> {
					return CHARACTER;
				}
				// The unsized spellings are the default-width byte streams SBCL opens.
				case "UNSIGNED-BYTE" -> {
					return OCTET;
				}
				case "SIGNED-BYTE" -> {
					return new StreamElementType(1, true);
				}
				default -> {
					// fall through to the interval
				}
			}
		}
		if (spec instanceof LispCons cons && cons.car() instanceof LispSymbol head && cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			String name = IntegerTypeRange.plainName(head);
			if (("UNSIGNED-BYTE".equals(name) || "SIGNED-BYTE".equals(name))
					&& (parts.size() == 1 || parts.get(1) instanceof LispSymbol star && "*".equals(star.name()))) {
				return of(head);
			}
		}
		IntegerTypeRange range = IntegerTypeRange.covering(spec);
		if (range == null || range.isEmpty() || range.lo() == null || range.hi() == null) {
			return null;
		}
		boolean signed = range.lo().signum() < 0;
		int bits = signed ? Math.max(range.lo().bitLength(), range.hi().bitLength()) + 1
				: Math.max(1, range.hi().bitLength());
		return new StreamElementType(octetsFor(bits), signed);
	}

	/**
	 * Whether the classification is anything but a character or an
	 * {@code (unsigned-byte 8)} stream -- the ones every backend's octet path already
	 * moves as they are.
	 * @return true for a wide, narrow-signed or multi-octet element
	 */
	public boolean isWide() {
		return this.octets > 1 || this.signed;
	}

	/**
	 * Whether this is a character stream.
	 * @return true for {@link #CHARACTER}
	 */
	public boolean isCharacter() {
		return this.octets == 0;
	}

	/**
	 * The type {@code stream-element-type} answers: {@code character}, or the widened
	 * {@code (unsigned-byte 8k)} / {@code (signed-byte 8k)}.
	 * @return the type specifier
	 */
	public LispVal spec() {
		if (isCharacter()) {
			return new LispSymbol(LispNames.CHARACTER_TYPE);
		}
		return new LispCons(new LispSymbol(this.signed ? "SIGNED-BYTE" : LispNames.UNSIGNED_BYTE),
				new LispCons(new LispInteger(8L * this.octets), LispNil.INSTANCE));
	}

	private static int octetsFor(int bits) {
		if (bits <= 8) {
			return 1;
		}
		if (bits <= 16) {
			return 2;
		}
		if (bits <= 32) {
			return 4;
		}
		if (bits <= 64) {
			return 8;
		}
		return (bits + 7) / 8;
	}

}
