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
			"0061736d0100000001090160047f7f7f7f017f0302010005030100040608017f01418080040b071902066d656d6f727902000c636162695f"
					+ "7265616c6c6f6300000a26012401017f2300200241016b6a200241016b417f7371240023002104230020036a240020040b0014046e616d65"
					+ "020601000104017207050100026870");

	/**
	 * The preview1-to-0.2 adapter core module: it imports the shared memory and the
	 * lowered WASI functions ({@code get-stdout} / {@code write} / {@code drop} /
	 * {@code get-random-u64} / {@code wall-now} / {@code mono-now}) and exports the
	 * preview1-style functions rontolisp imports -- {@code fd_write} (over
	 * {@code wasi:io/streams}), {@code random_get} (over {@code wasi:random}),
	 * {@code clock_time_get} (over {@code wasi:clocks} wall/monotonic), plus stub
	 * {@code environ_sizes_get}/{@code environ_get}/{@code fd_read}/{@code path_open}/
	 * {@code fd_close}. The stream-error result area is at linear offset 256 and the
	 * wall-clock datetime scratch at 512, both outside the rontolisp fixed I/O layout.
	 */
	private static final byte[] ADAPTER_MODULE = fromHex(
			"0061736d01000000013b096000017f60047f7f7f7f0060017f006000017e60047f7f7f7f017f60027f7f017f60037f7e7f017f60097f7f7f"
					+ "7f7f7e7e7f7f017f60017f017f027208036d656d066d656d6f727902000401770a6765742d7374646f757400000177057772697465000101"
					+ "770464726f70000201770e6765742d72616e646f6d2d753634000301770877616c6c2d6e6f7700020177086d6f6e6f2d6e6f77000301770f"
					+ "6765742d656e7669726f6e6d656e7400020309080405060505040708076d080866645f777269746500070a72616e646f6d5f67657400080e"
					+ "636c6f636b5f74696d655f676574000911656e7669726f6e5f73697a65735f676574000a0b656e7669726f6e5f676574000b0766645f7265"
					+ "6164000c09706174685f6f70656e000d0866645f636c6f7365000e0a9004085801067f1000210402400340200520024f0d01200120054108"
					+ "6c6a210920092802002106200941046a28020021072004200620074180021001200820076a2108200541016a21050c000b0b200410022003"
					+ "200836020041000b2601017f02400340200220014f0d01200020026a1003370300200241086a21020c000b0b41000b310020004504404180"
					+ "0410042002418004290300428094ebdc037e418804280200ad7c37030005200210053703000b41000b6201057f41d804100641d804280200"
					+ "210241dc04280200210302400340200420034f0d012002200441106c6a21062005200641046a2802002006410c6a2802006a41026a6a2105"
					+ "200441016a21040c000b0b200020033602002001200536020041000be901010a7f41d804100641d804280200210241dc0428020021032001"
					+ "210502400340200420034f0d012002200441106c6a210620062802002107200641046a2802002108200641086a28020021092006410c6a28"
					+ "0200210a2000200441046c6a20053602004100210b02400340200b20084f0d0120052007200b6a2d00003a0000200541016a2105200b4101"
					+ "6a210b0c000b0b2005413d3a0000200541016a21054100210b02400340200b200a4f0d0120052009200b6a2d00003a0000200541016a2105"
					+ "200b41016a210b0c000b0b200541003a0000200541016a2105200441016a21040c000b0b41000b040041080b040041080b040041000b00b6"
					+ "02046e616d65014407000a6765745f7374646f757401057772697465020464726f70030872616e645f753634040877616c6c5f6e6f770508"
					+ "6d6f6e6f5f6e6f77060a676574656e7669726f6e02b10105070a000266640103696f760203636e7403026e7704026f730501690603707472"
					+ "07036c656e0805746f74616c0904626173650803000362756601036c656e02016909030005636c6b69640104707265630206726573707472"
					+ "0a0700026370010262700204626173650305636f756e740401690502737a0601650b0c000270700104627566700204626173650305636f75"
					+ "6e7404016905036f757406016507026b7008026b6c090276700a02766c0b016a03350407020004646f6e6501016c08020004646f6e650101"
					+ "6c0a0200016401016c0b0600016401016c02026b6403016b04027664050176");

	/**
	 * The {@code wasi:clocks/wall-clock} instance type (its {@code now} returns a
	 * {@code datetime} record of {@code seconds: u64} / {@code nanoseconds: u32}).
	 * Captured from a wasm-tools-generated reference.
	 */
	private static final byte[] WALL_CLOCK_TYPE = fromHex(
			"4204017202077365636f6e6473770b6e616e6f7365636f6e6473790400086461746574696d6503000001400000010400036e6f770102");

	/**
	 * The {@code wasi:clocks/monotonic-clock} instance type (its {@code now} returns a
	 * {@code u64} instant).
	 */
	private static final byte[] MONOTONIC_CLOCK_TYPE = fromHex(
			"42040177040007696e7374616e7403000001400000010400036e6f770102");

	/**
	 * The {@code wasi:cli/environment} instance type (its {@code get-environment} returns
	 * a {@code list<tuple<string, string>>}).
	 */
	private static final byte[] ENVIRONMENT_TYPE = fromHex(
			"4204016f027373017000014000000104000f6765742d656e7669726f6e6d656e740102");

	/**
	 * Assemble a runnable WASI 0.2 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode (imports its
	 * memory, imports {@code wasi_snapshot_preview1}, and exports a {@code run} function
	 * returning {@code i32})
	 * @return the WASI 0.2 component binary
	 */
	public static byte[] build(byte[] coreModule) {
		final ComponentWriter c = new ComponentWriter();
		// Imported WASI interfaces: component types 0-4, component instances 0-2
		// (wasi:io/error, wasi:io/streams, wasi:cli/stdout).
		c.writeRaw(IMPORT_BLOCK);
		// Component type 5 + instance 3: wasi:random (get-random-u64 () -> u64).
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter
			.vec(List.of(ComponentWriter.instanceTypeWithFunc("get-random-u64", ComponentWriter.VT_U64))));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:random/random@0.2.0", 5))));
		// Component type 6 + instance 4: wasi:clocks/wall-clock (now () -> datetime).
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(WALL_CLOCK_TYPE)));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:clocks/wall-clock@0.2.0", 6))));
		// Component type 7 + instance 5: wasi:clocks/monotonic-clock (now () -> u64).
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(MONOTONIC_CLOCK_TYPE)));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:clocks/monotonic-clock@0.2.0", 7))));
		// Component type 8 + instance 6: wasi:cli/environment (get-environment).
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(ENVIRONMENT_TYPE)));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:cli/environment@0.2.0", 8))));
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
		// Alias imported WASI functions: component funcs 0 = get-stdout, 1 = write,
		// 2 = get-random-u64, 3 = wall-clock.now, 4 = monotonic-clock.now,
		// 5 = get-environment.
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceFunc(2, "get-stdout"),
						ComponentWriter.aliasInstanceFunc(1, "[method]output-stream.blocking-write-and-flush"),
						ComponentWriter.aliasInstanceFunc(3, "get-random-u64"),
						ComponentWriter.aliasInstanceFunc(4, "now"), ComponentWriter.aliasInstanceFunc(5, "now"),
						ComponentWriter.aliasInstanceFunc(6, "get-environment"))));
		// Lower them: core func 1 = get-stdout, 2 = write (memory/realloc), 3 =
		// resource.drop output-stream, 4 = get-random-u64, 5 = wall-clock.now (memory,
		// for
		// the record return), 6 = monotonic-clock.now, 7 = get-environment
		// (memory/realloc,
		// for the list return).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLower(0), ComponentWriter.canonLower(1, 0, 0),
						ComponentWriter.canonResourceDrop(3), ComponentWriter.canonLower(2),
						ComponentWriter.canonLowerMemory(3, 0), ComponentWriter.canonLower(4),
						ComponentWriter.canonLower(5, 0, 0))));
		// Group the lowered functions for the adapter's "w" import (core instance 1).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
					List.of("get-stdout", "write", "drop", "get-random-u64", "wall-now", "mono-now", "get-environment"),
					List.of(1, 2, 3, 4, 5, 6, 7)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// Instantiate rontolisp (core instance 3): mem = instance 0,
		// wasi_snapshot_preview1 = adapter instance 2.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2)))));
		// Alias rontolisp's run (core func 8).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Types: 9 = result<_,_>, 10 = func () -> result<_,_>.
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter
			.vec(List.of(ComponentWriter.definedResultVoid(), ComponentWriter.funcTypeResultType(9))));
		// Lift run into component func 6 with type 10.
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLift(8, 10))));
		// Component instance 7 exporting run, exported as the wasi:cli/run interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 6))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.2.0", 7))));
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
