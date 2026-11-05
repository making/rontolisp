package am.ik.rontolisp.codegen.wasm;

import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.wasm.Instruction;
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
	 * family, the two lifts and the two string fields, which each backend answers its own
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
