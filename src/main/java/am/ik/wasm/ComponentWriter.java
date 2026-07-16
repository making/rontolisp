package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Writer for the WebAssembly <strong>Component Model</strong> binary format.
 *
 * <p>
 * This is a language-independent encoder, a companion to {@link WasmWriter} (which only
 * emits core modules). It produces the component preamble ({@code \0asm} + version
 * {@code 0x0d}, layer {@code 0x01}) and length-prefixed component sections, and exposes
 * static encoders for the component-model constructs needed to wrap a core module as a
 * WASI 0.2 component: core-module / core-instance / component-type / alias / canonical /
 * import / export entries.
 *
 * <p>
 * All multi-byte indices and sizes use unsigned LEB128 as required by the component-model
 * binary specification. Value types ({@code s32}, {@code u64}, ...) are encoded as signed
 * LEB128 of their primitive code so the negative single-byte form is produced.
 */
public final class ComponentWriter {

	/** Component section id: core module (embeds a full core module). */
	public static final int SEC_CORE_MODULE = 1;

	/** Component section id: core instance. */
	public static final int SEC_CORE_INSTANCE = 2;

	/** Component section id: core type. */
	public static final int SEC_CORE_TYPE = 3;

	/** Component section id: nested component. */
	public static final int SEC_COMPONENT = 4;

	/** Component section id: component instance. */
	public static final int SEC_INSTANCE = 5;

	/** Component section id: alias. */
	public static final int SEC_ALIAS = 6;

	/** Component section id: component type. */
	public static final int SEC_TYPE = 7;

	/** Component section id: canonical function. */
	public static final int SEC_CANON = 8;

	/** Component section id: start. */
	public static final int SEC_START = 9;

	/** Component section id: import. */
	public static final int SEC_IMPORT = 10;

	/** Component section id: export. */
	public static final int SEC_EXPORT = 11;

	// Primitive value type codes (encoded as signed LEB128 to get the negative form).
	/** Primitive value type code for {@code bool}. */
	public static final int VT_BOOL = 0x7f;

	/** Primitive value type code for {@code s8}. */
	public static final int VT_S8 = 0x7e;

	/** Primitive value type code for {@code s16}. */
	public static final int VT_S16 = 0x7c;

	/** Primitive value type code for {@code u16}. */
	public static final int VT_U16 = 0x7b;

	/** Primitive value type code for {@code f32}. */
	public static final int VT_F32 = 0x76;

	/** Primitive value type code for {@code char}. */
	public static final int VT_CHAR = 0x74;

	/**
	 * Primitive value type code for {@code u8} (the element type of {@code stream<u8>}).
	 */
	public static final int VT_U8 = 0x7d;

	/** Primitive value type code for {@code s32}. */
	public static final int VT_S32 = 0x7a;

	/** Primitive value type code for {@code u32}. */
	public static final int VT_U32 = 0x79;

	/** Primitive value type code for {@code s64}. */
	public static final int VT_S64 = 0x78;

	/** Primitive value type code for {@code u64}. */
	public static final int VT_U64 = 0x77;

	/** Primitive value type code for {@code f64}. */
	public static final int VT_F64 = 0x75;

	/** Primitive value type code for {@code string}. */
	public static final int VT_STRING = 0x73;

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final WasmWriter writer = new WasmWriter(this.out);

	/**
	 * Create a new component writer and emit the component preamble.
	 */
	public ComponentWriter() {
		// magic "\0asm" then version 0x000d (little-endian u16) + layer 0x0001
		this.writer.write("\0asm").write(new byte[] { 0x0d, 0x00, 0x01, 0x00 });
	}

	/**
	 * Write a component section: section id, then the byte length, then the payload.
	 * @param sectionId the component section id (see {@code SEC_*} constants)
	 * @param payload the already-encoded section payload (begins with the entry count for
	 * vector sections)
	 * @return this instance for chaining
	 */
	public ComponentWriter rawSection(int sectionId, byte[] payload) {
		this.writer.write(sectionId).writeUnsignedLeb128(payload.length).write(payload);
		return this;
	}

	/**
	 * Write raw, already-encoded bytes verbatim (e.g. a block of pre-built component
	 * sections).
	 * @param bytes the bytes to append
	 * @return this instance for chaining
	 */
	public ComponentWriter writeRaw(byte[] bytes) {
		this.writer.write((Object) bytes);
		return this;
	}

	/**
	 * Return the assembled component binary.
	 * @return the component bytes
	 */
	public byte[] toByteArray() {
		return this.out.toByteArray();
	}

	// --- encoding helpers (language-independent component-model primitives) ---

	/**
	 * Encode bytes by running the given consumer against a fresh {@link WasmWriter}.
	 * @param consumer a consumer that writes the bytes
	 * @return the encoded bytes
	 */
	public static byte[] enc(Consumer<WasmWriter> consumer) {
		final ByteArrayOutputStream buf = new ByteArrayOutputStream();
		consumer.accept(new WasmWriter(buf));
		return buf.toByteArray();
	}

	/**
	 * Encode a vector: the entry count as unsigned LEB128 followed by the concatenated
	 * entries.
	 * @param entries the pre-encoded entries
	 * @return the encoded vector
	 */
	public static byte[] vec(List<byte[]> entries) {
		return enc(w -> {
			w.writeUnsignedLeb128(entries.size());
			entries.forEach(e -> w.write((Object) e));
		});
	}

	/**
	 * Write an import/export declaration name: a {@code 0x00} kind byte, then the
	 * length-prefixed UTF-8 bytes.
	 * @param w the writer
	 * @param name the name
	 */
	public static void declName(WasmWriter w, String name) {
		w.write(0x00).writeUnsignedLeb128(name.length()).write(name);
	}

	/**
	 * Write a plain name (length-prefixed UTF-8), used by aliases, instantiation
	 * arguments and core instance exports.
	 * @param w the writer
	 * @param name the name
	 */
	public static void plainName(WasmWriter w, String name) {
		w.writeUnsignedLeb128(name.length()).write(name);
	}

	/**
	 * Encode a component function type with no parameters and a single primitive result.
	 * @param resultValType the result primitive value type code (e.g. {@link #VT_U64})
	 * @return the encoded function type
	 */
	public static byte[] funcTypeResult(int resultValType) {
		// 0x40 functype, empty param vector, result-list 0x00 then the value type
		return enc(w -> w.write(0x40).writeUnsignedLeb128(0).write(0x00).writeSignedLeb128(resultValType - 0x80));
	}

	/**
	 * Encode a <strong>synchronous</strong> component function type with the given named
	 * parameters (each a primitive value type) and an optional single primitive result
	 * &mdash; the shape of a pure-compute scalar function export.
	 * @param paramNames the parameter names, in order (must be valid component-model
	 * labels)
	 * @param paramValTypes the primitive value type code of each parameter (e.g.
	 * {@link #VT_S32})
	 * @param resultValType the result primitive value type code, or {@code null} for no
	 * result
	 * @return the encoded function type
	 */
	public static byte[] funcTypeScalars(List<String> paramNames, List<Integer> paramValTypes,
			@Nullable Integer resultValType) {
		return enc(w -> {
			w.write(0x40); // functype
			w.writeUnsignedLeb128(paramNames.size());
			for (int i = 0; i < paramNames.size(); i++) {
				plainName(w, paramNames.get(i));
				w.writeSignedLeb128(paramValTypes.get(i) - 0x80);
			}
			if (resultValType == null) {
				// result list: 0x01 (named-results form) then an empty vec (no results)
				w.write(0x01).writeUnsignedLeb128(0);
			}
			else {
				w.write(0x00).writeSignedLeb128(resultValType - 0x80);
			}
		});
	}

	/**
	 * Encode an instance type that exports a single function under {@code exportName}
	 * with no parameters and a single primitive result.
	 * @param exportName the exported function name
	 * @param resultValType the result primitive value type code
	 * @return the encoded instance type
	 */
	public static byte[] instanceTypeWithFunc(String exportName, int resultValType) {
		return enc(w -> {
			w.write(0x42); // instance type
			w.writeUnsignedLeb128(2); // two instance declarations
			// decl 0: define a function type (instance-local type index 0)
			w.write(0x01).write((Object) funcTypeResult(resultValType));
			// decl 1: export it
			w.write(0x04);
			declName(w, exportName);
			w.write(0x01).writeUnsignedLeb128(0); // externdesc: func, type index 0
		});
	}

	/**
	 * Encode a component import of an instance type.
	 * @param importName the import name (e.g. {@code "wasi:random/random@0.2.0"})
	 * @param instanceTypeIndex the component type index of the imported instance type
	 * @return the encoded import entry
	 */
	public static byte[] importInstance(String importName, int instanceTypeIndex) {
		return enc(w -> {
			declName(w, importName);
			w.write(0x05).writeUnsignedLeb128(instanceTypeIndex); // externdesc: instance
		});
	}

	/**
	 * Encode an alias that projects a function export out of an imported component
	 * instance into the component function index space.
	 * @param instanceIndex the component instance index
	 * @param exportName the function export name
	 * @return the encoded alias entry
	 */
	public static byte[] aliasInstanceFunc(int instanceIndex, String exportName) {
		return enc(w -> {
			w.write(0x01); // sort: func
			w.write(0x00).writeUnsignedLeb128(instanceIndex); // target: instance export
			plainName(w, exportName);
		});
	}

	/**
	 * Encode an alias that projects a type export out of an imported component instance
	 * into the component type index space (used to obtain a resource type for
	 * {@code canon resource.drop}).
	 * @param instanceIndex the component instance index
	 * @param exportName the type export name (e.g. {@code "output-stream"})
	 * @return the encoded alias entry
	 */
	public static byte[] aliasInstanceType(int instanceIndex, String exportName) {
		return enc(w -> {
			w.write(0x03); // sort: type
			w.write(0x00).writeUnsignedLeb128(instanceIndex); // target: instance export
			plainName(w, exportName);
		});
	}

	/**
	 * Encode an alias that projects a function export out of a core instance into the
	 * core function index space.
	 * @param coreInstanceIndex the core instance index
	 * @param exportName the core function export name
	 * @return the encoded alias entry
	 */
	public static byte[] aliasCoreFunc(int coreInstanceIndex, String exportName) {
		return enc(w -> {
			w.write(0x00).write(0x00); // sort: core func
			w.write(0x01).writeUnsignedLeb128(coreInstanceIndex); // target: core instance
																	// export
			plainName(w, exportName);
		});
	}

	/**
	 * Encode an alias that projects a memory export out of a core instance into the core
	 * memory index space.
	 * @param coreInstanceIndex the core instance index
	 * @param exportName the core memory export name
	 * @return the encoded alias entry
	 */
	public static byte[] aliasCoreMemory(int coreInstanceIndex, String exportName) {
		return enc(w -> {
			w.write(0x00).write(0x02); // sort: core memory
			w.write(0x01).writeUnsignedLeb128(coreInstanceIndex); // target: core instance
																	// export
			plainName(w, exportName);
		});
	}

	/**
	 * Encode an alias that projects a table export out of a core instance into the core
	 * table index space (used to reach a funcref-table shim's {@code $imports} table for
	 * the fixup module of the wit-component adapter pattern).
	 * @param coreInstanceIndex the core instance index
	 * @param exportName the core table export name
	 * @return the encoded alias entry
	 */
	public static byte[] aliasCoreTable(int coreInstanceIndex, String exportName) {
		return enc(w -> {
			w.write(0x00).write(0x01); // sort: core table
			w.write(0x01).writeUnsignedLeb128(coreInstanceIndex); // target: core instance
																	// export
			plainName(w, exportName);
		});
	}

	/**
	 * Encode a {@code canon lower} of a component function into a core function, with no
	 * canonical options.
	 * @param funcIndex the component function index to lower
	 * @return the encoded canonical entry
	 */
	public static byte[] canonLower(int funcIndex) {
		return enc(w -> w.write(0x01).write(0x00).writeUnsignedLeb128(funcIndex).writeUnsignedLeb128(0));
	}

	/**
	 * Encode a {@code canon lower} of a component function into a core function,
	 * supplying the canonical memory and reallocation function (required when the lowered
	 * function has a list/string parameter or an indirect result).
	 * @param funcIndex the component function index to lower
	 * @param memoryIndex the core memory index used by the canonical ABI
	 * @param reallocFuncIndex the core function index of {@code cabi_realloc}
	 * @return the encoded canonical entry
	 */
	public static byte[] canonLower(int funcIndex, int memoryIndex, int reallocFuncIndex) {
		return enc(w -> w.write(0x01)
			.write(0x00)
			.writeUnsignedLeb128(funcIndex)
			.writeUnsignedLeb128(2) // two canonical options
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex) // memory
			.write(0x04)
			.writeUnsignedLeb128(reallocFuncIndex)); // realloc
	}

	/**
	 * Encode a {@code canon lower} of a component function into a core function,
	 * supplying only the canonical memory option (required when the lowered function
	 * returns a record indirectly through a caller-provided return pointer, e.g.
	 * {@code wall-clock.now}).
	 * @param funcIndex the component function index to lower
	 * @param memoryIndex the core memory index used by the canonical ABI
	 * @return the encoded canonical entry
	 */
	public static byte[] canonLowerMemory(int funcIndex, int memoryIndex) {
		return enc(w -> w.write(0x01)
			.write(0x00)
			.writeUnsignedLeb128(funcIndex)
			.writeUnsignedLeb128(1) // one canonical option
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex)); // memory
	}

	/**
	 * Encode a {@code canon lower} with the canonical memory and UTF-8 string-encoding
	 * options (required when the lowered function has a {@code string} parameter, e.g.
	 * {@code descriptor.open-at}).
	 * @param funcIndex the component function index to lower
	 * @param memoryIndex the core memory index used by the canonical ABI
	 * @return the encoded canonical entry
	 */
	public static byte[] canonLowerMemoryUtf8(int funcIndex, int memoryIndex) {
		return enc(w -> w.write(0x01)
			.write(0x00)
			.writeUnsignedLeb128(funcIndex)
			.writeUnsignedLeb128(2) // two canonical options: memory, string-encoding
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex) // memory
			.write(0x00)); // string-encoding utf8
	}

	/**
	 * Encode a {@code canon lower} with the canonical memory, reallocation and UTF-8
	 * string-encoding options (required when the lowered function returns lists of
	 * strings, e.g. {@code get-environment} / {@code preopens.get-directories}).
	 * @param funcIndex the component function index to lower
	 * @param memoryIndex the core memory index used by the canonical ABI
	 * @param reallocFuncIndex the core function index of {@code cabi_realloc}
	 * @return the encoded canonical entry
	 */
	public static byte[] canonLowerMemoryReallocUtf8(int funcIndex, int memoryIndex, int reallocFuncIndex) {
		return enc(w -> w.write(0x01)
			.write(0x00)
			.writeUnsignedLeb128(funcIndex)
			.writeUnsignedLeb128(3) // three canonical options: memory, realloc,
									// string-encoding
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex) // memory
			.write(0x04)
			.writeUnsignedLeb128(reallocFuncIndex) // realloc
			.write(0x00)); // string-encoding utf8
	}

	/**
	 * Encode a {@code canon resource.drop} for the given resource type, producing a core
	 * function that drops a handle of that resource.
	 * @param typeIndex the component type index of the resource
	 * @return the encoded canonical entry
	 */
	public static byte[] canonResourceDrop(int typeIndex) {
		return enc(w -> w.write(0x03).writeUnsignedLeb128(typeIndex));
	}

	/**
	 * Encode a {@code canon lift} of a core function into a component function, with no
	 * canonical options.
	 * @param coreFuncIndex the core function index to lift
	 * @param typeIndex the component function type index
	 * @return the encoded canonical entry
	 */
	public static byte[] canonLift(int coreFuncIndex, int typeIndex) {
		return enc(w -> w.write(0x00)
			.write(0x00)
			.writeUnsignedLeb128(coreFuncIndex)
			.writeUnsignedLeb128(0)
			.writeUnsignedLeb128(typeIndex));
	}

	/**
	 * Encode a {@code canon lift} with the canonical memory, reallocation, UTF-8
	 * string-encoding and post-return options (required when the lifted function has a
	 * {@code string} parameter or result: the host lowers string arguments into the
	 * callee's memory via {@code cabi_realloc}, reads an indirect string result out of
	 * it, then calls the post-return function so the callee can reclaim the per-call
	 * allocations). The option order (memory, realloc, string-encoding, post-return)
	 * matches the canonical byte sequence {@code wasm-tools} emits.
	 * @param coreFuncIndex the core function index to lift
	 * @param typeIndex the component function type index
	 * @param memoryIndex the core memory index used by the canonical ABI
	 * @param reallocFuncIndex the core function index of {@code cabi_realloc}
	 * @param postReturnFuncIndex the core function index of the post-return function (its
	 * parameters are the lifted core function's flat results)
	 * @return the encoded canonical entry
	 */
	public static byte[] canonLiftMemoryReallocUtf8PostReturn(int coreFuncIndex, int typeIndex, int memoryIndex,
			int reallocFuncIndex, int postReturnFuncIndex) {
		return enc(w -> w.write(0x00)
			.write(0x00)
			.writeUnsignedLeb128(coreFuncIndex)
			.writeUnsignedLeb128(4) // four canonical options
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex) // memory
			.write(0x04)
			.writeUnsignedLeb128(reallocFuncIndex) // realloc
			.write(0x00) // string-encoding utf8
			.write(0x05)
			.writeUnsignedLeb128(postReturnFuncIndex) // post-return
			.writeUnsignedLeb128(typeIndex));
	}

	/**
	 * Encode a core instance synthesized from a single function export.
	 * @param exportName the core function export name
	 * @param coreFuncIndex the core function index being exported
	 * @return the encoded core instance entry
	 */
	public static byte[] coreInstanceFromFunc(String exportName, int coreFuncIndex) {
		return coreInstanceFromFuncs(List.of(exportName), List.of(coreFuncIndex));
	}

	/**
	 * Encode a core instance synthesized from one or more function exports.
	 * @param exportNames the core function export names (in order)
	 * @param coreFuncIndices the core function index exported under each name
	 * @return the encoded core instance entry
	 */
	public static byte[] coreInstanceFromFuncs(List<String> exportNames, List<Integer> coreFuncIndices) {
		return enc(w -> {
			w.write(0x01); // from-exports
			w.writeUnsignedLeb128(exportNames.size());
			for (int i = 0; i < exportNames.size(); i++) {
				plainName(w, exportNames.get(i));
				w.write(0x00).writeUnsignedLeb128(coreFuncIndices.get(i)); // core sort
																			// func, index
			}
		});
	}

	/** Core sort code for a function, as used by {@link #coreInstanceFromExports}. */
	public static final int CORE_SORT_FUNC = 0x00;

	/** Core sort code for a table, as used by {@link #coreInstanceFromExports}. */
	public static final int CORE_SORT_TABLE = 0x01;

	/**
	 * Encode a core instance synthesized from exports of mixed core sorts (e.g. a table
	 * plus a function, the argument instance of a wit-component fixup module).
	 * @param exportNames the core export names (in order)
	 * @param coreSorts the core sort code of each export ({@link #CORE_SORT_FUNC} /
	 * {@link #CORE_SORT_TABLE} / ...)
	 * @param indices the index of each export within its sort's core index space
	 * @return the encoded core instance entry
	 */
	public static byte[] coreInstanceFromExports(List<String> exportNames, List<Integer> coreSorts,
			List<Integer> indices) {
		return enc(w -> {
			w.write(0x01); // from-exports
			w.writeUnsignedLeb128(exportNames.size());
			for (int i = 0; i < exportNames.size(); i++) {
				plainName(w, exportNames.get(i));
				w.write(coreSorts.get(i)).writeUnsignedLeb128(indices.get(i)); // core
																				// sort,
																				// index
			}
		});
	}

	/**
	 * Encode a core instance produced by instantiating a core module, supplying named
	 * core instance arguments.
	 * @param moduleIndex the core module index
	 * @param argNames the import names to satisfy (in order)
	 * @param argInstanceIndices the core instance index supplied for each argument
	 * @return the encoded core instance entry
	 */
	public static byte[] coreInstanceInstantiate(int moduleIndex, List<String> argNames,
			List<Integer> argInstanceIndices) {
		return enc(w -> {
			w.write(0x00); // instantiate
			w.writeUnsignedLeb128(moduleIndex);
			w.writeUnsignedLeb128(argNames.size());
			for (int i = 0; i < argNames.size(); i++) {
				plainName(w, argNames.get(i));
				w.write(0x12).writeUnsignedLeb128(argInstanceIndices.get(i)); // core
																				// sort:
																				// instance
			}
		});
	}

	/**
	 * Encode a component export of a function.
	 * @param exportName the export name
	 * @param funcIndex the component function index
	 * @return the encoded export entry
	 */
	public static byte[] exportFunc(String exportName, int funcIndex) {
		return enc(w -> {
			declName(w, exportName);
			w.write(0x01).writeUnsignedLeb128(funcIndex); // sortidx: func
			w.write(0x00); // no type ascription
		});
	}

	/**
	 * Encode the empty result type {@code result<_, _>} (no ok payload, no err payload).
	 * @return the encoded defined value type
	 */
	public static byte[] definedResultVoid() {
		return enc(w -> w.write(0x6a).write(0x00).write(0x00));
	}

	/**
	 * Encode a component function type with the given named parameters (each a defined
	 * value type referenced by component type index) and <strong>no</strong> result, e.g.
	 * {@code wasi:http/incoming-handler}'s
	 * {@code handle(request: incoming-request, response-out: response-outparam)}.
	 * @param paramNames the parameter names, in order
	 * @param paramTypeIndices the component type index of each parameter's value type
	 * @return the encoded function type
	 */
	public static byte[] funcTypeParamsNoResult(java.util.List<String> paramNames,
			java.util.List<Integer> paramTypeIndices) {
		return enc(w -> {
			w.write(0x40); // functype
			w.writeUnsignedLeb128(paramNames.size());
			for (int i = 0; i < paramNames.size(); i++) {
				plainName(w, paramNames.get(i));
				w.writeSignedLeb128(paramTypeIndices.get(i)); // valtype = component type
																// index
			}
			// result list: 0x01 (named-results form) then an empty vec (no results).
			w.write(0x01).writeUnsignedLeb128(0);
		});
	}

	/**
	 * Encode a component function type with no parameters whose single result is a
	 * defined type referenced by index (e.g. {@code result<_, _>}).
	 * @param resultTypeIndex the component type index of the result type
	 * @return the encoded function type
	 */
	public static byte[] funcTypeResultType(int resultTypeIndex) {
		// 0x40 functype, empty params, result-list 0x00 then the type index as a positive
		// value type (signed LEB128 of the index)
		return enc(w -> w.write(0x40).writeUnsignedLeb128(0).write(0x00).writeSignedLeb128(resultTypeIndex));
	}

	/**
	 * Encode a component instance synthesized from a single function export.
	 * @param exportName the function export name
	 * @param funcIndex the component function index being exported
	 * @return the encoded component instance entry
	 */
	public static byte[] componentInstanceFromFunc(String exportName, int funcIndex) {
		return enc(w -> {
			w.write(0x01); // from-exports
			w.writeUnsignedLeb128(1); // one export
			declName(w, exportName);
			w.write(0x01).writeUnsignedLeb128(funcIndex); // sortidx: func
		});
	}

	/**
	 * Encode a component export of an instance (e.g. the {@code wasi:cli/run@0.2.0}
	 * interface).
	 * @param exportName the export name
	 * @param instanceIndex the component instance index
	 * @return the encoded export entry
	 */
	public static byte[] exportInstance(String exportName, int instanceIndex) {
		return enc(w -> {
			declName(w, exportName);
			w.write(0x05).writeUnsignedLeb128(instanceIndex); // sortidx: instance
			w.write(0x00); // no type ascription
		});
	}

	// --- user-defined WIT types + instance-type imports (component imports)
	//
	// Generic encoders for the defined-value-type grammar of the component-model binary
	// format, byte-validated against `wasm-tools dump` of a wasm-tools-built reference
	// component importing wasi:keyvalue/store (pinned in ComponentWriterTest). A
	// "valtype" operand is passed pre-encoded (see valTypePrim / valTypeIndex) because
	// it is either a negative signed-LEB primitive code or a non-negative type index.

	/**
	 * Encode a value-type operand referring to a primitive type (e.g.
	 * {@link #VT_STRING}).
	 * @param primCode the primitive value type code
	 * @return the encoded valtype operand
	 */
	public static byte[] valTypePrim(int primCode) {
		return enc(w -> w.writeSignedLeb128(primCode - 0x80));
	}

	/**
	 * Encode a value-type operand referring to a defined type by index.
	 * @param typeIndex the type index (local to the enclosing instance type, or a
	 * component type index at the outer level)
	 * @return the encoded valtype operand
	 */
	public static byte[] valTypeIndex(int typeIndex) {
		return enc(w -> w.writeSignedLeb128(typeIndex));
	}

	/**
	 * Encode the defined value type {@code record} (tag {@code 0x72}).
	 * @param fieldNames the field names, in order
	 * @param fieldTypes the encoded valtype of each field
	 * @return the encoded defined value type
	 */
	public static byte[] definedRecordOf(List<String> fieldNames, List<byte[]> fieldTypes) {
		return enc(w -> {
			w.write(0x72).writeUnsignedLeb128(fieldNames.size());
			for (int i = 0; i < fieldNames.size(); i++) {
				plainName(w, fieldNames.get(i));
				w.write((Object) fieldTypes.get(i));
			}
		});
	}

	/**
	 * Encode the defined value type {@code variant} (tag {@code 0x71}).
	 * @param caseNames the case names, in order
	 * @param casePayloads the encoded valtype of each case's payload, or {@code null} for
	 * a payload-less case
	 * @return the encoded defined value type
	 */
	public static byte[] definedVariantOf(List<String> caseNames, List<byte @Nullable []> casePayloads) {
		return enc(w -> {
			w.write(0x71).writeUnsignedLeb128(caseNames.size());
			for (int i = 0; i < caseNames.size(); i++) {
				plainName(w, caseNames.get(i));
				byte[] payload = casePayloads.get(i);
				if (payload == null) {
					w.write(0x00);
				}
				else {
					w.write(0x01).write((Object) payload);
				}
				w.write(0x00); // refines: none
			}
		});
	}

	/**
	 * Encode the defined value type {@code list<T>} (tag {@code 0x70}) over an arbitrary
	 * element valtype.
	 * @param elementType the encoded element valtype
	 * @return the encoded defined value type
	 */
	public static byte[] definedListOf(byte[] elementType) {
		return enc(w -> w.write(0x70).write((Object) elementType));
	}

	/**
	 * Encode the defined value type {@code tuple} (tag {@code 0x6f}).
	 * @param elementTypes the encoded valtype of each element, in order
	 * @return the encoded defined value type
	 */
	public static byte[] definedTupleOf(List<byte[]> elementTypes) {
		return enc(w -> {
			w.write(0x6f).writeUnsignedLeb128(elementTypes.size());
			elementTypes.forEach(t -> w.write((Object) t));
		});
	}

	/**
	 * Encode the defined value type {@code flags} (tag {@code 0x6e}).
	 * @param names the flag names, in order
	 * @return the encoded defined value type
	 */
	public static byte[] definedFlagsOf(List<String> names) {
		return enc(w -> {
			w.write(0x6e).writeUnsignedLeb128(names.size());
			names.forEach(n -> plainName(w, n));
		});
	}

	/**
	 * Encode the defined value type {@code enum} (tag {@code 0x6d}).
	 * @param names the enum case names, in order
	 * @return the encoded defined value type
	 */
	public static byte[] definedEnumOf(List<String> names) {
		return enc(w -> {
			w.write(0x6d).writeUnsignedLeb128(names.size());
			names.forEach(n -> plainName(w, n));
		});
	}

	/**
	 * Encode the defined value type {@code option<T>} (tag {@code 0x6b}) over an
	 * arbitrary payload valtype.
	 * @param payloadType the encoded payload valtype
	 * @return the encoded defined value type
	 */
	public static byte[] definedOptionOf(byte[] payloadType) {
		return enc(w -> w.write(0x6b).write((Object) payloadType));
	}

	/**
	 * Encode the defined value type {@code result<T, E>} (tag {@code 0x6a}) with
	 * arbitrary (or absent) payload valtypes.
	 * @param okType the encoded ok-arm valtype, or {@code null} for a payload-less ok arm
	 * @param errType the encoded error-arm valtype, or {@code null} for a payload-less
	 * error arm
	 * @return the encoded defined value type
	 */
	public static byte[] definedResultOf(byte @Nullable [] okType, byte @Nullable [] errType) {
		return enc(w -> {
			w.write(0x6a);
			if (okType == null) {
				w.write(0x00);
			}
			else {
				w.write(0x01).write((Object) okType);
			}
			if (errType == null) {
				w.write(0x00);
			}
			else {
				w.write(0x01).write((Object) errType);
			}
		});
	}

	/**
	 * Encode the defined value type {@code borrow<resource>} (tag {@code 0x68}).
	 * @param resourceTypeIndex the type index of the resource
	 * @return the encoded defined value type
	 */
	public static byte[] definedBorrow(int resourceTypeIndex) {
		return enc(w -> w.write(0x68).writeUnsignedLeb128(resourceTypeIndex));
	}

	/**
	 * Encode a component function type with named parameters of arbitrary valtypes and an
	 * optional single result of an arbitrary valtype &mdash; the general form of
	 * {@link #funcTypeScalars}, used inside imported-instance types.
	 * @param paramNames the parameter names, in order
	 * @param paramTypes the encoded valtype of each parameter
	 * @param resultType the encoded result valtype, or {@code null} for no result
	 * @return the encoded function type
	 */
	public static byte[] funcTypeOf(List<String> paramNames, List<byte[]> paramTypes, byte @Nullable [] resultType) {
		return enc(w -> {
			w.write(0x40);
			w.writeUnsignedLeb128(paramNames.size());
			for (int i = 0; i < paramNames.size(); i++) {
				plainName(w, paramNames.get(i));
				w.write((Object) paramTypes.get(i));
			}
			if (resultType == null) {
				w.write(0x01).writeUnsignedLeb128(0);
			}
			else {
				w.write(0x00).write((Object) resultType);
			}
		});
	}

	/**
	 * Encode an instance-type declaration that defines a type (tag {@code 0x01}). Each
	 * such declaration appends one entry to the instance type's local type index space.
	 * @param definedType the encoded defined type (or function type)
	 * @return the encoded instance-type declaration
	 */
	public static byte[] instanceDeclType(byte[] definedType) {
		return enc(w -> w.write(0x01).write((Object) definedType));
	}

	/**
	 * Encode an instance-type declaration that aliases a type from an <strong>enclosing
	 * scope</strong> into the instance type's local type index space (tag {@code 0x02} =
	 * alias, sort {@code 0x03} = type, target {@code 0x02} = outer). Like
	 * {@link #instanceDeclType}, it appends one entry to the local type index space.
	 * <p>
	 * This is how a type an interface {@code use}s from ANOTHER interface is referred to:
	 * the enclosing component first projects it out of the defining instance with
	 * {@link #aliasInstanceType}, and the dependent instance type then points at that
	 * component type index. Structurally re-declaring it instead is wrong for a
	 * {@code resource}, which is NOMINAL &mdash; the two would be different types, the
	 * host could not satisfy the import, and the handles would index different tables.
	 * @param count the number of enclosing scopes to skip ({@code 1} = the component
	 * containing this instance type)
	 * @param outerTypeIndex the type index in that enclosing scope
	 * @return the encoded instance-type declaration
	 */
	public static byte[] instanceDeclAliasOuterType(int count, int outerTypeIndex) {
		return enc(w -> {
			w.write(0x02); // instance decl: alias
			w.write(0x03); // sort: type
			w.write(0x02); // target: outer
			w.writeUnsignedLeb128(count);
			w.writeUnsignedLeb128(outerTypeIndex);
		});
	}

	/**
	 * Encode an instance-type declaration exporting a <strong>resource</strong> type
	 * (externdesc {@code type} with the {@code sub resource} bound). The export appends
	 * one entry to the local type index space &mdash; the resource type itself.
	 * @param name the export name (e.g. {@code "bucket"})
	 * @return the encoded instance-type declaration
	 */
	public static byte[] instanceDeclExportResource(String name) {
		return enc(w -> {
			w.write(0x04);
			declName(w, name);
			w.write(0x03).write(0x01); // externdesc: type, bound: sub resource
		});
	}

	/**
	 * Encode an instance-type declaration exporting a previously declared type under a
	 * name (externdesc {@code type} with an {@code eq} bound). The export appends one
	 * entry to the local type index space &mdash; the exported alias.
	 * @param name the export name (e.g. {@code "error"})
	 * @param localTypeIndex the local type index of the declared type
	 * @return the encoded instance-type declaration
	 */
	public static byte[] instanceDeclExportTypeEq(String name, int localTypeIndex) {
		return enc(w -> {
			w.write(0x04);
			declName(w, name);
			w.write(0x03).write(0x00).writeUnsignedLeb128(localTypeIndex);
		});
	}

	/**
	 * Encode an instance-type declaration exporting a function of a previously declared
	 * function type. Function exports do <strong>not</strong> append to the local type
	 * index space.
	 * @param name the export name (e.g. {@code "[method]bucket.get"})
	 * @param localFuncTypeIndex the local type index of the function type
	 * @return the encoded instance-type declaration
	 */
	public static byte[] instanceDeclExportFunc(String name, int localFuncTypeIndex) {
		return enc(w -> {
			w.write(0x04);
			declName(w, name);
			w.write(0x01).writeUnsignedLeb128(localFuncTypeIndex);
		});
	}

	/**
	 * Encode an instance type (tag {@code 0x42}) from its declarations.
	 * @param decls the encoded instance-type declarations, in order
	 * @return the encoded instance type
	 */
	public static byte[] instanceTypeOf(List<byte[]> decls) {
		return enc(w -> {
			w.write(0x42);
			w.writeUnsignedLeb128(decls.size());
			decls.forEach(d -> w.write((Object) d));
		});
	}

	// --- async canonical ABI (WASI 0.3 / Preview 3) ---------------------------------
	//
	// In WASI 0.3 the wasi:io package is gone and I/O is expressed with the built-in
	// component-model types stream<u8> / future<T>, driven by the async canonical
	// built-ins below. All opcodes and option encodings here are golden values captured
	// from `wasm-tools dump` of a component validated with
	// `wasm-tools validate -f component-model -f cm-async -f cm-async-stackful
	// -f cm-more-async-builtins` and executed with the matching `wasmtime run -W` flags;
	// they are pinned by ComponentWriterTest. These primitives are intentionally general
	// (not tied to the preview1 adapter) so they can be reused for future language-level
	// async (future/stream as rontolisp values).

	/**
	 * Encode the defined value type {@code stream<elem>} (component type tag
	 * {@code 0x66}).
	 * @param elemValType the element primitive value type code (e.g. {@link #VT_U8})
	 * @return the encoded defined value type
	 */
	public static byte[] definedStream(int elemValType) {
		return enc(w -> w.write(0x66).write(0x01).writeSignedLeb128(elemValType - 0x80));
	}

	/**
	 * Encode the defined value type {@code stream<elem>} where the element is a defined
	 * type referenced by index (e.g. {@code own<tcp-socket>} for the wasi:sockets accept
	 * stream) rather than a primitive value type.
	 * @param elemTypeIndex the component type index of the element type
	 * @return the encoded defined value type
	 */
	public static byte[] definedStreamOfType(int elemTypeIndex) {
		return enc(w -> w.write(0x66).write(0x01).writeSignedLeb128(elemTypeIndex));
	}

	/**
	 * Encode a type-section entry that IS a bare primitive value type (the
	 * {@code defvaltype ::= primvaltype} production), so a container that references its
	 * payload by index -- {@code future<u8>}, say -- can point at a primitive.
	 * @param primCode the primitive value type code (e.g. {@link #VT_U8})
	 * @return the encoded defined value type
	 */
	public static byte[] definedPrim(int primCode) {
		return enc(w -> w.writeSignedLeb128(primCode - 0x80));
	}

	// --- task/subtask/waitable-set canonical built-ins (component-model async ABI) ---
	//
	// Byte encodings derived empirically from `wasm-tools parse` + `dump` of a
	// hand-written reference (the same method as the stream/future built-ins above) and
	// pinned by ComponentWriterTest. `task.return` is what lets a stackful async EXPORT
	// deliver its result mid-task and keep running (the WASI 0.3 replacement for
	// wasi:http 0.2's response-outparam.set); the waitable-set trio is what awaits an
	// async-LOWERED import call's completion (its core call returns a subtask rather
	// than blocking).

	/**
	 * Encode {@code canon task.return} for a task with no result (tag {@code 0x09},
	 * result-list {@code none}).
	 * @return the encoded canonical function
	 */
	public static byte[] canonTaskReturnVoid() {
		return enc(w -> w.write(0x09).write(0x01).write(0x00).writeUnsignedLeb128(0));
	}

	/**
	 * Encode {@code canon task.return} whose result is a defined type referenced by
	 * index; the core signature takes the result's flattened values.
	 * @param resultTypeIndex the component type index of the task's result type
	 * @return the encoded canonical function
	 */
	public static byte[] canonTaskReturnType(int resultTypeIndex) {
		return enc(w -> w.write(0x09).write(0x00).writeSignedLeb128(resultTypeIndex).writeUnsignedLeb128(0));
	}

	/**
	 * Encode {@code canon task.return} with the canonical memory / realloc / UTF-8
	 * options, for a result whose flattening spills to memory or stages string bytes.
	 * @param resultTypeIndex the component type index of the task's result type
	 * @param memoryIndex the canonical memory index
	 * @param reallocFuncIndex the canonical realloc core function index
	 * @return the encoded canonical function
	 */
	public static byte[] canonTaskReturnTypeMemoryReallocUtf8(int resultTypeIndex, int memoryIndex,
			int reallocFuncIndex) {
		return enc(w -> w.write(0x09)
			.write(0x00)
			.writeSignedLeb128(resultTypeIndex)
			.writeUnsignedLeb128(3)
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex)
			.write(0x04)
			.writeUnsignedLeb128(reallocFuncIndex)
			.write(0x00));
	}

	/**
	 * Encode {@code canon waitable-set.new} (tag {@code 0x1f}); core signature
	 * {@code [] -> [i32 set]}.
	 * @return the encoded canonical function
	 */
	public static byte[] canonWaitableSetNew() {
		return enc(w -> w.write(0x1f));
	}

	/**
	 * Encode {@code canon waitable.join} (tag {@code 0x23}); core signature
	 * {@code [i32 waitable, i32 set] -> []} (set 0 = leave the current set).
	 * @return the encoded canonical function
	 */
	public static byte[] canonWaitableJoin() {
		return enc(w -> w.write(0x23));
	}

	/**
	 * Encode the blocking {@code canon waitable-set.wait} (tag {@code 0x20}); core
	 * signature {@code [i32 set, i32 payload-ptr] -> [i32 event-code]}, the event's two
	 * payload words written at the pointer.
	 * @param memoryIndex the canonical memory index the payload is written into
	 * @return the encoded canonical function
	 */
	public static byte[] canonWaitableSetWait(int memoryIndex) {
		return enc(w -> w.write(0x20).write(0x00).writeUnsignedLeb128(memoryIndex));
	}

	/**
	 * Encode {@code canon waitable-set.drop} (tag {@code 0x22}); core signature
	 * {@code [i32 set] -> []}.
	 * @return the encoded canonical function
	 */
	public static byte[] canonWaitableSetDrop() {
		return enc(w -> w.write(0x22));
	}

	/**
	 * Encode {@code canon subtask.drop} (tag {@code 0x0d}); core signature
	 * {@code [i32 subtask] -> []}, releasing a completed async call's subtask handle.
	 * @return the encoded canonical function
	 */
	public static byte[] canonSubtaskDrop() {
		return enc(w -> w.write(0x0d));
	}

	/**
	 * Encode an <strong>async</strong> {@code canon lower} with the canonical memory /
	 * realloc / UTF-8 options (the {@code async} canonical option, tag {@code 0x06}). The
	 * lowered core function's signature becomes {@code [flattened params..., i32
	 * results-ptr] -> [i32 (status << 4) | subtask]} -- the call returns immediately and
	 * its completion is awaited through a waitable-set.
	 * @param funcIndex the component function index to lower
	 * @param memoryIndex the canonical memory index
	 * @param reallocFuncIndex the canonical realloc core function index
	 * @return the encoded canonical function
	 */
	public static byte[] canonLowerAsyncMemoryReallocUtf8(int funcIndex, int memoryIndex, int reallocFuncIndex) {
		return enc(w -> w.write(0x01)
			.write(0x00)
			.writeUnsignedLeb128(funcIndex)
			.writeUnsignedLeb128(4)
			.write(0x06) // async
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex)
			.write(0x04)
			.writeUnsignedLeb128(reallocFuncIndex)
			.write(0x00)); // string-encoding utf8
	}

	/**
	 * Encode the defined value type {@code own<resource>} (component type tag
	 * {@code 0x69}), e.g. the element type of the wasi:sockets accept stream.
	 * @param resourceTypeIndex the component type index of the resource type
	 * @return the encoded defined value type
	 */
	public static byte[] definedOwn(int resourceTypeIndex) {
		return enc(w -> w.write(0x69).writeUnsignedLeb128(resourceTypeIndex));
	}

	/**
	 * Encode the defined value type {@code future<T>} where {@code T} is a defined type
	 * referenced by index (component type tag {@code 0x65}).
	 * @param payloadTypeIndex the component type index of the future payload type
	 * @return the encoded defined value type
	 */
	public static byte[] definedFuture(int payloadTypeIndex) {
		return enc(w -> w.write(0x65).write(0x01).writeUnsignedLeb128(payloadTypeIndex));
	}

	/**
	 * Encode the defined value type {@code result<_, errType>} (no ok payload, an error
	 * payload referenced by index), as used by the WASI 0.3 stream/future error channel.
	 * @param errTypeIndex the component type index of the error type (e.g. an
	 * {@code error-code} enum)
	 * @return the encoded defined value type
	 */
	public static byte[] definedResultErr(int errTypeIndex) {
		return enc(w -> w.write(0x6a).write(0x00).write(0x01).writeUnsignedLeb128(errTypeIndex));
	}

	/**
	 * Encode an <strong>async</strong> component function type with no parameters whose
	 * single result is a defined type referenced by index (component func type tag
	 * {@code 0x43}). Lifting a core function with the ordinary {@link #canonLift} against
	 * this type produces a stackful async export (no callback), so straight-line core
	 * code can call blocking stream/future built-ins which suspend cooperatively.
	 * @param resultTypeIndex the component type index of the result type (e.g.
	 * {@code result<_, _>})
	 * @return the encoded async function type
	 */
	public static byte[] asyncFuncTypeResultType(int resultTypeIndex) {
		return enc(w -> w.write(0x43).writeUnsignedLeb128(0).write(0x00).writeSignedLeb128(resultTypeIndex));
	}

	/**
	 * Encode an <strong>async</strong> component function type with the given named
	 * parameters (each a primitive value type) and an optional single primitive result
	 * (component func type tag {@code 0x43}) &mdash; the async counterpart of
	 * {@link #funcTypeScalars}. Lifting a core function with the ordinary
	 * {@link #canonLift} (or the string-ABI variant) against this type produces a
	 * stackful async export whose flat core signature is identical to the sync one, but
	 * which runs as an async task &mdash; so blocking stream/future built-ins (I/O)
	 * suspend cooperatively instead of trapping with "cannot block a synchronous task".
	 * @param paramNames the parameter names, in order (must be valid component-model
	 * labels)
	 * @param paramValTypes the primitive value type code of each parameter (e.g.
	 * {@link #VT_S32})
	 * @param resultValType the result primitive value type code, or {@code null} for no
	 * result
	 * @return the encoded async function type
	 */
	public static byte[] asyncFuncTypeScalars(List<String> paramNames, List<Integer> paramValTypes,
			@Nullable Integer resultValType) {
		return enc(w -> {
			w.write(0x43); // async functype
			w.writeUnsignedLeb128(paramNames.size());
			for (int i = 0; i < paramNames.size(); i++) {
				plainName(w, paramNames.get(i));
				w.writeSignedLeb128(paramValTypes.get(i) - 0x80);
			}
			if (resultValType == null) {
				// result list: 0x01 (named-results form) then an empty vec (no results)
				w.write(0x01).writeUnsignedLeb128(0);
			}
			else {
				w.write(0x00).writeSignedLeb128(resultValType - 0x80);
			}
		});
	}

	// Canonical built-in option list carrying only the canonical memory (the shape every
	// memory-bearing stream/future built-in uses): count 1, option tag 0x03, memory
	// index.
	private static void memoryOption(WasmWriter w, int memoryIndex) {
		w.writeUnsignedLeb128(1).write(0x03).writeUnsignedLeb128(memoryIndex);
	}

	/**
	 * Encode {@code canon stream.new} (opcode {@code 0x0e}) for the given stream type,
	 * producing a core function {@code () -> i64} that returns a packed
	 * {@code (readable << 0) | (writable << 32)} handle pair.
	 * @param streamTypeIndex the component type index of the {@code stream<u8>} type
	 * @return the encoded canonical entry
	 */
	public static byte[] canonStreamNew(int streamTypeIndex) {
		return enc(w -> w.write(0x0e).writeUnsignedLeb128(streamTypeIndex));
	}

	/**
	 * Encode {@code canon stream.read} (opcode {@code 0x0f}) for the given stream type,
	 * producing a core function {@code (handle, ptr, count) -> i32} (the synchronous
	 * variant blocks cooperatively under a stackful async task).
	 * @param streamTypeIndex the component type index of the {@code stream<u8>} type
	 * @param memoryIndex the core memory index the bytes are read into
	 * @return the encoded canonical entry
	 */
	public static byte[] canonStreamRead(int streamTypeIndex, int memoryIndex) {
		return enc(w -> {
			w.write(0x0f).writeUnsignedLeb128(streamTypeIndex);
			memoryOption(w, memoryIndex);
		});
	}

	/**
	 * Encode {@code canon stream.write} (opcode {@code 0x10}) for the given stream type,
	 * producing a core function {@code (handle, ptr, count) -> i32} (the synchronous
	 * variant blocks cooperatively under a stackful async task).
	 * @param streamTypeIndex the component type index of the {@code stream<u8>} type
	 * @param memoryIndex the core memory index the bytes are written from
	 * @return the encoded canonical entry
	 */
	public static byte[] canonStreamWrite(int streamTypeIndex, int memoryIndex) {
		return enc(w -> {
			w.write(0x10).writeUnsignedLeb128(streamTypeIndex);
			memoryOption(w, memoryIndex);
		});
	}

	/**
	 * Encode {@code canon stream.drop-readable} (opcode {@code 0x13}) for the given
	 * stream type, producing a core function {@code (handle) -> ()}.
	 * @param streamTypeIndex the component type index of the {@code stream<u8>} type
	 * @return the encoded canonical entry
	 */
	public static byte[] canonStreamDropReadable(int streamTypeIndex) {
		return enc(w -> w.write(0x13).writeUnsignedLeb128(streamTypeIndex));
	}

	/**
	 * Encode {@code canon stream.drop-writable} (opcode {@code 0x14}) for the given
	 * stream type, producing a core function {@code (handle) -> ()}. Dropping the
	 * writable end signals end-of-stream to the reader.
	 * @param streamTypeIndex the component type index of the {@code stream<u8>} type
	 * @return the encoded canonical entry
	 */
	public static byte[] canonStreamDropWritable(int streamTypeIndex) {
		return enc(w -> w.write(0x14).writeUnsignedLeb128(streamTypeIndex));
	}

	/**
	 * Encode {@code canon future.new} (opcode {@code 0x15}) for the given future type,
	 * producing a core function {@code () -> i64} that returns a packed readable/writable
	 * handle pair.
	 * @param futureTypeIndex the component type index of the {@code future<T>} type
	 * @return the encoded canonical entry
	 */
	public static byte[] canonFutureNew(int futureTypeIndex) {
		return enc(w -> w.write(0x15).writeUnsignedLeb128(futureTypeIndex));
	}

	/**
	 * Encode {@code canon future.read} (opcode {@code 0x16}) for the given future type,
	 * producing a core function {@code (handle, ptr) -> i32} (the synchronous variant
	 * blocks cooperatively until the future resolves).
	 * @param futureTypeIndex the component type index of the {@code future<T>} type
	 * @param memoryIndex the core memory index the payload is read into
	 * @return the encoded canonical entry
	 */
	public static byte[] canonFutureRead(int futureTypeIndex, int memoryIndex) {
		return enc(w -> {
			w.write(0x16).writeUnsignedLeb128(futureTypeIndex);
			memoryOption(w, memoryIndex);
		});
	}

	/**
	 * Encode {@code canon future.read} (opcode {@code 0x16}) with both the canonical
	 * memory and reallocation options, required when the future payload can carry a
	 * variable-length value (e.g. a {@code result<_, error-code>} whose error case bears
	 * a {@code string}, as {@code wasi:filesystem}'s {@code error-code} does).
	 * @param futureTypeIndex the component type index of the {@code future<T>} type
	 * @param memoryIndex the core memory index the payload is read into
	 * @param reallocFuncIndex the core function index of {@code cabi_realloc}
	 * @return the encoded canonical entry
	 */
	public static byte[] canonFutureRead(int futureTypeIndex, int memoryIndex, int reallocFuncIndex) {
		return enc(w -> w.write(0x16)
			.writeUnsignedLeb128(futureTypeIndex)
			.writeUnsignedLeb128(2)
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex)
			.write(0x04)
			.writeUnsignedLeb128(reallocFuncIndex));
	}

	/**
	 * Encode {@code canon future.write} (opcode {@code 0x17}) for the given future type,
	 * producing a core function {@code (handle, ptr) -> i32}.
	 * @param futureTypeIndex the component type index of the {@code future<T>} type
	 * @param memoryIndex the core memory index the payload is written from
	 * @return the encoded canonical entry
	 */
	public static byte[] canonFutureWrite(int futureTypeIndex, int memoryIndex) {
		return enc(w -> {
			w.write(0x17).writeUnsignedLeb128(futureTypeIndex);
			memoryOption(w, memoryIndex);
		});
	}

	/**
	 * Encode {@code canon future.write} (opcode {@code 0x17}) with the canonical memory
	 * and UTF-8 string-encoding options, required when the future payload can carry a
	 * string (e.g. wasi:http's {@code error-code} cases). Golden bytes from
	 * {@code wasm-tools dump} of the wasi:http 0.3 reference component (2026-07-16):
	 * {@code FutureWrite '{' ty, options: [Memory(0), UTF8] '}'}.
	 * @param futureTypeIndex the component type index of the {@code future<T>} type
	 * @param memoryIndex the core memory index the payload is written from
	 * @return the encoded canonical entry
	 */
	public static byte[] canonFutureWriteUtf8(int futureTypeIndex, int memoryIndex) {
		return enc(w -> w.write(0x17)
			.writeUnsignedLeb128(futureTypeIndex)
			.writeUnsignedLeb128(2)
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex)
			.write(0x00));
	}

	/**
	 * Encode an <strong>async</strong> component function type with the given named
	 * parameters (each an encoded valtype) and an optional single result (component func
	 * type tag {@code 0x43}) &mdash; the async counterpart of {@link #funcTypeOf}, used
	 * for a WIT {@code async func} both inside an imported-instance type and at the
	 * component level (the async-lifted export's type).
	 * @param paramNames the parameter names, in order
	 * @param paramTypes the encoded valtype of each parameter ({@link #valTypePrim} /
	 * {@link #valTypeIndex})
	 * @param resultType the encoded result valtype, or {@code null} for no result
	 * @return the encoded async function type
	 */
	public static byte[] asyncFuncTypeOf(List<String> paramNames, List<byte[]> paramTypes,
			byte @Nullable [] resultType) {
		return enc(w -> {
			w.write(0x43);
			w.writeUnsignedLeb128(paramNames.size());
			for (int i = 0; i < paramNames.size(); i++) {
				plainName(w, paramNames.get(i));
				w.write((Object) paramTypes.get(i));
			}
			if (resultType == null) {
				w.write(0x01).writeUnsignedLeb128(0);
			}
			else {
				w.write(0x00).write((Object) resultType);
			}
		});
	}

	/**
	 * Encode a <strong>stackful async</strong> {@code canon lift}: the canonical memory,
	 * UTF-8 string-encoding and {@code async} options, in that order (the byte sequence
	 * wasm-tools emits for an async-without-callback export). The lifted core function's
	 * signature is {@code [flat params] -> []} &mdash; the result is delivered
	 * exclusively through {@code canon task.return} &mdash; and running it requires
	 * {@code wasmtime -W component-model-async-stackful=y}.
	 * @param coreFuncIndex the core function index to lift
	 * @param typeIndex the component function type index (an async function type)
	 * @param memoryIndex the core memory index used by the canonical ABI
	 * @return the encoded canonical entry
	 */
	public static byte[] canonLiftMemoryUtf8Async(int coreFuncIndex, int typeIndex, int memoryIndex) {
		return enc(w -> w.write(0x00)
			.write(0x00)
			.writeUnsignedLeb128(coreFuncIndex)
			.writeUnsignedLeb128(3) // three canonical options
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex) // memory
			.write(0x00) // string-encoding utf8
			.write(0x06) // async (stackful)
			.writeUnsignedLeb128(typeIndex));
	}

	/**
	 * Encode {@code canon task.return} with the canonical memory and UTF-8
	 * string-encoding options (wasm-tools' choice for a result whose flattening reads
	 * guest memory), for a result type referenced by index.
	 * @param resultTypeIndex the component type index of the task's result type
	 * @param memoryIndex the canonical memory index
	 * @return the encoded canonical function
	 */
	public static byte[] canonTaskReturnTypeMemoryUtf8(int resultTypeIndex, int memoryIndex) {
		return enc(w -> w.write(0x09)
			.write(0x00)
			.writeSignedLeb128(resultTypeIndex)
			.writeUnsignedLeb128(2)
			.write(0x03)
			.writeUnsignedLeb128(memoryIndex)
			.write(0x00));
	}

	/**
	 * Encode {@code canon future.drop-readable} (opcode {@code 0x1a}) for the given
	 * future type, producing a core function {@code (handle) -> ()}.
	 * @param futureTypeIndex the component type index of the {@code future<T>} type
	 * @return the encoded canonical entry
	 */
	public static byte[] canonFutureDropReadable(int futureTypeIndex) {
		return enc(w -> w.write(0x1a).writeUnsignedLeb128(futureTypeIndex));
	}

	/**
	 * Encode {@code canon future.drop-writable} (opcode {@code 0x1b}) for the given
	 * future type, producing a core function {@code (handle) -> ()}.
	 * @param futureTypeIndex the component type index of the {@code future<T>} type
	 * @return the encoded canonical entry
	 */
	public static byte[] canonFutureDropWritable(int futureTypeIndex) {
		return enc(w -> w.write(0x1b).writeUnsignedLeb128(futureTypeIndex));
	}

}
