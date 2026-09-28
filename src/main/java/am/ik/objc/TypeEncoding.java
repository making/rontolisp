package am.ik.objc;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.PaddingLayout;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * An Objective-C method type encoding, parsed into the shape a foreign call needs.
 *
 * <p>
 * The runtime describes every method completely: {@code method_getTypeEncoding} answers a
 * string such as {@code @68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64} -- the return
 * type first, then each argument type, each followed by its stack offset (which this
 * parser skips). The first two arguments are always the receiver and the selector. That
 * string is the whole reason a GENERIC {@code send} is honest rather than a footgun: the
 * {@link FunctionDescriptor} is derived from it, so a selector sent with the wrong arity
 * or the wrong operand type is a caught error, not the SIGBUS that sending an
 * {@code NSRect} through a {@code long} shape gives you.
 *
 * <p>
 * A struct is carried as its scalar leaves ({@code {CGRect={CGPoint=dd}{CGSize=dd}}}
 * becomes four doubles), each at the offset the C rule gives it over the NESTED members:
 * an inner struct keeps its own tail padding, so {@code {Outer={Inner=dc}c}} is 24 bytes
 * with the outer {@code c} at 16 ({@link Type#layout()}). A block ({@code @?}) is an
 * object. Unions, bitfields, function pointers ({@code ?}) and {@code long double} are
 * {@linkplain #parse rejected}: a selector that takes one is outside the first cut, and
 * the error names the encoding so the caller can see why.
 *
 * <p>
 * Pure: nothing here touches the runtime, so it is testable on any platform.
 *
 * @param returnType the return type
 * @param argumentTypes every argument type, the receiver and the selector included
 */
public record TypeEncoding(Type returnType, List<Type> argumentTypes) {

	/** The scalar kinds a leaf can have, each carrying its FFM layout. */
	public enum Kind {

		/** {@code @} -- an object reference. */
		OBJECT(ValueLayout.ADDRESS),
		/** {@code #} -- a class reference. */
		CLASS(ValueLayout.ADDRESS),
		/** {@code :} -- a selector. */
		SELECTOR(ValueLayout.ADDRESS),
		/** {@code *} -- a C string. */
		CSTRING(ValueLayout.ADDRESS),
		/** {@code ^...} -- any other pointer. */
		POINTER(ValueLayout.ADDRESS),
		/** {@code B} -- a C99 {@code bool}, which is what {@code BOOL} is on arm64. */
		BOOL(ValueLayout.JAVA_BOOLEAN),
		/** {@code c} / {@code C}. */
		INT8(ValueLayout.JAVA_BYTE),
		/** {@code s} / {@code S}. */
		INT16(ValueLayout.JAVA_SHORT),
		/** {@code i} / {@code I}. */
		INT32(ValueLayout.JAVA_INT),
		/**
		 * {@code l} / {@code L} / {@code q} / {@code Q} -- 64-bit on every Apple
		 * platform.
		 */
		INT64(ValueLayout.JAVA_LONG),
		/** {@code f}. */
		FLOAT(ValueLayout.JAVA_FLOAT),
		/** {@code d}. */
		DOUBLE(ValueLayout.JAVA_DOUBLE),
		/** {@code v} -- a return type only. */
		VOID(null),
		/** {@code {...}} -- a struct, carried by value; see {@link Type#leaves()}. */
		STRUCT(null);

		private final @Nullable MemoryLayout layout;

		Kind(@Nullable MemoryLayout layout) {
			this.layout = layout;
		}

		/**
		 * The layout of a scalar kind.
		 * @return the layout
		 * @throws IllegalStateException for {@code void} and a struct
		 */
		public MemoryLayout scalarLayout() {
			if (this.layout == null) {
				throw new IllegalStateException(this + " has no scalar layout");
			}
			return this.layout;
		}

		/**
		 * @return whether this kind is passed as an address
		 */
		public boolean isAddress() {
			return this.layout instanceof AddressLayout;
		}

	}

	/**
	 * One parameter or return type.
	 *
	 * @param kind the scalar kind, or {@link Kind#STRUCT}
	 * @param members a struct's members as the encoding nests them (empty otherwise); an
	 * array member is its element repeated
	 * @param leaves the scalar leaves of a struct (empty otherwise), in memory order
	 * @param offsets each leaf's byte offset, by the C rule over the nested members
	 * @param unsigned whether an integer kind was declared unsigned
	 */
	public record Type(Kind kind, List<Type> members, List<Kind> leaves, List<Long> offsets, boolean unsigned) {

		static Type of(Kind kind) {
			return new Type(kind, List.of(), List.of(), List.of(), false);
		}

		static Type unsigned(Kind kind) {
			return new Type(kind, List.of(), List.of(), List.of(), true);
		}

		/**
		 * A struct of MEMBERS laid end to end by the C rule: each member at its own
		 * alignment, its leaves at their offsets within it.
		 */
		static Type struct(List<Type> members) {
			List<Kind> leaves = new ArrayList<>();
			List<Long> offsets = new ArrayList<>();
			long offset = 0;
			for (Type member : members) {
				MemoryLayout layout = member.argumentLayout();
				offset = roundUp(offset, layout.byteAlignment());
				if (member.isStruct()) {
					leaves.addAll(member.leaves);
					for (long inner : member.offsets) {
						offsets.add(offset + inner);
					}
				}
				else {
					leaves.add(member.kind);
					offsets.add(offset);
				}
				offset += layout.byteSize();
			}
			return new Type(Kind.STRUCT, List.copyOf(members), List.copyOf(leaves), List.copyOf(offsets), false);
		}

		/**
		 * The FFM layout of this type, or {@code null} for {@code void}.
		 *
		 * <p>
		 * A struct's is its leaves laid end to end whenever that puts each leaf at its C
		 * offset -- every AppKit struct, whose spelling in the native image's closed
		 * table is the flat one -- and the nested members otherwise: an inner struct's
		 * tail padding (or a wider inner alignment) moves the leaves after it past where
		 * the flat layout would put them, and the linker refuses padding that natural
		 * alignment does not call for. Both classify the same (an HFA by its leaves, any
		 * other composite by its size), so the choice only decides which one the linker
		 * accepts.
		 * @return the layout
		 */
		public @Nullable MemoryLayout layout() {
			if (this.kind != Kind.STRUCT) {
				return this.kind.layout;
			}
			List<MemoryLayout> flat = this.leaves.stream().map(Kind::scalarLayout).toList();
			return naturalOffsets(flat).equals(this.offsets) ? natural(flat)
					: natural(this.members.stream().map(Type::argumentLayout).toList());
		}

		/** Each member's offset when laid end to end at its own alignment. */
		private static List<Long> naturalOffsets(List<MemoryLayout> members) {
			List<Long> offsets = new ArrayList<>();
			long offset = 0;
			for (MemoryLayout member : members) {
				offset = roundUp(offset, member.byteAlignment());
				offsets.add(offset);
				offset += member.byteSize();
			}
			return offsets;
		}

		/** A struct of MEMBERS with the padding natural alignment calls for. */
		private static MemoryLayout natural(List<MemoryLayout> members) {
			List<MemoryLayout> out = new ArrayList<>();
			long offset = 0;
			long align = 1;
			for (MemoryLayout member : members) {
				long at = roundUp(offset, member.byteAlignment());
				if (at > offset) {
					out.add(MemoryLayout.paddingLayout(at - offset));
				}
				out.add(member);
				offset = at + member.byteSize();
				align = Math.max(align, member.byteAlignment());
			}
			long size = roundUp(offset, align);
			if (size > offset) {
				out.add(MemoryLayout.paddingLayout(size - offset));
			}
			return MemoryLayout.structLayout(out.toArray(MemoryLayout[]::new));
		}

		private static long roundUp(long offset, long align) {
			return (offset + align - 1) / align * align;
		}

		/**
		 * @return whether this is a struct carried by value
		 */
		public boolean isStruct() {
			return this.kind == Kind.STRUCT;
		}

		/**
		 * The layout of this type in an argument position, where {@code void} cannot
		 * appear.
		 * @return the layout
		 */
		public MemoryLayout argumentLayout() {
			MemoryLayout layout = layout();
			if (layout == null) {
				throw new ObjcException("a void argument");
			}
			return layout;
		}

	}

	/**
	 * Parses a method type encoding.
	 * @param encoding the string {@code method_getTypeEncoding} answered
	 * @return the parsed encoding
	 * @throws ObjcException when a type in it has no foreign-call shape
	 */
	public static TypeEncoding parse(String encoding) {
		Parser parser = new Parser(encoding);
		List<Type> types = new ArrayList<>();
		while (!parser.atEnd()) {
			types.add(parser.type());
			parser.skipDigits();
		}
		if (types.isEmpty()) {
			throw new ObjcException("empty type encoding");
		}
		Type ret = types.get(0);
		List<Type> args = types.subList(1, types.size());
		for (Type arg : args) {
			if (arg.kind() == Kind.VOID) {
				throw new ObjcException("void argument in type encoding '" + encoding + "'");
			}
		}
		return new TypeEncoding(ret, List.copyOf(args));
	}

	/**
	 * The foreign-call shape of this encoding. A variadic call's variadic arguments are
	 * part of the encoding the caller spells ({@code objc.lisp} appends them), so this is
	 * the whole shape; the linker option saying where they start is the caller's.
	 * @return the descriptor a downcall or upcall stub needs
	 */
	public FunctionDescriptor descriptor() {
		MemoryLayout[] all = this.argumentTypes.stream().map(Type::argumentLayout).toArray(MemoryLayout[]::new);
		MemoryLayout ret = this.returnType.layout();
		return ret == null ? FunctionDescriptor.ofVoid(all) : FunctionDescriptor.of(ret, all);
	}

	/**
	 * A shape spelled the way {@code reachability-metadata.json} spells it --
	 * {@code void*(void*,void*,jlong)} -- so that a native image refusing an unregistered
	 * shape can name the entry to add.
	 * @param descriptor the shape
	 * @return its spelling
	 */
	public static String spelling(FunctionDescriptor descriptor) {
		String ret = descriptor.returnLayout().map(TypeEncoding::spell).orElse("void");
		return ret + descriptor.argumentLayouts()
			.stream()
			.map(TypeEncoding::spell)
			.collect(Collectors.joining(",", "(", ")"));
	}

	private static String spell(MemoryLayout layout) {
		return switch (layout) {
			case AddressLayout ignored -> "void*";
			case ValueLayout.OfBoolean ignored -> "jboolean";
			case ValueLayout.OfByte ignored -> "jbyte";
			case ValueLayout.OfShort ignored -> "jshort";
			case ValueLayout.OfInt ignored -> "jint";
			case ValueLayout.OfLong ignored -> "jlong";
			case ValueLayout.OfFloat ignored -> "jfloat";
			case ValueLayout.OfDouble ignored -> "jdouble";
			// The padding is part of the spelling: the image builder rebuilds the layout
			// with MemoryLayout.structLayout, which refuses a member off its alignment.
			case PaddingLayout padding -> "padding(" + padding.byteSize() + ")";
			case GroupLayout group -> group.memberLayouts()
				.stream()
				.map(TypeEncoding::spell)
				.collect(Collectors.joining(",", "struct(", ")"));
			default -> layout.toString();
		};
	}

	/** A recursive-descent reader over one encoding string. */
	private static final class Parser {

		private final String source;

		private int pos;

		Parser(String source) {
			this.source = source;
		}

		boolean atEnd() {
			return this.pos >= this.source.length();
		}

		void skipDigits() {
			while (!atEnd() && Character.isDigit(this.source.charAt(this.pos))) {
				this.pos++;
			}
		}

		private char peek() {
			return this.source.charAt(this.pos);
		}

		Type type() {
			// Method qualifiers (const, in, out, inout, bycopy, byref, oneway) say
			// nothing about the shape.
			while (!atEnd() && "rnNoORV".indexOf(peek()) >= 0) {
				this.pos++;
			}
			if (atEnd()) {
				throw fail("truncated");
			}
			char c = peek();
			this.pos++;
			return switch (c) {
				case '@' -> {
					if (!atEnd() && peek() == '?') {
						// A block is an object; calling one is the caller's business, so
						// the call only needs its address. Clang's extended encoding
						// appends the block's own signature in angle brackets.
						this.pos++;
						skipBlockSignature();
						yield Type.of(Kind.OBJECT);
					}
					skipQuotedName();
					yield Type.of(Kind.OBJECT);
				}
				case '#' -> Type.of(Kind.CLASS);
				case ':' -> Type.of(Kind.SELECTOR);
				case '*' -> Type.of(Kind.CSTRING);
				case '^' -> {
					// The pointee's type is parsed only to consume it.
					if (!atEnd() && peek() == 'v') {
						this.pos++;
					}
					else {
						type();
					}
					yield Type.of(Kind.POINTER);
				}
				case 'B' -> Type.of(Kind.BOOL);
				case 'c' -> Type.of(Kind.INT8);
				case 'C' -> Type.unsigned(Kind.INT8);
				case 's' -> Type.of(Kind.INT16);
				case 'S' -> Type.unsigned(Kind.INT16);
				case 'i' -> Type.of(Kind.INT32);
				case 'I' -> Type.unsigned(Kind.INT32);
				case 'l', 'q' -> Type.of(Kind.INT64);
				case 'L', 'Q' -> Type.unsigned(Kind.INT64);
				case 'f' -> Type.of(Kind.FLOAT);
				case 'd' -> Type.of(Kind.DOUBLE);
				case 'v' -> Type.of(Kind.VOID);
				case '{' -> Type.struct(aggregate('}'));
				case '[' -> {
					int count = 0;
					while (!atEnd() && Character.isDigit(peek())) {
						count = count * 10 + (peek() - '0');
						this.pos++;
					}
					Type element = type();
					expect(']');
					yield Type.struct(Collections.nCopies(count, element));
				}
				case '(' -> throw fail("a union is not supported");
				case 'b' -> throw fail("a bitfield is not supported");
				case '?' -> throw fail("a function pointer is not supported");
				default -> throw fail("unsupported type '" + c + "'");
			};
		}

		/**
		 * Reads {@code name=members} up to the closing bracket.
		 */
		private List<Type> aggregate(char close) {
			// {CGRect=...}: the name (possibly "?" for an anonymous one) runs to '='; a
			// struct in a POINTEE position may have no member list at all ({CGRect}).
			while (!atEnd() && peek() != '=' && peek() != close) {
				this.pos++;
			}
			List<Type> members = new ArrayList<>();
			if (!atEnd() && peek() == '=') {
				this.pos++;
				while (!atEnd() && peek() != close) {
					skipQuotedName();
					Type member = type();
					if (member.kind() == Kind.VOID) {
						throw fail("void struct member");
					}
					members.add(member);
				}
			}
			expect(close);
			return members;
		}

		/** {@code @"NSString"} and {@code "field"} labels carry a quoted name. */
		private void skipQuotedName() {
			if (!atEnd() && peek() == '"') {
				int end = this.source.indexOf('"', this.pos + 1);
				this.pos = end < 0 ? this.source.length() : end + 1;
			}
		}

		private void skipBlockSignature() {
			if (atEnd() || peek() != '<') {
				return;
			}
			int depth = 0;
			while (!atEnd()) {
				char c = peek();
				this.pos++;
				if (c == '<') {
					depth++;
				}
				else if (c == '>' && --depth == 0) {
					return;
				}
			}
		}

		private void expect(char c) {
			if (atEnd() || peek() != c) {
				throw fail("expected '" + c + "'");
			}
			this.pos++;
		}

		private ObjcException fail(String why) {
			return new ObjcException("type encoding '" + this.source + "': " + why);
		}

	}

}
