package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import am.ik.wasm.ComponentWriter;

/**
 * Wraps a rontolisp core module (compiled in component mode) into a WASI 0.3 (Preview 3)
 * <strong>component</strong> that prints through {@code wasi:cli/stdout@0.3.0} and is
 * runnable with {@code wasmtime run -W component-model-async=y
 * -W component-model-async-stackful=y -W component-model-more-async-builtins=y -W gc=y}.
 *
 * <p>
 * In WASI 0.3 the {@code wasi:io} package is gone: all byte I/O flows through the
 * built-in component-model {@code stream<u8>} / {@code future<T>} types and the async
 * canonical ABI. The wrapped rontolisp core module is unchanged from Preview 1 &mdash; it
 * still imports the eight {@code wasi_snapshot_preview1} functions and prints / reads
 * through them; those imports are satisfied by an <em>adapter</em> core module that
 * implements them over WASI 0.3 ({@code wasi:cli}, {@code wasi:filesystem},
 * {@code wasi:clocks}, {@code wasi:random}) using
 * {@code stream.new}/{@code stream.read}/{@code stream.write} and {@code future.read}
 * (see {@code src/wasm-component/adapter.wat}). The component's
 * {@code wasi:cli/run@0.3.0} export &mdash; an {@code async func} &mdash; is lifted as a
 * <strong>stackful</strong> async export (an ordinary {@link ComponentWriter#canonLift}
 * against an {@link ComponentWriter#asyncFuncTypeResultType async} function type), so the
 * synchronous {@code stream.write}/{@code stream.read}/{@code future.read} built-ins the
 * adapter calls block cooperatively without any callback state machine.
 *
 * <p>
 * A lowered WASI import with a {@code list<u8>}/{@code string} parameter needs the
 * canonical memory, and that memory must exist before the importing module is
 * instantiated. To avoid the instantiate-before-memory cycle, a tiny shared "memory" core
 * module is instantiated first; it exports the memory (and a {@code cabi_realloc}) used
 * by the canonical lowering, the canonical built-ins and the adapter / rontolisp modules.
 *
 * <p>
 * Three byte blobs are loaded from classpath resources next to this class (under
 * {@code component/}) because they are fixed and independent of the compiled program:
 * {@code import-block.bin} (the unified WASI 0.3 import declarations) and the two helper
 * core modules {@code mem.wasm} / {@code adapter.wasm}. The remaining wiring is emitted
 * programmatically below; the indices and per-function canonical options were derived
 * from {@code wasm-tools dump} of a generated reference (see
 * {@code src/wasm-component/README.md}). Each artifact was validated with
 * {@code wasm-tools validate -f component-model -f cm-async -f cm-async-stackful
 * -f cm-more-async-builtins} and executed with {@code wasmtime run}.
 */
public final class WasmComponentBuilder {

	/** Classpath location of the embedded component blobs, relative to this class. */
	private static final String RES = "component/";

	/**
	 * Pre-built component type/import sections declaring every imported WASI 0.3
	 * interface, captured from a {@code wasm-tools}-generated reference for the
	 * {@code uni} WIT world (see {@code src/wasm-component/uni.wit}). In order, component
	 * import instances 0-8 are: {@code wasi:cli/types} (the {@code error-code} enum),
	 * {@code wasi:cli/stdout}, {@code wasi:cli/stdin}, {@code wasi:cli/environment},
	 * {@code wasi:clocks/system-clock}, {@code wasi:clocks/monotonic-clock},
	 * {@code wasi:filesystem/types}, {@code wasi:filesystem/preopens} and
	 * {@code wasi:random/random}. The block defines component types 0-11, so the next
	 * free component type index is 12.
	 */
	private static final byte[] IMPORT_BLOCK = resource("import-block.bin");

	/**
	 * A core module exporting a linear {@code memory} (6 pages) and a bump-allocator
	 * {@code cabi_realloc}, shared by the canonical lowering, the canonical built-ins and
	 * the main modules. Source: {@code src/wasm-component/mem.wat}.
	 */
	private static final byte[] MEM_MODULE = resource("mem.wasm");

	/**
	 * The preview1-to-WASI-0.3 adapter core module: it imports the shared memory, the
	 * lowered WASI 0.3 functions and the async canonical built-ins (under {@code "w"})
	 * and exports the eight preview1-style functions rontolisp imports. Source:
	 * {@code src/wasm-component/adapter.wat}.
	 */
	private static final byte[] ADAPTER_MODULE = resource("adapter.wasm");

	// Component import-instance indices (from import-block.bin; see IMPORT_BLOCK).
	private static final int INST_CLI_TYPES = 0;

	private static final int INST_STDOUT = 1;

	private static final int INST_STDIN = 2;

	private static final int INST_ENVIRON = 3;

	private static final int INST_SYS_CLOCK = 4;

	private static final int INST_MONO_CLOCK = 5;

	private static final int INST_FS_TYPES = 6;

	private static final int INST_FS_PREOPENS = 7;

	private static final int INST_RANDOM = 8;

	// First free component type index after the import block.
	private static final int T_CLI_ERRCODE = 12;

	private static final int T_FS_ERRCODE = 13;

	private static final int T_DESCRIPTOR = 14;

	private static final int T_STREAM = 15;

	private static final int T_CLI_RESULT = 16;

	private static final int T_CLI_FUTURE = 17;

	private static final int T_FS_RESULT = 18;

	private static final int T_FS_FUTURE = 19;

	private static final int T_RUN_RESULT = 20;

	private static final int T_RUN_FUNC = 21;

	private WasmComponentBuilder() {
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode (imports its
	 * memory, imports {@code wasi_snapshot_preview1}, and exports a {@code run} function
	 * returning {@code i32})
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule) {
		return build(coreModule, false);
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param usesHttp whether the program uses {@code rontolisp:fetch}
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule, boolean usesHttp) {
		if (usesHttp) {
			throw new UnsupportedOperationException(
					"rontolisp:fetch is not yet supported in WASI 0.3 (Preview 3) component mode");
		}
		final ComponentWriter c = new ComponentWriter();
		// All imported WASI 0.3 interfaces in one block: import instances 0-8, types
		// 0-11.
		c.writeRaw(IMPORT_BLOCK);
		// Core modules: 0 = shared memory, 1 = adapter, 2 = rontolisp.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Alias the resource/enum types we need (component types 12-14) and the WASI
		// functions (component funcs 0-9), all in one alias section.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// types 12 cli error-code, 13 fs error-code, 14 descriptor
				ComponentWriter.aliasInstanceType(INST_CLI_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "descriptor"),
				// funcs 0 write-via-stream, 1 read-via-stream(stdin), 2 get-environment,
				// 3 system-clock.now, 4 monotonic.now
				ComponentWriter.aliasInstanceFunc(INST_STDOUT, "write-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_STDIN, "read-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_ENVIRON, "get-environment"),
				ComponentWriter.aliasInstanceFunc(INST_SYS_CLOCK, "now"),
				ComponentWriter.aliasInstanceFunc(INST_MONO_CLOCK, "now"),
				// funcs 5 read-via-stream(file), 6 append-via-stream, 7 open-at,
				// 8 get-directories, 9 get-random-u64
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.read-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.append-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.open-at"),
				ComponentWriter.aliasInstanceFunc(INST_FS_PREOPENS, "get-directories"),
				ComponentWriter.aliasInstanceFunc(INST_RANDOM, "get-random-u64"))));
		// Define the async value/function types (component types 15-21). stream<u8> is
		// structural; the futures differ by their error-code (cli vs filesystem).
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 15
						ComponentWriter.definedResultErr(T_CLI_ERRCODE), // 16
						ComponentWriter.definedFuture(T_CLI_RESULT), // 17
						ComponentWriter.definedResultErr(T_FS_ERRCODE), // 18
						ComponentWriter.definedFuture(T_FS_RESULT), // 19
						ComponentWriter.definedResultVoid(), // 20 result<_,_> (run
																// result)
						ComponentWriter.asyncFuncTypeResultType(T_RUN_RESULT)))); // 21
		// Lower the WASI functions (component funcs 0-9) to core funcs 1-10 and drop the
		// descriptor resource (core func 11); canonical options mirror wasm-tools'
		// choices.
		// Then the async built-ins: stream (core funcs 12-16), futures (core funcs
		// 17-20).
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLower(0), // 1
																											// write-via-stream
				ComponentWriter.canonLowerMemory(1, 0), // 2 read-via-stream (stdin)
				ComponentWriter.canonLowerMemoryReallocUtf8(2, 0, 0), // 3 get-environment
				ComponentWriter.canonLowerMemory(3, 0), // 4 system-clock.now
				ComponentWriter.canonLower(4), // 5 monotonic.now
				ComponentWriter.canonLowerMemory(5, 0), // 6 descriptor.read-via-stream
				ComponentWriter.canonLower(6), // 7 descriptor.append-via-stream
				ComponentWriter.canonLowerMemoryReallocUtf8(7, 0, 0), // 8 open-at
				ComponentWriter.canonLowerMemoryReallocUtf8(8, 0, 0), // 9 get-directories
				ComponentWriter.canonLower(9), // 10 get-random-u64
				ComponentWriter.canonResourceDrop(T_DESCRIPTOR), // 11 drop descriptor
				ComponentWriter.canonStreamNew(T_STREAM), // 12
				ComponentWriter.canonStreamRead(T_STREAM, 0), // 13
				ComponentWriter.canonStreamWrite(T_STREAM, 0), // 14
				ComponentWriter.canonStreamDropReadable(T_STREAM), // 15
				ComponentWriter.canonStreamDropWritable(T_STREAM), // 16
				ComponentWriter.canonFutureRead(T_CLI_FUTURE, 0), // 17 future-read-cli
				ComponentWriter.canonFutureDropReadable(T_CLI_FUTURE), // 18
																		// future-drop-cli
				// the filesystem error-code is a variant with a string-bearing case, so
				// its
				// future payload needs realloc (cabi_realloc = core func 0)
				ComponentWriter.canonFutureRead(T_FS_FUTURE, 0, 0), // 19 future-read-fs
				ComponentWriter.canonFutureDropReadable(T_FS_FUTURE)))); // 20
																			// future-drop-fs
		// Group the 20 lowered/built-in core funcs (1-20) for the adapter's "w" import
		// (core instance 1). Names match adapter.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("stdout-write", "stdin-read", "get-environment", "sys-now", "mono-now", "file-read",
								"file-append", "open-at", "get-directories", "get-random-u64", "drop-desc",
								"stream-new", "stream-read", "stream-write", "stream-drop-r", "stream-drop-w",
								"future-read-cli", "future-drop-cli", "future-read-fs", "future-drop-fs"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// Instantiate rontolisp (core instance 3): mem = instance 0,
		// wasi_snapshot_preview1 = adapter instance 2.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2)))));
		// Alias rontolisp's run (core func 21 = cabi_realloc + 20 lowered/built-in
		// funcs).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Lift run (core func 21) into component func 10 with the async function type 21
		// (stackful async: no callback option). Component func 10 follows the 10 aliased
		// WASI funcs (0-9).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(21, T_RUN_FUNC))));
		// Component instance 9 (after import instances 0-8) exporting run, exported as
		// the
		// wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 10))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 9))));
		return c.toByteArray();
	}

	/**
	 * Load a fixed component blob bundled as a classpath resource next to this class.
	 * @param name the file name under {@code component/}
	 * @return the raw bytes
	 */
	private static byte[] resource(String name) {
		try (InputStream in = WasmComponentBuilder.class.getResourceAsStream(RES + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing component resource: " + RES + name);
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to read component resource: " + RES + name, ex);
		}
	}

}
