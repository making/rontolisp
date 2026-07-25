package am.ik.rontolisp.compiler;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The vocabulary of types that cross a rontolisp WASM boundary — the single table both
 * WIT front-ends ({@code rontolisp:wit-export}) and both WASM backends consult, in the
 * same way {@link WitTypeMapper} is the single WIT&nbsp;&lt;-&gt;&nbsp;rontolisp value
 * mapping. This class contains <strong>no codegen</strong>; it names each boundary type
 * once, together with the two facts every consumer derives from: its WIT spelling and,
 * for an integer, the exact set of values it can carry.
 *
 * <p>
 * <strong>The designators are the WIT spellings.</strong> The vocabulary predates WIT (it
 * was the {@code rontolisp:wasm-export} {@code :int}/{@code :long}/{@code :float}/…
 * keyword set), which is why it had no unsigned member and could not express the
 * component-model tutorial world's {@code u32}. The canonical spelling of every
 * fixed-width integer is now the WIT one — {@code :s8} {@code :s16} {@code :s32}
 * {@code :s64} {@code :u8} {@code :u16} {@code :u32} {@code :u64} — so the accepted set
 * of a hand-written {@code rontolisp:wasm-export} and the accepted set of a WIT world are
 * the same set, spelled the same way. {@code :int} and {@code :long} remain accepted
 * forever as aliases of {@code :s32} / {@code :s64} and normalize to them at parse time,
 * so every program written against the old vocabulary keeps compiling to the same bytes.
 * The three non-integer types keep their names ({@code :float}, {@code :bool},
 * {@code :string}): each is already one-to-one with its WIT type, so a width-qualified
 * spelling would say nothing extra (rontolisp has no internal {@code f32}; see
 * {@code .kb/wasm-export-no-wasi.md}).
 *
 * <p>
 * <strong>The integer ranges are the whole boundary contract.</strong> A backend carries
 * Lisp integers in a house representation of its own — {@code i31ref} on the wasm-GC
 * backends, {@code i64} under {@code --no-gc} — and a boundary type's range may be wider
 * than that. {@link #range()} states each type's range exactly, so a backend decides
 * whether a wrapper needs a guard by comparing two intervals rather than by enumerating
 * cases:
 *
 * <ul>
 * <li><strong>inbound</strong> (a host value becoming a Lisp value): guard when
 * {@code houseRange} does not contain {@link #range()} — the incoming value may be
 * unrepresentable</li>
 * <li><strong>outbound</strong> (a Lisp value becoming a host value): guard when
 * {@link #range()} does not contain {@code houseRange} — the outgoing value may be
 * outside what the declared type can state</li>
 * </ul>
 *
 * The rule the guards implement is uniform over the whole family, including {@code :int}
 * and {@code :long}: <em>the boundary carries the value exactly, or the wrapper
 * traps</em>. Full rationale and the per-backend matrix: {@code .kb/wit.md}.
 *
 * @see WitTypeMapper
 */
public enum BoundaryType {

	/** {@code s8}: an 8-bit signed integer. */
	S8(":S8", "s8", 8, true),

	/** {@code s16}: a 16-bit signed integer. */
	S16(":S16", "s16", 16, true),

	/**
	 * {@code s32}: a 32-bit signed integer. The house spelling {@code :int} is an alias
	 * of this member.
	 */
	S32(":S32", "s32", 32, true),

	/**
	 * {@code s64}: a 64-bit signed integer. The house spelling {@code :long} is an alias
	 * of this member. Exact on every backend: {@code --no-gc}'s house integer is
	 * {@code i64}, and the wasm-GC backends carry it through the boxed exact-integer
	 * representation ({@code .kb/wasm-bignum.md}).
	 */
	S64(":S64", "s64", 64, true),

	/** {@code u8}: an 8-bit unsigned integer. */
	U8(":U8", "u8", 8, false),

	/** {@code u16}: a 16-bit unsigned integer. */
	U16(":U16", "u16", 16, false),

	/** {@code u32}: a 32-bit unsigned integer. */
	U32(":U32", "u32", 32, false),

	/**
	 * {@code u64}: a 64-bit unsigned integer. Like {@link #S64} it rides the {@code i64}
	 * core type; every backend's house integer is signed 64-bit, so a value at or above
	 * 2^63 has no exact representation and the wrapper traps (exact-or-trap).
	 */
	U64(":U64", "u64", 64, false),

	/**
	 * {@code f64}: a double-precision float. rontolisp has no internal {@code f32}, so
	 * {@code f64} is the only float the boundary carries and {@code :float} names it
	 * unambiguously.
	 */
	FLOAT(":FLOAT", "f64"),

	/** {@code bool}: {@code t} / {@code nil} (0 = nil, non-zero = t at the boundary). */
	BOOL(":BOOL", "bool"),

	/** {@code string}: a string, as {@code (ptr,len)} bytes in linear memory. */
	STRING(":STRING", "string"),

	/**
	 * An s-expression, crossing as its printed text. A rontolisp-only boundary type with
	 * no WIT spelling: a WIT world can never ask for it, and it lifts as a
	 * component-model {@code string}. wasm-GC backends only (it needs the embedded
	 * reader/printer).
	 */
	S_EXPR(":S-EXPR", null),

	/**
	 * No result: the wrapper discards the Lisp return value and has no WASM result.
	 * Selected when {@code :returns} is omitted, or given as {@code nil}, {@code '()} or
	 * {@code :void}. A parameter can never have this type.
	 */
	VOID(":VOID", null);

	/**
	 * A closed interval of integers. Used to state a boundary type's range and a
	 * backend's house-integer range in the same currency, so "does a wrapper need a guard
	 * here" is an interval containment test instead of a per-type case.
	 *
	 * @param min the smallest value in the interval, inclusive
	 * @param max the largest value in the interval, inclusive
	 */
	public record Range(BigInteger min, BigInteger max) {

		/**
		 * The range of a two's-complement signed integer of the given width.
		 * @param bits the width in bits (e.g. {@code 31} for the wasm-GC house integer
		 * {@code i31ref}, {@code 64} for the {@code --no-gc} house integer)
		 * @return the range {@code [-2^(bits-1), 2^(bits-1)-1]}
		 */
		public static Range ofSignedBits(int bits) {
			BigInteger limit = BigInteger.ONE.shiftLeft(bits - 1);
			return new Range(limit.negate(), limit.subtract(BigInteger.ONE));
		}

		/**
		 * The range of an unsigned integer of the given width.
		 * @param bits the width in bits
		 * @return the range {@code [0, 2^bits-1]}
		 */
		public static Range ofUnsignedBits(int bits) {
			return new Range(BigInteger.ZERO, BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE));
		}

		/**
		 * Returns whether this range contains every value of the other range.
		 * @param other the range to test
		 * @return {@code true} when {@code other} is a subset of this range
		 */
		public boolean contains(Range other) {
			return this.min.compareTo(other.min) <= 0 && this.max.compareTo(other.max) >= 0;
		}

	}

	// :INT and :LONG are the vocabulary rontolisp:wasm-export shipped with, before the
	// WIT
	// spellings became canonical. They stay accepted forever: normalizing them here
	// (rather
	// than keeping a second internal spelling) is what makes a program written either way
	// compile to the same bytes.
	private static final Map<String, BoundaryType> ALIASES = Map.of(":INT", S32, ":LONG", S64);

	private static final Map<String, BoundaryType> BY_DESIGNATOR = byDesignator();

	private static final Map<String, BoundaryType> BY_WIT_NAME = byWitName();

	private final String designator;

	private final @Nullable String witName;

	private final @Nullable Range range;

	private final int bits;

	private final boolean signed;

	BoundaryType(String designator, @Nullable String witName) {
		this.designator = designator;
		this.witName = witName;
		this.range = null;
		this.bits = 0;
		this.signed = false;
	}

	BoundaryType(String designator, String witName, int bits, boolean signed) {
		this.designator = designator;
		this.witName = witName;
		this.range = signed ? Range.ofSignedBits(bits) : Range.ofUnsignedBits(bits);
		this.bits = bits;
		this.signed = signed;
	}

	/**
	 * The canonical keyword spelling of this type, upcased the way the reader upcases a
	 * source keyword (e.g. {@code ":U32"}).
	 * @return the canonical designator
	 */
	public String designator() {
		return this.designator;
	}

	/**
	 * The WIT spelling of this type.
	 * @return the WIT primitive name, or {@code null} for the two rontolisp-only members
	 * ({@link #S_EXPR}, {@link #VOID}) that no WIT type maps to
	 */
	public @Nullable String witName() {
		return this.witName;
	}

	/**
	 * Returns whether this is one of the fixed-width integer types.
	 * @return {@code true} for {@link #S8} … {@link #U64}
	 */
	public boolean isInteger() {
		return this.range != null;
	}

	/**
	 * The exact set of values this type can state.
	 * @return the closed range, or {@code null} when this is not an integer type
	 */
	public @Nullable Range range() {
		return this.range;
	}

	/**
	 * The width of this integer type in bits.
	 * @return the width, or {@code 0} when this is not an integer type
	 */
	public int bits() {
		return this.bits;
	}

	/**
	 * Returns whether this integer type is signed.
	 * @return {@code true} for {@link #S8} … {@link #S64}, {@code false} for the unsigned
	 * members and for every non-integer type
	 */
	public boolean signed() {
		return this.signed;
	}

	/**
	 * Looks a type up by its keyword designator, accepting the {@code :int} /
	 * {@code :long} aliases.
	 * @param designator the keyword spelling, upcased (as the reader leaves it)
	 * @return the type, or {@code null} when the vocabulary has no such member
	 */
	public static @Nullable BoundaryType forDesignator(String designator) {
		return BY_DESIGNATOR.get(designator);
	}

	/**
	 * Looks a type up by its WIT spelling.
	 * @param witName the WIT primitive name (e.g. {@code "u32"})
	 * @return the type, or {@code null} when no boundary type carries that WIT type
	 */
	public static @Nullable BoundaryType forWitName(String witName) {
		return BY_WIT_NAME.get(witName);
	}

	/**
	 * The designators a directive may name, in vocabulary order, for an error message
	 * that has to spell out the accepted set. {@link #VOID} is excluded: it is the
	 * omitted-{@code :returns} marker, not something a parameter list may contain.
	 * @return the canonical designators of every non-void member
	 */
	public static List<String> valueDesignators() {
		List<String> names = new java.util.ArrayList<>();
		for (BoundaryType type : values()) {
			if (type != VOID) {
				names.add(type.designator);
			}
		}
		return List.copyOf(names);
	}

	private static Map<String, BoundaryType> byDesignator() {
		Map<String, BoundaryType> map = new LinkedHashMap<>();
		for (BoundaryType type : values()) {
			map.put(type.designator, type);
		}
		map.putAll(ALIASES);
		return Map.copyOf(map);
	}

	private static Map<String, BoundaryType> byWitName() {
		Map<String, BoundaryType> map = new LinkedHashMap<>();
		for (BoundaryType type : values()) {
			if (type.witName != null) {
				map.put(type.witName, type);
			}
		}
		return Map.copyOf(map);
	}

}
