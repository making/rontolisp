package am.ik.rontolisp.codegen.wasm;

import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * The fields of the reserved {@code rontolisp:wasm-import} module {@value #MODULE}: the
 * module's own linear memory, read and written from Lisp. Declaring one binds no host
 * function: the synthetic defun a directive always makes ({@code .kb/wasm-import.md})
 * answers from the module itself, so the name is callable and first-class like any
 * import, the interpreter and JVM stubs come for free, and the import section gains
 * nothing.
 *
 * <p>
 * This is what a C-shaped host ABI needs and a boundary type cannot state: a host
 * function that answers through out-pointers ({@code proxy_get_header_map_value(...,
 * char** value, size_t* size)}) takes addresses the program reserves and then reads.
 */
enum WasmMemoryIntrinsic {

	ALLOC("alloc", List.of(BoundaryType.U32), BoundaryType.U32, true),

	MARK("mark", List.of(), BoundaryType.U32, true),

	RESET("reset", List.of(BoundaryType.U32), BoundaryType.VOID, true),

	LOAD8("load8", List.of(BoundaryType.U32), BoundaryType.U32, false),

	LOAD32("load32", List.of(BoundaryType.U32), BoundaryType.U32, false),

	LOAD64("load64", List.of(BoundaryType.U32), BoundaryType.S64, false),

	STORE8("store8", List.of(BoundaryType.U32, BoundaryType.U32), BoundaryType.VOID, false),

	STORE32("store32", List.of(BoundaryType.U32, BoundaryType.U32), BoundaryType.VOID, false),

	STORE64("store64", List.of(BoundaryType.U32, BoundaryType.S64), BoundaryType.VOID, false),

	STRING("string", List.of(BoundaryType.U32, BoundaryType.U32), BoundaryType.STRING, false),

	OCTETS("octets", List.of(BoundaryType.U32, BoundaryType.U32), BoundaryType.OCTETS, false),

	/**
	 * Where a string's bytes first occur in the {@code size} bytes at an address: their
	 * index, or -1. A loop over memory no boundary crosses per byte, so a body costs what
	 * this function's words cost ({@link #emitSearch}) and is never copied.
	 */
	SEARCH("search", List.of(BoundaryType.STRING, BoundaryType.U32, BoundaryType.U32), BoundaryType.S32, false),

	/**
	 * Where a string's bytes are, which only {@code --no-gc} can answer: its string is a
	 * block of linear memory, and a wasm-GC string is an engine object.
	 */
	ADDRESS("address", List.of(BoundaryType.STRING), BoundaryType.U32, false),

	/**
	 * How many bytes start at {@link #ADDRESS}, where {@code length} counts characters.
	 */
	SIZE("size", List.of(BoundaryType.STRING), BoundaryType.U32, false);

	/** The reserved {@code :from} value. */
	static final String MODULE = "rontolisp:memory";

	/** A one in the low bit of each of a word's bytes. */
	private static final long ONE_PER_BYTE = 0x0101010101010101L;

	private final String field;

	private final List<BoundaryType> params;

	private final BoundaryType result;

	private final boolean arena;

	WasmMemoryIntrinsic(String field, List<BoundaryType> params, BoundaryType result, boolean arena) {
		this.field = field;
		this.params = params;
		this.result = result;
		this.arena = arena;
	}

	/**
	 * Whether a declaration names the reserved module.
	 * @param decl the parsed declaration
	 * @return whether it binds an intrinsic rather than a host function
	 */
	static boolean isIntrinsic(WasmImportCompiler.Decl decl) {
		return MODULE.equals(decl.module());
	}

	/**
	 * The intrinsic a declaration binds, checked against its declared signature so the
	 * Lisp stub's arity and the emitted body cannot disagree.
	 * @param decl a declaration of the reserved module
	 * @return the intrinsic
	 * @throws UnsupportedOperationException if the field is unknown, the signature is not
	 * the field's, or the declaration asks for {@code :async}
	 */
	static WasmMemoryIntrinsic of(WasmImportCompiler.Decl decl) {
		WasmMemoryIntrinsic intrinsic = Arrays.stream(values())
			.filter(candidate -> candidate.field.equals(decl.field()))
			.findFirst()
			.orElseThrow(() -> new UnsupportedOperationException("rontolisp:wasm-import '" + decl.name()
					+ "' names unknown field \"" + decl.field() + "\" of \"" + MODULE + "\" (expected one of "
					+ Arrays.stream(values()).map(value -> value.field).toList() + ")"));
		if (!intrinsic.params.equals(decl.paramTypes()) || intrinsic.result != decl.returnType()) {
			throw new UnsupportedOperationException(
					"rontolisp:wasm-import '" + decl.name() + "' declares \"" + MODULE + "\".\"" + decl.field()
							+ "\" as :params " + decl.paramTypes().stream().map(BoundaryType::designator).toList()
							+ " :returns " + decl.returnType().designator() + ", but it is :params "
							+ intrinsic.params.stream().map(BoundaryType::designator).toList() + " :returns "
							+ intrinsic.result.designator());
		}
		if (decl.async()) {
			throw new UnsupportedOperationException("rontolisp:wasm-import '" + decl.name() + "' declares \"" + MODULE
					+ "\".\"" + decl.field() + "\" :async; the module's own memory cannot suspend");
		}
		return intrinsic;
	}

	/**
	 * Whether a declaration needs the bump heap's helper functions, so a module whose
	 * program only declares a {@code load32} carries none of them.
	 * @param decl the parsed declaration
	 * @return whether the module must carry the allocator family
	 */
	static boolean needsArena(WasmImportCompiler.Decl decl) {
		return isIntrinsic(decl) && of(decl).arena;
	}

	/**
	 * Emits the load or store over the address and value already on the stack, as host
	 * values. Alignment is the byte hint: an out-cell is whatever address the host was
	 * handed.
	 * @param w the writer
	 * @return whether this intrinsic is one instruction ({@code false} for the arena
	 * family, the two lifts and the string fields, which each backend answers its own
	 * way)
	 */
	boolean emitAccess(WasmWriter w) {
		int opcode = switch (this) {
			case LOAD8 -> Instruction.I32_LOAD8_U;
			case LOAD32 -> Instruction.I32_LOAD;
			case LOAD64 -> Instruction.I64_LOAD;
			case STORE8 -> Instruction.I32_STORE8;
			case STORE32 -> Instruction.I32_STORE;
			case STORE64 -> Instruction.I64_STORE;
			default -> -1;
		};
		if (opcode < 0) {
			return false;
		}
		w.write(opcode, 0x00, 0x00);
		return true;
	}

	/**
	 * Emits {@link #SEARCH}'s loop, which leaves the answer on the stack as an i32: eight
	 * bytes at a time past the words not holding the needle's first byte, by the
	 * zero-byte test on {@code word ^ pattern} (about 3.5 instructions per byte, against
	 * 14 a byte at a time), then a byte-wise comparison where one does. The lowest bit
	 * the test sets is exact, so the comparison starts at the first candidate. Fewer than
	 * eight starts left go byte-wise. A word read stays inside the {@code size} bytes:
	 * its last byte is below {@code end} plus 7, and {@code end} is one past the last
	 * start.
	 * @param w the writer
	 * @param needle a local holding the needle's address
	 * @param needleSize a local holding its size
	 * @param hay a local holding the address searched
	 * @param size a local holding the size searched
	 * @param at an i32 local to use
	 * @param end an i32 local to use
	 * @param k an i32 local to use
	 * @param pattern an i64 local to use
	 * @param mask an i64 local to use
	 */
	static void emitSearch(WasmWriter w, int needle, int needleSize, int hay, int size, int at, int end, int k,
			int pattern, int mask) {
		w.write(Instruction.BLOCK, Type.I32.code());
		// An empty needle is at the start; one longer than the bytes is nowhere.
		i32(w, 0);
		get(w, needleSize);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		w.write(Instruction.DROP);
		i32(w, -1);
		get(w, size);
		get(w, needleSize);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.BR_IF, 0);
		w.write(Instruction.DROP);
		get(w, hay);
		set(w, at);
		get(w, hay);
		get(w, size);
		w.write(Instruction.I32_ADD);
		get(w, needleSize);
		w.write(Instruction.I32_SUB);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, end);
		get(w, needle);
		w.write(Instruction.I64_LOAD8_U, 0x00, 0x00);
		i64(w, ONE_PER_BYTE);
		w.write(Instruction.I64_MUL);
		set(w, pattern);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, at);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		get(w, end);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.BR_IF, 1);
		get(w, at);
		w.write(Instruction.I64_LOAD, 0x00, 0x00);
		get(w, pattern);
		w.write(Instruction.I64_XOR);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(mask);
		i64(w, ONE_PER_BYTE);
		w.write(Instruction.I64_SUB);
		get(w, mask);
		i64(w, -1);
		w.write(Instruction.I64_XOR);
		w.write(Instruction.I64_AND);
		i64(w, ONE_PER_BYTE << 7);
		w.write(Instruction.I64_AND);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(mask);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.IF, 0x40);
		get(w, at);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		set(w, at);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		get(w, at);
		get(w, mask);
		w.write(Instruction.I64_CTZ);
		i64(w, 3);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_ADD);
		set(w, at);
		w.write(Instruction.END); // the word loop
		w.write(Instruction.END);
		// A candidate, or a start past the words. None left: nowhere.
		i32(w, -1);
		get(w, at);
		get(w, end);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.DROP);
		i32(w, 0);
		set(w, k);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, k);
		get(w, needleSize);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		get(w, at);
		get(w, hay);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.BR, 4);
		w.write(Instruction.END);
		get(w, at);
		get(w, k);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		get(w, needle);
		get(w, k);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 1);
		get(w, k);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, k);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // the comparison loop
		w.write(Instruction.END);
		get(w, at);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, at);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // the scan
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	private static void get(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void set(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void i64(WasmWriter w, long value) {
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(value);
	}

	/**
	 * The parameter types.
	 * @return one boundary type per parameter
	 */
	List<BoundaryType> params() {
		return this.params;
	}

	/**
	 * The result designator.
	 * @return the boundary type of the result
	 */
	BoundaryType result() {
		return this.result;
	}

}
