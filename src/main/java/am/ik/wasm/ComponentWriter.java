package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;

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
