package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.wasm.ComponentWriter;

/**
 * Wraps a rontolisp core module (compiled in component mode) into a WASI 0.2 (Preview 2)
 * <strong>component</strong> that prints through {@code wasi:cli/stdout} and
 * {@code wasi:io/streams} and is runnable with {@code wasmtime run}.
 *
 * <p>
 * The wrapped core module keeps importing the four {@code wasi_snapshot_preview1}
 * functions and printing through {@code fd_write}; those imports are satisfied by an
 * <em>adapter</em> core module that implements {@code fd_write} on top of WASI 0.2
 * streams (and stubs {@code fd_read}/{@code path_open}/{@code fd_close}, so reading and
 * file I/O are not yet available in component mode). The only change the core module
 * itself needs is to import its linear memory and export a {@code run} entry (see
 * {@link WasmLispCompiler}).
 *
 * <p>
 * A lowered WASI import with a {@code list<u8>} parameter needs the canonical memory, and
 * that memory must exist before the importing module is instantiated. To avoid the
 * instantiate-before-memory cycle without the lazy funcref trampoline that
 * {@code wasm-tools} emits, a tiny shared "memory" core module is instantiated first; it
 * exports the memory (and a {@code cabi_realloc}) that both the canonical lowering and
 * the main module use.
 *
 * <p>
 * Three byte blobs are embedded as constants because they are fixed and independent of
 * the compiled program: the WASI import declarations (resource / variant / result / list
 * type encodings that are impractical to hand-build) and the two helper core modules.
 * Each was validated with {@code wasm-tools validate -f component-model} and executed
 * with {@code wasmtime run}.
 */
public final class WasmComponentBuilder {

	private WasmComponentBuilder() {
	}

	/**
	 * Pre-built component sections declaring the imported WASI 0.2 interfaces
	 * {@code wasi:io/error}, {@code wasi:io/streams} (the {@code output-stream} resource
	 * and its {@code blocking-write-and-flush} method) and {@code wasi:cli/stdout}
	 * ({@code get-stdout}). Establishes component types 0-4 and component instances 0-2.
	 */
	private static final byte[] IMPORT_BLOCK = fromHex(
			"070d0142010400056572726f7203010a18010013776173693a696f2f6572726f7240302e322e300500060a01030000056572726f72"
					+ "07b20101420b04000d6f75747075742d73747265616d030102030201010400056572726f72030001016902017102156c6173742d6f"
					+ "7065726174696f6e2d6661696c656401030006636c6f736564000004000c73747265616d2d6572726f7203000401680001707d016a"
					+ "0001050140020473656c660608636f6e74656e747307000804002e5b6d6574686f645d6f75747075742d73747265616d2e626c6f63"
					+ "6b696e672d77726974652d616e642d666c75736801090a1a010015776173693a696f2f73747265616d7340302e322e300502061201"
					+ "0300010d6f75747075742d73747265616d0732014205020302010304000d6f75747075742d73747265616d030000016901014000"
					+ "000204000a6765742d7374646f757401030a1a010015776173693a636c692f7374646f757440302e322e300504");

	/**
	 * A core module exporting a 1-page linear {@code memory} and a bump-allocator
	 * {@code cabi_realloc}, shared by the canonical lowering and the main module.
	 */
	private static final byte[] MEM_MODULE = fromHex(
			"0061736d0100000001090160047f7f7f7f017f0302010005030100010607017f014180080b071902066d656d6f727902000c636162"
					+ "695f7265616c6c6f6300000a13011101017f23002104230020036a240020040b0014046e616d65020601000104017207050100026870");

	/**
	 * The preview1-to-0.2 adapter core module: it imports the shared memory and the
	 * lowered WASI functions ({@code get-stdout} / {@code write} / {@code drop}) and
	 * exports {@code fd_write} (looping over the iovec and calling
	 * {@code output-stream.blocking-write-and-flush}) plus stub
	 * {@code fd_read}/{@code path_open}/{@code fd_close}. The stream-error result area is
	 * written at linear offset 256, which is outside the rontolisp fixed I/O layout.
	 */
	private static final byte[] ADAPTER_MODULE = fromHex(
			"0061736d01000000012a066000017f60047f7f7f7f0060017f0060047f7f7f7f017f60097f7f7f7f7f7e7e7f7f017f60017f017f02"
					+ "3104036d656d066d656d6f727902000101770a6765742d7374646f757400000177057772697465000101770464726f70000203050403"
					+ "030405072d040866645f777269746500030766645f72656164000409706174685f6f70656e00050866645f636c6f736500060a6c0459"
					+ "01067f1000210402400340200520024f0d01200120054180026c6a210920092802002106200941046a28020021072004200620074180"
					+ "021001200820076a2108200541016a21050c000b0b200410022003200836020041000b05004180020b05004180020b040041000b0064"
					+ "046e616d65011a03000a6765745f7374646f757401057772697465020464726f70023301030a000266640103696f760203636e740302"
					+ "6e7704026f73050169060370747207036c656e0805746f74616c090462617365030c0103020004646f6e6501016c");

	/**
	 * Assemble a runnable WASI 0.2 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode (imports its
	 * memory, imports {@code wasi_snapshot_preview1}, and exports a {@code run} function
	 * returning {@code i32})
	 * @return the WASI 0.2 component binary
	 */
	public static byte[] build(byte[] coreModule) {
		final ComponentWriter c = new ComponentWriter();
		// Imported WASI interfaces: component types 0-4, component instances 0-2.
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
		// Alias the imported WASI functions (component funcs 0 and 1).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceFunc(2, "get-stdout"),
						ComponentWriter.aliasInstanceFunc(1, "[method]output-stream.blocking-write-and-flush"))));
		// Lower them (core funcs 1, 2) and resource.drop output-stream (core func 3).
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLower(0),
				ComponentWriter.canonLower(1, 0, 0), ComponentWriter.canonResourceDrop(3))));
		// Group the lowered functions for the adapter's "w" import (core instance 1).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceFromFuncs(List.of("get-stdout", "write", "drop"), List.of(1, 2, 3)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// Instantiate rontolisp (core instance 3): mem = instance 0,
		// wasi_snapshot_preview1 = adapter instance 2.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2)))));
		// Alias rontolisp's run (core func 4).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Types: 5 = result<_,_>, 6 = func () -> result<_,_>.
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter
			.vec(List.of(ComponentWriter.definedResultVoid(), ComponentWriter.funcTypeResultType(5))));
		// Lift run into component func 2 with type 6.
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLift(4, 6))));
		// Component instance 3 exporting run, exported as the wasi:cli/run interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 2))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.2.0", 3))));
		return c.toByteArray();
	}

	private static byte[] fromHex(String hex) {
		final int n = hex.length() / 2;
		final byte[] out = new byte[n];
		for (int i = 0; i < n; i++) {
			out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

}
