package am.ik.wasm;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A decoded view of the parts of a core module a whole-module ANALYSIS needs, where
 * {@link WasmSections} only frames them: every type definition with its parameter,
 * result, field and element types; and a function body as a list of instructions with
 * their immediates, their byte spans (so an unchanged instruction can be copied back
 * verbatim) and the matching {@code else}/{@code end} of every block opener.
 * <p>
 * The instruction subset is exactly the one {@code WasmSections.scanInstr} accepts -- the
 * finite set the rontolisp backends emit. An opcode outside it throws, so a newly emitted
 * instruction is caught by the corpus tests rather than mis-framed here.
 */
final class WasmCodeModel {

	private WasmCodeModel() {
	}

	// --- Value types ---

	/**
	 * A value or storage type. {@code code} is the type's leading byte: a numeric or
	 * vector type ({@code 0x7B-0x7F}), a packed storage type ({@code 0x77}/{@code 0x78}),
	 * or a reference ({@code 0x63} nullable / {@code 0x64} non-null, with {@code heap}
	 * either a concrete type index or a negative abstract heap-type code -- the shorthand
	 * {@code eqref} decodes as a nullable reference to {@code eq}).
	 */
	record ValType(int code, int heap, boolean nullable) {

		static final ValType I32 = new ValType(0x7F, 0, false);

		static final ValType I64 = new ValType(0x7E, 0, false);

		static final ValType F32 = new ValType(0x7D, 0, false);

		static final ValType F64 = new ValType(0x7C, 0, false);

		static final ValType V128 = new ValType(0x7B, 0, false);

		/** A nullable reference to an abstract heap type, as its one-byte shorthand. */
		static ValType abstractRef(int shorthand) {
			return new ValType(0x63, shorthand - 0x80, true);
		}

		boolean isRef() {
			return this.code == 0x63 || this.code == 0x64;
		}

		boolean isI32() {
			return this.code == 0x7F;
		}

	}

	/** Reads a {@code valtype} (also accepts the two packed storage types). */
	static ValType readValType(byte[] buf, int[] p) {
		int b = buf[p[0]++] & 0xff;
		if (b == 0x63 || b == 0x64) {
			return new ValType(b, WasmSections.readS(buf, p), b == 0x63);
		}
		if (b >= 0x69 && b <= 0x74) {
			return ValType.abstractRef(b);
		}
		if ((b >= 0x7B && b <= 0x7F) || b == 0x77 || b == 0x78) {
			return new ValType(b, 0, false);
		}
		throw new IllegalStateException(String.format("WasmCodeModel: unhandled value type 0x%02X", b));
	}

	// The abstract heap-type codes as the s33 a heaptype immediate decodes to (the
	// shorthand byte minus 0x80): eq = -19, i31 = -20, ...
	static final int HEAP_ANY = 0x6E - 0x80;

	static final int HEAP_EQ = 0x6D - 0x80;

	static final int HEAP_I31 = 0x6C - 0x80;

	static final int HEAP_STRUCT = 0x6B - 0x80;

	static final int HEAP_ARRAY = 0x6A - 0x80;

	static final int HEAP_NONE = 0x71 - 0x80;

	// --- Type definitions ---

	sealed interface TypeDef permits FuncType, StructType, ArrayType {

	}

	record FuncType(List<ValType> params, List<ValType> results) implements TypeDef {
	}

	record FieldType(ValType storage, boolean mutable) {

		boolean isRef() {
			return this.storage.isRef();
		}

	}

	record StructType(List<FieldType> fields) implements TypeDef {
	}

	record ArrayType(FieldType elem) implements TypeDef {
	}

	/**
	 * The type section: every definition by index, and the {@code rec} group each index
	 * belongs to ({@code groupOf[t]}, with {@code groupStart[g]}/{@code groupSize[g]}
	 * giving the group's index range) -- the unit of canonical identity under wasm-GC.
	 */
	record TypeSection(List<TypeDef> types, int[] groupOf, int[] groupStart, int[] groupSize) {

		int count() {
			return this.types.size();
		}

		FuncType func(int index) {
			if (this.types.get(index) instanceof FuncType f) {
				return f;
			}
			throw new IllegalStateException("WasmCodeModel: type " + index + " is not a function type");
		}

	}

	static TypeSection parseTypeSection(byte @Nullable [] payload) {
		if (payload == null) {
			return new TypeSection(List.of(), new int[0], new int[0], new int[0]);
		}
		int[] p = { 0 };
		int count = WasmSections.readU(payload, p);
		List<TypeDef> types = new ArrayList<>();
		List<Integer> groupOf = new ArrayList<>();
		List<Integer> groupStart = new ArrayList<>();
		List<Integer> groupSize = new ArrayList<>();
		for (int g = 0; g < count; g++) {
			int members = 1;
			if ((payload[p[0]] & 0xff) == 0x4E) {
				p[0]++;
				members = WasmSections.readU(payload, p);
			}
			groupStart.add(types.size());
			groupSize.add(members);
			for (int k = 0; k < members; k++) {
				types.add(readSubType(payload, p));
				groupOf.add(g);
			}
		}
		return new TypeSection(types, toArray(groupOf), toArray(groupStart), toArray(groupSize));
	}

	private static int[] toArray(List<Integer> values) {
		int[] out = new int[values.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = values.get(i);
		}
		return out;
	}

	// subtype := 0x50 vec(typeidx) comptype | 0x4F vec(typeidx) comptype | comptype
	private static TypeDef readSubType(byte[] buf, int[] p) {
		int b = buf[p[0]] & 0xff;
		if (b == 0x50 || b == 0x4F) {
			p[0]++;
			int supertypes = WasmSections.readU(buf, p);
			// The backend never declares a supertype (.kb/wasm-gc-final-types.md), and
			// subtyping would make a `ref.test` answer for more than one type index.
			if (supertypes != 0) {
				throw new IllegalStateException("WasmCodeModel: a declared supertype is not supported");
			}
		}
		int tag = buf[p[0]++] & 0xff;
		switch (tag) {
			case 0x60 -> {
				int np = WasmSections.readU(buf, p);
				List<ValType> params = new ArrayList<>(np);
				for (int i = 0; i < np; i++) {
					params.add(readValType(buf, p));
				}
				int nr = WasmSections.readU(buf, p);
				List<ValType> results = new ArrayList<>(nr);
				for (int i = 0; i < nr; i++) {
					results.add(readValType(buf, p));
				}
				return new FuncType(List.copyOf(params), List.copyOf(results));
			}
			case 0x5E -> {
				return new ArrayType(readFieldType(buf, p));
			}
			case 0x5F -> {
				int n = WasmSections.readU(buf, p);
				List<FieldType> fields = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					fields.add(readFieldType(buf, p));
				}
				return new StructType(List.copyOf(fields));
			}
			default ->
				throw new IllegalStateException(String.format("WasmCodeModel: unhandled comptype tag 0x%02X", tag));
		}
	}

	private static FieldType readFieldType(byte[] buf, int[] p) {
		ValType storage = readValType(buf, p);
		int mut = buf[p[0]++] & 0xff;
		return new FieldType(storage, mut == 1);
	}

	// --- Instructions ---

	/** One catch clause of a {@code try_table}. */
	record Catch(int kind, int tag, int label) {
	}

	/**
	 * A block type: the parameters it takes off the stack and the results it leaves, plus
	 * the byte span of its encoding within the instruction (copied when an {@code if}
	 * becomes a {@code block}).
	 */
	record BlockType(List<ValType> params, List<ValType> results, int rawStart, int rawEnd) {
	}

	/** One decoded instruction: opcode, immediates, byte span and block structure. */
	static final class Instr {

		/** The opcode byte (the prefix for prefixed instructions). */
		final int op;

		/**
		 * The sub-opcode after a {@code 0xFB}/{@code 0xFC}/{@code 0xFD} prefix, else -1.
		 */
		final int sub;

		/** Byte span within the code entry. */
		final int start;

		int end;

		/** First immediate (an index, a label, a constant, a heap type ...). */
		long a;

		/** Second immediate (a field index, an element count, a memarg offset ...). */
		long b;

		/** {@code br_table}: the labels, the default last. */
		int @Nullable [] labels;

		@Nullable BlockType blockType;

		@Nullable List<Catch> catches;

		/**
		 * A block opener's matching {@code end}; an {@code else}/{@code end}'s opener.
		 */
		int match = -1;

		/** An {@code if}'s {@code else}, or -1. */
		int elseIndex = -1;

		Instr(int op, int sub, int start) {
			this.op = op;
			this.sub = sub;
			this.start = start;
		}

		boolean isOpener() {
			return this.op == 0x02 || this.op == 0x03 || this.op == 0x04 || this.op == 0x1F;
		}

	}

	/** A decoded code entry: its locals (parameters excluded) and its instructions. */
	record Body(List<ValType> locals, List<Instr> code) {
	}

	/**
	 * Writes a locals vector as the emitter would: one run per stretch of equal types.
	 * @param out the code entry being written
	 * @param locals the locals in declaration order (parameters excluded)
	 */
	static void writeLocals(java.io.ByteArrayOutputStream out, List<ValType> locals) {
		List<ValType> kinds = new ArrayList<>();
		List<Integer> runs = new ArrayList<>();
		for (ValType t : locals) {
			if (!kinds.isEmpty() && kinds.get(kinds.size() - 1).equals(t)) {
				runs.set(runs.size() - 1, runs.get(runs.size() - 1) + 1);
			}
			else {
				kinds.add(t);
				runs.add(1);
			}
		}
		WasmSections.writeU(out, kinds.size());
		for (int i = 0; i < kinds.size(); i++) {
			WasmSections.writeU(out, runs.get(i));
			writeValType(out, kinds.get(i));
		}
	}

	/**
	 * Writes a value type in its shortest legal encoding, as the emitter does: a nullable
	 * reference to an abstract heap type is its one-byte shorthand.
	 * @param out the destination
	 * @param t the type
	 */
	static void writeValType(java.io.ByteArrayOutputStream out, ValType t) {
		if (t.isRef()) {
			if (t.code() == 0x63 && t.heap() < 0) {
				out.write(t.heap() + 0x80);
				return;
			}
			out.write(t.code());
			WasmSections.writeS(out, t.heap());
			return;
		}
		out.write(t.code());
	}

	/**
	 * Decodes one code entry.
	 * @param entry the code entry (locals + instruction stream)
	 * @param types the type section, for the {@code typeidx} block-type form
	 * @return the decoded body
	 */
	static Body decode(byte[] entry, TypeSection types) {
		int[] p = { 0 };
		int groups = WasmSections.readU(entry, p);
		List<ValType> locals = new ArrayList<>();
		for (int i = 0; i < groups; i++) {
			int run = WasmSections.readU(entry, p);
			ValType t = readValType(entry, p);
			for (int k = 0; k < run; k++) {
				locals.add(t);
			}
		}
		// Sized for the common instruction length (an opcode and a one-byte immediate),
		// so a body rarely regrows its list.
		List<Instr> code = new ArrayList<>(Math.max(8, (entry.length - p[0]) / 2));
		while (p[0] < entry.length) {
			code.add(decodeInstr(entry, p, types));
		}
		matchBlocks(code);
		return new Body(locals, code);
	}

	// Decodes a constant expression (a global initializer): the instructions up to and
	// including its terminating `end`.
	static List<Instr> decodeConstExpr(byte[] buf, int[] p, TypeSection types) {
		List<Instr> code = new ArrayList<>();
		while (true) {
			Instr in = decodeInstr(buf, p, types);
			code.add(in);
			if (in.op == 0x0B) {
				break;
			}
		}
		matchBlocks(code);
		return code;
	}

	private static void matchBlocks(List<Instr> code) {
		int[] openers = new int[16];
		int depth = 0;
		for (int i = 0; i < code.size(); i++) {
			Instr in = code.get(i);
			if (in.isOpener()) {
				if (depth == openers.length) {
					openers = java.util.Arrays.copyOf(openers, depth * 2);
				}
				openers[depth++] = i;
			}
			else if (in.op == 0x05) { // else
				if (depth == 0) {
					throw new IndexOutOfBoundsException("WasmCodeModel: else outside a block");
				}
				int opener = openers[depth - 1];
				code.get(opener).elseIndex = i;
				in.match = opener;
			}
			else if (in.op == 0x0B) { // end
				if (depth == 0) {
					in.match = -1; // the function's own end
				}
				else {
					int opener = openers[--depth];
					code.get(opener).match = i;
					in.match = opener;
				}
			}
		}
		if (depth != 0) {
			throw new IllegalStateException("WasmCodeModel: unterminated block");
		}
	}

	private static Instr decodeInstr(byte[] buf, int[] p, TypeSection types) {
		int start = p[0];
		int op = buf[p[0]++] & 0xff;
		Instr in;
		if (op >= 0x45 && op <= 0xC4) {
			in = new Instr(op, -1, start); // numeric: no immediate
		}
		else {
			in = switch (op) {
				case 0x00, 0x01, 0x05, 0x0A, 0x0B, 0x0F, 0x1A, 0x1B, 0xD1, 0xD3 -> new Instr(op, -1, start);
				case 0x02, 0x03, 0x04 -> {
					Instr i = new Instr(op, -1, start);
					i.blockType = readBlockType(buf, p, types);
					yield i;
				}
				case 0x1F -> {
					Instr i = new Instr(op, -1, start);
					i.blockType = readBlockType(buf, p, types);
					int n = WasmSections.readU(buf, p);
					List<Catch> catches = new ArrayList<>(n);
					for (int k = 0; k < n; k++) {
						int kind = buf[p[0]++] & 0xff;
						switch (kind) {
							case 0x00, 0x01 -> {
								int tag = WasmSections.readU(buf, p);
								int label = WasmSections.readU(buf, p);
								catches.add(new Catch(kind, tag, label));
							}
							case 0x02, 0x03 -> catches.add(new Catch(kind, -1, WasmSections.readU(buf, p)));
							default -> throw new IllegalStateException(
									String.format("WasmCodeModel: unhandled catch clause kind 0x%02X", kind));
						}
					}
					i.catches = catches;
					yield i;
				}
				// return_call (0x12) carries a function index exactly as call does.
				case 0x08, 0x0C, 0x0D, 0x10, 0x12, 0x20, 0x21, 0x22, 0x23, 0x24 -> {
					Instr i = new Instr(op, -1, start);
					i.a = WasmSections.readU(buf, p);
					yield i;
				}
				case 0x0E -> {
					Instr i = new Instr(op, -1, start);
					int n = WasmSections.readU(buf, p);
					int[] labels = new int[n + 1];
					for (int k = 0; k <= n; k++) {
						labels[k] = WasmSections.readU(buf, p);
					}
					i.labels = labels;
					yield i;
				}
				case 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
						0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E -> {
					Instr i = new Instr(op, -1, start);
					i.a = WasmSections.readU(buf, p);
					i.b = WasmSections.readU(buf, p);
					yield i;
				}
				case 0x3F, 0x40 -> {
					Instr i = new Instr(op, -1, start);
					i.a = buf[p[0]++] & 0xff;
					yield i;
				}
				case 0x41 -> {
					Instr i = new Instr(op, -1, start);
					i.a = WasmSections.readS(buf, p);
					yield i;
				}
				case 0x42 -> {
					Instr i = new Instr(op, -1, start);
					skipLeb(buf, p);
					yield i;
				}
				case 0x43 -> {
					p[0] += 4;
					yield new Instr(op, -1, start);
				}
				case 0x44 -> {
					p[0] += 8;
					yield new Instr(op, -1, start);
				}
				case 0xD0 -> {
					Instr i = new Instr(op, -1, start);
					i.a = WasmSections.readS(buf, p);
					yield i;
				}
				case 0xFB -> decodeGc(buf, p, start);
				case 0xFC -> {
					int sub = WasmSections.readU(buf, p);
					// memory.copy (0x0A) carries two memory indices, memory.fill (0x0B)
					// one; the saturating truncations (0x00-0x07) carry none.
					switch (sub) {
						case 0x0A -> p[0] += 2;
						case 0x0B -> p[0]++;
						default -> {
							if (sub > 0x07) {
								throw new IllegalStateException(
										String.format("WasmCodeModel: unhandled misc opcode 0xFC 0x%02X", sub));
							}
						}
					}
					yield new Instr(op, sub, start);
				}
				case 0xFD -> decodeSimd(buf, p, start);
				// call_indirect (0x11) and ref.func (0xD2) name things this model cannot
				// follow (a table; a function reachable other than by a direct call).
				default -> throw new IllegalStateException(String.format("WasmCodeModel: unhandled opcode 0x%02X", op));
			};
		}
		in.end = p[0];
		return in;
	}

	private static Instr decodeGc(byte[] buf, int[] p, int start) {
		int sub = WasmSections.readU(buf, p);
		Instr in = new Instr(0xFB, sub, start);
		switch (sub) {
			case 0x00, 0x01, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x10 -> in.a = WasmSections.readU(buf, p);
			case 0x14, 0x15, 0x16, 0x17 -> in.a = WasmSections.readS(buf, p);
			case 0x02, 0x03, 0x04, 0x05, 0x08, 0x11 -> {
				in.a = WasmSections.readU(buf, p);
				in.b = WasmSections.readU(buf, p);
			}
			case 0x0F, 0x1C, 0x1D, 0x1E -> {
			}
			// any.convert_extern / extern.convert_any (0x1A/0x1B) would let a host value
			// enter the eq hierarchy; the data-segment array constructors carry a
			// dataidx. The backend emits none of them.
			default ->
				throw new IllegalStateException(String.format("WasmCodeModel: unhandled GC opcode 0xFB 0x%02X", sub));
		}
		return in;
	}

	private static Instr decodeSimd(byte[] buf, int[] p, int start) {
		int sub = WasmSections.readU(buf, p);
		Instr in = new Instr(0xFD, sub, start);
		switch (sub) {
			case 0x00, 0x0B -> {
				in.a = WasmSections.readU(buf, p);
				in.b = WasmSections.readU(buf, p);
			}
			case 0x0C, 0x0D -> p[0] += 16;
			case 0x1F, 0x20, 0x21, 0x22 -> in.a = buf[p[0]++] & 0xff;
			case 0x13, 0x14, 0x43, 0x44, 0x49, 0x4A, 0x52, 0x5F, 0xE0, 0xE1, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xEC, 0xED,
					0xEF, 0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5 ->
				{
				}
			default ->
				throw new IllegalStateException(String.format("WasmCodeModel: unhandled SIMD opcode 0xFD 0x%X", sub));
		}
		return in;
	}

	/**
	 * The stack effect of a SIMD instruction: {@code {pops, pushes}}.
	 */
	static int[] simdArity(int sub) {
		return switch (sub) {
			case 0x00 -> new int[] { 1, 1 }; // v128.load
			case 0x0B -> new int[] { 2, 0 }; // v128.store
			case 0x0C -> new int[] { 0, 1 }; // v128.const
			case 0x0D -> new int[] { 2, 1 }; // i8x16.shuffle
			case 0x13, 0x14 -> new int[] { 1, 1 }; // splat
			case 0x1F, 0x21 -> new int[] { 1, 1 }; // extract_lane
			case 0x20, 0x22 -> new int[] { 2, 1 }; // replace_lane
			case 0x43, 0x44, 0x49, 0x4A -> new int[] { 2, 1 }; // lt / gt
			case 0x52 -> new int[] { 3, 1 }; // bitselect
			case 0x5F, 0xE0, 0xE1, 0xE3, 0xEC, 0xED, 0xEF -> new int[] { 1, 1 }; // unary
			case 0xE4, 0xE5, 0xE6, 0xE7, 0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5 -> new int[] { 2, 1 }; // binary
			default ->
				throw new IllegalStateException(String.format("WasmCodeModel: unhandled SIMD opcode 0xFD 0x%X", sub));
		};
	}

	/**
	 * The stack effect of a numeric instruction ({@code 0x45-0xC4}): {@code {pops,
	 * pushes}}; the pushed value is a scalar whose exact type this model does not need.
	 */
	static int numericPops(int op) {
		if (op == 0x45 || op == 0x50) {
			return 1; // i32.eqz / i64.eqz
		}
		if (op >= 0x46 && op <= 0x66) {
			return 2; // comparisons
		}
		if (op >= 0x67 && op <= 0x69) {
			return 1; // i32 clz/ctz/popcnt
		}
		if (op >= 0x6A && op <= 0x78) {
			return 2;
		}
		if (op >= 0x79 && op <= 0x7B) {
			return 1;
		}
		if (op >= 0x7C && op <= 0x8A) {
			return 2;
		}
		if (op >= 0x8B && op <= 0x91) {
			return 1;
		}
		if (op >= 0x92 && op <= 0x98) {
			return 2;
		}
		if (op >= 0x99 && op <= 0x9F) {
			return 1;
		}
		if (op >= 0xA0 && op <= 0xA6) {
			return 2;
		}
		return 1; // conversions and sign extensions
	}

	/**
	 * Whether a numeric instruction can trap (integer division/remainder by zero, a
	 * float-to-integer truncation out of range) -- the ones a fold must not delete.
	 */
	static boolean numericMayTrap(int op) {
		return (op >= 0x6D && op <= 0x70) || (op >= 0x7F && op <= 0x82) || (op >= 0xA8 && op <= 0xAB)
				|| (op >= 0xAE && op <= 0xB1);
	}

	private static BlockType readBlockType(byte[] buf, int[] p, TypeSection types) {
		int rawStart = p[0];
		int b = buf[p[0]] & 0xff;
		if (b == 0x40) {
			p[0]++;
			return new BlockType(List.of(), List.of(), rawStart, p[0]);
		}
		if ((b >= 0x7B && b <= 0x7F) || (b >= 0x69 && b <= 0x74) || b == 0x63 || b == 0x64) {
			ValType t = readValType(buf, p);
			return new BlockType(List.of(), List.of(t), rawStart, p[0]);
		}
		int index = WasmSections.readS(buf, p);
		FuncType f = types.func(index);
		return new BlockType(f.params(), f.results(), rawStart, p[0]);
	}

	private static void skipLeb(byte[] buf, int[] p) {
		while ((buf[p[0]] & 0x80) != 0) {
			p[0]++;
		}
		p[0]++;
	}

}
