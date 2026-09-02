package am.ik.ffi;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;

/**
 * A foreign type designator, decided at RUN time -- the CFFI keyword set
 * ({@code :char :uchar :short :ushort :int :uint :long :ulong :llong :ullong :float
 * :double :pointer :string :void}, plus the fixed-width spellings {@code :int8} ..
 * {@code :uint64}) as {@linkplain Scalar scalars}, and a structure passed or returned BY
 * VALUE as a {@linkplain Struct list of member types}. The C integer names are aliases of
 * the fixed widths under LP64 (Linux and macOS, the platforms the linker serves here):
 * {@code :long} is 8 bytes.
 *
 * <p>
 * A struct's {@linkplain #layout() layout} is built with each member at its natural
 * alignment -- padding inserted between members and at the tail, exactly as a C compiler
 * lays the struct out -- which is what lets a binding pass and return structures by value
 * with no libffi anywhere.
 */
public sealed interface FfiType permits FfiType.Scalar, FfiType.Struct {

	/**
	 * The memory layout of a value of this type at the calling convention. An address --
	 * {@link Scalar#POINTER}, and the {@link Scalar#STRING} that travels as one -- is
	 * {@code JAVA_LONG} rather than {@code ADDRESS}: on both ABIs the linker serves a
	 * pointer and a 64-bit integer are the SAME parameter (one integer-class register,
	 * same width, same alignment), and spelling them as ONE carrier is what collapses the
	 * shape grid a native image has to ship ({@link FfiRuntime}, "The carriers are
	 * canonicalised"). {@link Scalar#VOID} has no layout and throws.
	 * @return the layout
	 */
	MemoryLayout layout();

	/**
	 * The type's spelling for messages and cache keys, in the Lisp designator's own
	 * notation (e.g. {@code :int32}, {@code (:struct :int32 :int32)}).
	 * @return the spelling
	 */
	String spelling();

	/**
	 * The byte size of the type.
	 * @return the size
	 */
	default long size() {
		return layout().byteSize();
	}

	/**
	 * The byte alignment of the type.
	 * @return the alignment
	 */
	default long align() {
		return layout().byteAlignment();
	}

	/**
	 * The scalar type for a designator name, spelled without the leading colon and in any
	 * case (e.g. {@code "int"}, {@code "uchar"}, {@code "UINT64"}).
	 * @param name the designator name
	 * @return the scalar
	 * @throws FfiException when no foreign type has that name
	 */
	static Scalar of(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "char", "int8" -> Scalar.INT8;
			case "uchar", "uint8", "unsigned-char" -> Scalar.UINT8;
			case "short", "int16" -> Scalar.INT16;
			case "ushort", "uint16", "unsigned-short" -> Scalar.UINT16;
			case "int", "int32" -> Scalar.INT32;
			case "uint", "uint32", "unsigned-int" -> Scalar.UINT32;
			case "long", "llong", "long-long", "int64" -> Scalar.INT64;
			case "ulong", "ullong", "unsigned-long", "unsigned-long-long", "uint64" -> Scalar.UINT64;
			case "float" -> Scalar.FLOAT;
			case "double" -> Scalar.DOUBLE;
			case "pointer" -> Scalar.POINTER;
			case "string" -> Scalar.STRING;
			case "void" -> Scalar.VOID;
			default -> throw new FfiException("no such foreign type: :" + name.toLowerCase(Locale.ROOT));
		};
	}

	/**
	 * The scalar foreign types. The integer members carry their signedness so a return
	 * value or a load can be widened correctly ({@code :uchar} 255 must not arrive as
	 * -1).
	 */
	enum Scalar implements FfiType {

		INT8(ValueLayout.JAVA_BYTE, false), UINT8(ValueLayout.JAVA_BYTE, true), INT16(ValueLayout.JAVA_SHORT, false),
		UINT16(ValueLayout.JAVA_SHORT, true), INT32(ValueLayout.JAVA_INT, false), UINT32(ValueLayout.JAVA_INT, true),
		INT64(ValueLayout.JAVA_LONG, false), UINT64(ValueLayout.JAVA_LONG, true), FLOAT(ValueLayout.JAVA_FLOAT, false),
		DOUBLE(ValueLayout.JAVA_DOUBLE, false), POINTER(ValueLayout.JAVA_LONG, false),
		STRING(ValueLayout.JAVA_LONG, false), VOID(null, false);

		private final @Nullable ValueLayout valueLayout;

		private final boolean unsigned;

		Scalar(@Nullable ValueLayout valueLayout, boolean unsigned) {
			this.valueLayout = valueLayout;
			this.unsigned = unsigned;
		}

		@Override
		public MemoryLayout layout() {
			if (this.valueLayout == null) {
				throw new FfiException(":void has no size");
			}
			return this.valueLayout;
		}

		@Override
		public String spelling() {
			return ":" + name().toLowerCase(Locale.ROOT);
		}

		/**
		 * Whether the type is an unsigned integer, so a raw value must be masked rather
		 * than sign-extended.
		 * @return {@code true} for the unsigned integer types
		 */
		public boolean unsigned() {
			return this.unsigned;
		}

	}

	/**
	 * A structure passed or returned by value: its members in declaration order, laid out
	 * with natural alignment. A member is any type but {@code :void} and {@code :string}
	 * (a string slot is a {@code :pointer}); nesting is allowed, and a nested member is
	 * spliced into the layout FLAT -- the offsets are the same and both ABIs classify an
	 * aggregate by flattening it, so the flat spelling is one shape where the nested one
	 * would be another.
	 *
	 * @param members the member types, in order
	 */
	record Struct(List<FfiType> members) implements FfiType {

		public Struct {
			if (members.isEmpty()) {
				throw new FfiException("a struct type needs at least one member");
			}
			members = List.copyOf(members);
			for (FfiType member : members) {
				if (member == Scalar.VOID || member == Scalar.STRING) {
					throw new FfiException(
							"a struct member cannot be " + member.spelling() + " (a string slot is :pointer)");
				}
			}
		}

		@Override
		public MemoryLayout layout() {
			List<MemoryLayout> parts = new ArrayList<>();
			long offset = 0;
			long alignment = 1;
			for (FfiType member : this.members) {
				MemoryLayout layout = member.layout();
				long a = layout.byteAlignment();
				alignment = Math.max(alignment, a);
				long padding = (a - offset % a) % a;
				if (padding > 0) {
					parts.add(MemoryLayout.paddingLayout(padding));
				}
				if (member instanceof Struct nested) {
					// A nested struct is SPLICED IN, not nested: same offsets, same
					// size, same alignment -- and both ABIs classify an aggregate by
					// flattening it anyway (SysV merges the eightbytes, AAPCS64 defines
					// an HFA recursively), so the flat spelling is the same call. What
					// it buys is the shape grid: a struct of two CGPoints and one of
					// four doubles are then ONE registered shape, not two.
					parts.addAll(((java.lang.foreign.GroupLayout) nested.layout()).memberLayouts());
				}
				else {
					parts.add(layout);
				}
				offset += padding + layout.byteSize();
			}
			long tail = (alignment - offset % alignment) % alignment;
			if (tail > 0) {
				parts.add(MemoryLayout.paddingLayout(tail));
			}
			// structLayout's own alignment is the maximum member alignment, which with
			// the padding above is exactly the C rule.
			return MemoryLayout.structLayout(parts.toArray(MemoryLayout[]::new));
		}

		@Override
		public String spelling() {
			StringJoiner joiner = new StringJoiner(" ", "(:struct ", ")");
			for (FfiType member : this.members) {
				joiner.add(member.spelling());
			}
			return joiner.toString();
		}

	}

}
