package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ComponentWriter}.
 *
 * <p>
 * The expected byte sequences are golden values captured from output that was validated
 * with {@code wasm-tools validate -f component-model} and executed with
 * {@code wasmtime run -W gc=y --invoke 'run()'}. Asserting against the golden bytes keeps
 * the encoder pinned without requiring wasmtime on the test host.
 */
class ComponentWriterTest {

	/**
	 * Build a core module:
	 * {@code (module (func (export "run") (result i32) i32.const 42))}.
	 */
	private static byte[] coreModuleRunReturns42() {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm")
			.writeLittleEndian4(1)
			.writeTypeSection(t -> t.addFunc(new Type[] {}, new Type[] { Type.I32 }))
			.writeFunction(f -> f.addFunction(0))
			.writeExport(e -> e.addExport("run", ExternalKind.FUNCTION, 0))
			.writeCode(c -> c.addFunction(new byte[] { 0x00, 0x41, 0x2a, 0x0b }));
		return out.toByteArray();
	}

	/**
	 * Build a core module that imports {@code rand.get-random-u64 ()->i64} and exports
	 * {@code run ()->i32} returning the low 32 bits.
	 */
	private static byte[] coreModuleUsesRand() {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm")
			.writeLittleEndian4(1)
			.writeTypeSection(t -> t.addFunc(new Type[] {}, new Type[] { Type.I64 })
				.addFunc(new Type[] {}, new Type[] { Type.I32 }))
			.writeImportSection(i -> i.addImport("rand", "get-random-u64", ExternalKind.FUNCTION, 0))
			.writeFunction(f -> f.addFunction(1))
			.writeExport(e -> e.addExport("run", ExternalKind.FUNCTION, 1))
			.writeCode(c -> c.addFunction(new byte[] { 0x00, 0x10, 0x00, (byte) 0xa7, 0x0b }));
		return out.toByteArray();
	}

	private static String hex(byte[] bytes) {
		final StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
		}
		return sb.toString();
	}

	@Test
	void preambleIsComponentMagicAndVersion() {
		final byte[] bytes = new ComponentWriter().toByteArray();
		// magic "\0asm" then version 0x000d, layer 0x0001
		assertThat(hex(bytes)).isEqualTo("0061736d0d000100");
	}

	@Test
	void wrapsCoreModuleAndExportsLiftedRun() {
		final byte[] core = coreModuleRunReturns42();
		final ComponentWriter c = new ComponentWriter();
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, core);
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.funcTypeResult(ComponentWriter.VT_S32))));
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(0, "run"))));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLift(0, 0))));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(List.of(ComponentWriter.exportFunc("run", 0))));

		assertThat(hex(c.toByteArray())).isEqualTo("0061736d0d00010001240061736d010000000105016000017f030201000707"
				+ "010372756e00000a06010400412a0b0204010000000705014000007a06090100000100037275"
				+ "6e08060100000000000b0901000372756e010000");
	}

	@Test
	void canonLowerWithMemoryAndReallocOptions() {
		// Lower component func 1 with memory 0 and realloc core func 0 (as used by the
		// stdout write path). Bytes taken from a validated component.
		assertThat(hex(ComponentWriter.canonLower(1, 0, 0))).isEqualTo("0100010203000400");
	}

	@Test
	void canonResourceDropEncoding() {
		assertThat(hex(ComponentWriter.canonResourceDrop(3))).isEqualTo("0303");
	}

	@Test
	void aliasInstanceTypeEncoding() {
		// Project the "output-stream" type export out of imported instance 1 (used to
		// obtain
		// the resource type for canon resource.drop). "output-stream" = 13 bytes (0x0d).
		assertThat(hex(ComponentWriter.aliasInstanceType(1, "output-stream")))
			.isEqualTo("0300010d" + "6f75747075742d73747265616d");
	}

	@Test
	void canonLowerMemoryUtf8Encoding() {
		// Lower component func 7 with memory 0 and UTF-8 string encoding (as used by
		// descriptor.open-at). Bytes match wasm-tools' lowering.
		assertThat(hex(ComponentWriter.canonLowerMemoryUtf8(7, 0))).isEqualTo("01000702030000");
	}

	@Test
	void canonLowerMemoryReallocUtf8Encoding() {
		// Lower component func 6 with memory 0, realloc core func 0, and UTF-8 (as used
		// by
		// get-environment / get-directories).
		assertThat(hex(ComponentWriter.canonLowerMemoryReallocUtf8(6, 0, 0))).isEqualTo("010006030300040000");
	}

	@Test
	void canonLiftMemoryReallocUtf8PostReturnEncoding() {
		// Golden bytes from `wasm-tools dump` of a validated string-lifting component:
		// lift core func 2 against type 0 with the canonical options (memory 0)
		// (realloc 0) string-encoding=utf8 (post-return 1) -- wasm-tools emits the
		// options in exactly this order.
		assertThat(hex(ComponentWriter.canonLiftMemoryReallocUtf8PostReturn(2, 0, 0, 0, 1)))
			.isEqualTo("000002040300040000050100");
	}

	@Test
	void aliasCoreMemoryEncoding() {
		assertThat(hex(ComponentWriter.aliasCoreMemory(0, "memory"))).isEqualTo("00020100066d656d6f7279");
	}

	@Test
	void coreInstanceFromMultipleFuncs() {
		// {"a" = core func 2, "b" = core func 3}
		assertThat(hex(ComponentWriter.coreInstanceFromFuncs(List.of("a", "b"), List.of(2, 3))))
			.isEqualTo("0102" + "01610002" + "01620003");
	}

	@Test
	void aliasCoreTableEncoding() {
		// Golden bytes from `wasm-tools dump` of a --no-gc --component printing
		// component: alias of the shim's "$imports" funcref table out of core instance
		// 0 (sort core table = 00 01, target core instance export = 01).
		assertThat(hex(ComponentWriter.aliasCoreTable(0, "$imports"))).isEqualTo("000101000824696d706f727473");
	}

	@Test
	void coreInstanceFromExportsWithMixedSorts() {
		// Golden bytes from `wasm-tools dump` of the same component: the fixup-argument
		// instance {"$imports" = core table 0, "fd_write" = core func 2} -- per-export
		// core sort bytes (table = 01, func = 00), unlike the funcs-only encoder.
		assertThat(hex(ComponentWriter.coreInstanceFromExports(List.of("$imports", "fd_write"),
				List.of(ComponentWriter.CORE_SORT_TABLE, ComponentWriter.CORE_SORT_FUNC), List.of(0, 2))))
			.isEqualTo("0102" + "0824696d706f7274730100" + "0866645f77726974650002");
	}

	@Test
	void commandRunExportEncoders() {
		// result<_,_> defined type, func ()->result, instance from func, instance export
		assertThat(hex(ComponentWriter.definedResultVoid())).isEqualTo("6a0000");
		assertThat(hex(ComponentWriter.funcTypeResultType(5))).isEqualTo("40000005");
		assertThat(hex(ComponentWriter.componentInstanceFromFunc("run", 2))).isEqualTo("0101000372756e0102");
		// "wasi:cli/run@0.2.0" = 18 bytes (0x12)
		assertThat(hex(ComponentWriter.exportInstance("wasi:cli/run@0.2.0", 3)))
			.isEqualTo("0012" + "776173693a636c692f72756e40302e322e30" + "050300");
	}

	@Test
	void funcTypeScalarsEncoding() {
		// Golden bytes verified against `wasm-tools dump` of a component whose scalar
		// wasm-export lifts were validated (component-model + cm-async...) and invoked
		// with `wasmtime run --invoke 'sumsquared(2, 3)'` (WAVE syntax).
		// (s32 p0, s32 p1) -> s32 : 40 02 "p0" 7a "p1" 7a 00 7a
		assertThat(hex(ComponentWriter.funcTypeScalars(List.of("p0", "p1"),
				List.of(ComponentWriter.VT_S32, ComponentWriter.VT_S32), ComponentWriter.VT_S32)))
			.isEqualTo("40020270307a0270317a007a");
		// (f64 p0) -> f64 : f64 is 0x75 (0x74 is char)
		assertThat(hex(ComponentWriter.funcTypeScalars(List.of("p0"), List.of(ComponentWriter.VT_F64),
				ComponentWriter.VT_F64)))
			.isEqualTo("4001027030750075");
		// (bool p0) -> no result: the named-results form 01 with an empty vec
		assertThat(hex(ComponentWriter.funcTypeScalars(List.of("p0"), List.of(ComponentWriter.VT_BOOL), null)))
			.isEqualTo("40010270307f0100");
	}

	@Test
	void importsWasiInstanceLowersAndWiresCoreInstance() {
		final byte[] core = coreModuleUsesRand();
		final ComponentWriter c = new ComponentWriter();
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter
			.vec(List.of(ComponentWriter.instanceTypeWithFunc("get-random-u64", ComponentWriter.VT_U64))));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:random/random@0.2.0", 0))));
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceFunc(0, "get-random-u64"))));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLower(0))));
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, core);
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFunc("get-random-u64", 0))));
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of("rand"), List.of(0)))));
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.funcTypeResult(ComponentWriter.VT_S32))));
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(1, "run"))));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLift(1, 1))));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(List.of(ComponentWriter.exportFunc("run", 1))));

		assertThat(hex(c.toByteArray()))
			.isEqualTo("0061736d0d000100071b014202014000007704000e6765742d72616e646f6d2d75363401000a1d010018"
					+ "776173693a72616e646f6d2f72616e646f6d40302e322e3005000613010100000e6765742d72616e646f6d2d"
					+ "7536340805010100000001420061736d010000000109026000017e6000017f0217010472616e640e6765742d"
					+ "72616e646f6d2d7536340000030201010707010372756e00010a070105001000a70b02140101010e6765742d"
					+ "72616e646f6d2d7536340000020b010000010472616e6412000705014000007a060901000001010372756e08"
					+ "060100000100010b0901000372756e010100");
	}

	// --- async canonical ABI (WASI 0.3 / Preview 3) ---------------------------------
	// Golden bytes captured from `wasm-tools dump` of components validated with
	// `wasm-tools validate -f component-model -f cm-async` and executed with
	// `wasmtime run -W gc=y` (printed "hello from wasi 0.3"); nothing needs the
	// more-async-builtins or stackful-lift features anymore.

	@Test
	void definedStreamFutureResultTypeEncodings() {
		// stream<u8> = 66 01 7d ; future<type 4> = 65 01 04 ; result<_, type 1> = 6a 00
		// 01 01
		assertThat(hex(ComponentWriter.definedStream(ComponentWriter.VT_U8))).isEqualTo("66017d");
		assertThat(hex(ComponentWriter.definedFuture(4))).isEqualTo("650104");
		assertThat(hex(ComponentWriter.definedResultErr(1))).isEqualTo("6a000101");
	}

	@Test
	void asyncFuncTypeEncoding() {
		// async func () -> (result type 6) = 43 00 00 06 (vs the non-async 0x40 form)
		assertThat(hex(ComponentWriter.asyncFuncTypeResultType(6))).isEqualTo("43000006");
	}

	@Test
	void asyncFuncTypeScalarsEncoding() {
		// The async counterpart of funcTypeScalars (the `:async t` lift): the sync golden
		// bytes with the functype tag flipped 0x40 -> 0x43. Verified against `wasm-tools
		// print` of a component whose async-typed export was invoked with `wasmtime run
		// --invoke 'noisy-add(20, 22)'` (I/O inside the export works; text form
		// `(func async (param "p0" s32) ...)` round-trips through `wasm-tools parse`).
		assertThat(hex(ComponentWriter.asyncFuncTypeScalars(List.of("p0", "p1"),
				List.of(ComponentWriter.VT_S32, ComponentWriter.VT_S32), ComponentWriter.VT_S32)))
			.isEqualTo("43020270307a0270317a007a");
		// (string p0) -> string, the :async :string shape
		assertThat(hex(ComponentWriter.asyncFuncTypeScalars(List.of("p0"), List.of(ComponentWriter.VT_STRING),
				ComponentWriter.VT_STRING)))
			.isEqualTo("4301027030730073");
		// (s32 p0) -> no result: the named-results form 01 with an empty vec
		assertThat(hex(ComponentWriter.asyncFuncTypeScalars(List.of("p0"), List.of(ComponentWriter.VT_S32), null)))
			.isEqualTo("43010270307a0100");
	}

	@Test
	void canonStreamBuiltinEncodings() {
		assertThat(hex(ComponentWriter.canonStreamNew(3))).isEqualTo("0e03");
		assertThat(hex(ComponentWriter.canonStreamDropReadable(0))).isEqualTo("1300");
		assertThat(hex(ComponentWriter.canonStreamDropWritable(3))).isEqualTo("1403");
	}

	@Test
	void canonTaskAndWaitableSetEncodings() {
		// Golden bytes from `wasm-tools parse` + `dump` of a hand-written reference
		// (2026-07-16, wasm-tools on PATH): task.return { result: None } = 09 01 00,
		// task.return { result: Some(Type(0)) } = 09 00 00, each with an empty option
		// vec; the waitable-set built-ins and subtask.drop are bare tags.
		assertThat(hex(ComponentWriter.canonTaskReturnVoid())).isEqualTo("09010000");
		assertThat(hex(ComponentWriter.canonTaskReturnType(0))).isEqualTo("09000000");
		assertThat(hex(ComponentWriter.canonTaskReturnTypeMemoryReallocUtf8(1, 0, 0))).isEqualTo("090001030300040000");
		assertThat(hex(ComponentWriter.canonWaitableSetNew())).isEqualTo("1f");
		assertThat(hex(ComponentWriter.canonWaitableJoin())).isEqualTo("23");
		assertThat(hex(ComponentWriter.canonWaitableSetWait(0))).isEqualTo("200000");
		assertThat(hex(ComponentWriter.canonWaitableSetDrop())).isEqualTo("22");
		assertThat(hex(ComponentWriter.canonSubtaskDrop())).isEqualTo("0d");
		// Lower { func_index: 0, options: [Async, Memory(0), Realloc(0), UTF8] } --
		// the async option is tag 06.
		assertThat(hex(ComponentWriter.canonLowerAsyncMemoryReallocUtf8(0, 0, 0))).isEqualTo("01000004060300040000");
	}

	@Test
	void callbackAsyncAbiEncodings() {
		// Golden bytes from `wasm-tools parse` + `dump` of the hand-written
		// callback-ABI reference component (2026-07-16, validated with
		// `wasm-tools validate -f component-model,cm-async` ONLY and run on wasmtime 46
		// with NO feature flags -- base component-model-async, no more-async-builtins,
		// no stackful lifts):
		// StreamRead { ty: 0, options: [Async, Memory(0)] } = 0f 00 02 06 03 00
		assertThat(hex(ComponentWriter.canonStreamReadAsync(0, 0))).isEqualTo("0f0002060300");
		// StreamWrite { ty: 0, options: [Async, Memory(1)] } = 10 00 02 06 03 01
		assertThat(hex(ComponentWriter.canonStreamWriteAsync(0, 1))).isEqualTo("100002060301");
		// FutureRead { ty: 1, options: [Async, Memory(2)] } = 16 01 02 06 03 02
		assertThat(hex(ComponentWriter.canonFutureReadAsync(1, 2))).isEqualTo("160102060302");
		assertThat(hex(ComponentWriter.canonFutureReadAsync(1, 0, 0))).isEqualTo("1601030603000400");
		// FutureWrite { ty: 1, options: [Async, Memory(3)] } = 17 01 02 06 03 03
		assertThat(hex(ComponentWriter.canonFutureWriteAsync(1, 3))).isEqualTo("170102060303");
		assertThat(hex(ComponentWriter.canonFutureWriteAsyncUtf8(1, 0))).isEqualTo("17010306030000");
		// ContextGet { ty: I32, slot: 0 } = 0a 7f 00; ContextSet = 0b 7f 00
		assertThat(hex(ComponentWriter.canonContextGet(0))).isEqualTo("0a7f00");
		assertThat(hex(ComponentWriter.canonContextSet(0))).isEqualTo("0b7f00");
		// WaitableSetPoll { cancellable: false, memory: 4 } = 21 00 04
		assertThat(hex(ComponentWriter.canonWaitableSetPoll(4))).isEqualTo("210004");
		// StreamCancelRead { ty: 0, async_: false } = 11 00 00
		assertThat(hex(ComponentWriter.canonStreamCancelRead(0))).isEqualTo("110000");
		// FutureCancelRead { ty: 1, async_: false } = 18 01 00
		assertThat(hex(ComponentWriter.canonFutureCancelRead(1))).isEqualTo("180100");
		// Lift { core_func_index: 9, type_index: 2,
		// options: [Memory(5), UTF8, Async, Callback(10)] }
		// = 00 00 09 04 03 05 00 06 07 0a 02 -- the callback option is tag 07
		assertThat(hex(ComponentWriter.canonLiftMemoryUtf8AsyncCallback(9, 2, 5, 10)))
			.isEqualTo("0000090403050006070a02");
	}

	@Test
	void asyncLiftAndTaskReturnEncodings() {
		// Golden bytes from `wasm-tools dump` of the hand-written wasi:http@0.3.0
		// service reference component (2026-07-16, served + curl-verified on
		// wasmtime 46):
		// [type 16] Func { async_: true, params: [("request", Type(15))],
		// result: Some(Type(14)) } = 43 01 07 "request" 0f 00 0e
		assertThat(hex(ComponentWriter.asyncFuncTypeOf(java.util.List.of("request"),
				java.util.List.of(ComponentWriter.valTypeIndex(15)), ComponentWriter.valTypeIndex(14))))
			.isEqualTo("430107726571756573740f000e");
		// [core func 14] TaskReturn { result: Some(Type(14)),
		// options: [Memory(0), UTF8] } = 09 00 0e 02 03 00 00
		assertThat(hex(ComponentWriter.canonTaskReturnTypeMemoryUtf8(14, 0))).isEqualTo("09000e02030000");
	}

	@Test
	void definedTypeEncodings() {
		// Golden bytes from `wasm-tools dump` of a wasm-tools-built component importing
		// wasi:keyvalue/store@0.2.0-draft (the component-import reference probe).
		final byte[] string = ComponentWriter.valTypePrim(ComponentWriter.VT_STRING);
		final byte[] u64 = ComponentWriter.valTypePrim(ComponentWriter.VT_U64);
		assertThat(hex(ComponentWriter.definedListOf(string))).isEqualTo("7073");
		assertThat(hex(ComponentWriter.definedListOf(ComponentWriter.valTypePrim(ComponentWriter.VT_U8))))
			.isEqualTo("707d");
		assertThat(hex(ComponentWriter.definedOptionOf(u64))).isEqualTo("6b77");
		assertThat(hex(ComponentWriter.definedOptionOf(ComponentWriter.valTypeIndex(8)))).isEqualTo("6b08");
		assertThat(
				hex(ComponentWriter.definedResultOf(ComponentWriter.valTypeIndex(9), ComponentWriter.valTypeIndex(2))))
			.isEqualTo("6a01090102");
		assertThat(hex(ComponentWriter.definedResultOf(null, ComponentWriter.valTypeIndex(2)))).isEqualTo("6a000102");
		assertThat(hex(ComponentWriter.definedBorrow(0))).isEqualTo("6800");
		// variant error { no-such-store, access-denied, other(string) }
		final List<byte @Nullable []> payloads = new java.util.ArrayList<>();
		payloads.add(null);
		payloads.add(null);
		payloads.add(string);
		assertThat(hex(ComponentWriter.definedVariantOf(List.of("no-such-store", "access-denied", "other"), payloads)))
			.isEqualTo(
					"71030d6e6f2d737563682d73746f726500000d6163636573732d64656e6965" + "640000056f746865720173" + "00");
		// record key-response { keys: list<string>(3), cursor: option<u64>(4) }
		assertThat(hex(ComponentWriter.definedRecordOf(List.of("keys", "cursor"),
				List.of(ComponentWriter.valTypeIndex(3), ComponentWriter.valTypeIndex(4)))))
			.isEqualTo("7202046b6579730306637572736f7204");
		assertThat(hex(ComponentWriter.definedEnumOf(List.of("a", "b")))).isEqualTo("6d0201610162");
		assertThat(hex(ComponentWriter.definedFlagsOf(List.of("x")))).isEqualTo("6e010178");
		assertThat(hex(ComponentWriter.definedTupleOf(List.of(string, u64)))).isEqualTo("6f027377");
		// func (self: type7, key: string) -> type10
		assertThat(hex(ComponentWriter.funcTypeOf(List.of("self", "key"),
				List.of(ComponentWriter.valTypeIndex(7), string), ComponentWriter.valTypeIndex(10))))
			.isEqualTo("40020473656c6607036b6579" + "73000a");
	}

	@Test
	void instanceTypeAliasesAUsedTypeFromTheEnclosingComponent() {
		// The imported wasi:io/streams@0.2.0 instance type, byte-for-byte as wasm-tools
		// encodes it inside a real fetch component (258 bytes, captured with
		// `wasm-tools dump`). What it pins is the `use` rule: io/streams does not DEFINE
		// `error` -- it uses it from wasi:io/error -- so the type is ALIASED IN from the
		// enclosing component (which projected it out of the wasi:io/error instance as
		// component type 14) instead of being re-declared. A resource is nominal, so a
		// structural re-declaration would mint a second, unrelated `error` that the host
		// cannot satisfy. The alias appends to the local type index space, like a type
		// declaration.
		final byte[] u8 = ComponentWriter.valTypePrim(ComponentWriter.VT_U8);
		final byte[] u64 = ComponentWriter.valTypePrim(ComponentWriter.VT_U64);
		final List<byte @Nullable []> streamErrorCases = new java.util.ArrayList<>();
		streamErrorCases.add(ComponentWriter.valTypeIndex(3));
		streamErrorCases.add(null);
		final List<byte[]> decls = List.of(
				// local 0: the output-stream resource, defined by this interface
				ComponentWriter.instanceDeclExportResource("output-stream"),
				// local 1: wasi:io/error's `error` resource, aliased from outer type 14
				ComponentWriter.instanceDeclAliasOuterType(1, 14),
				// local 2: export "error" = eq(1) -- the name io/streams `use`s it under
				ComponentWriter.instanceDeclExportTypeEq("error", 1),
				// local 3: own<error>, local 4: variant stream-error, local 5: its export
				ComponentWriter.instanceDeclType(ComponentWriter.definedOwn(2)),
				ComponentWriter.instanceDeclType(
						ComponentWriter.definedVariantOf(List.of("last-operation-failed", "closed"), streamErrorCases)),
				ComponentWriter.instanceDeclExportTypeEq("stream-error", 4),
				// local 6: the input-stream resource, also defined here
				ComponentWriter.instanceDeclExportResource("input-stream"),
				// local 7: borrow<input-stream>, 8: list<u8>, 9: result<8, 5>, 10: func
				ComponentWriter.instanceDeclType(ComponentWriter.definedBorrow(6)),
				ComponentWriter.instanceDeclType(ComponentWriter.definedListOf(u8)),
				ComponentWriter.instanceDeclType(ComponentWriter.definedResultOf(ComponentWriter.valTypeIndex(8),
						ComponentWriter.valTypeIndex(5))),
				ComponentWriter.instanceDeclType(ComponentWriter.funcTypeOf(List.of("self", "len"),
						List.of(ComponentWriter.valTypeIndex(7), u64), ComponentWriter.valTypeIndex(9))),
				ComponentWriter.instanceDeclExportFunc("[method]input-stream.blocking-read", 10),
				// local 11: borrow<output-stream>, 12: result<_, 5>, 13: func
				ComponentWriter.instanceDeclType(ComponentWriter.definedBorrow(0)),
				ComponentWriter
					.instanceDeclType(ComponentWriter.definedResultOf(null, ComponentWriter.valTypeIndex(5))),
				ComponentWriter.instanceDeclType(ComponentWriter.funcTypeOf(List.of("self", "contents"),
						List.of(ComponentWriter.valTypeIndex(11), ComponentWriter.valTypeIndex(8)),
						ComponentWriter.valTypeIndex(12))),
				ComponentWriter.instanceDeclExportFunc("[method]output-stream.blocking-write-and-flush", 13));
		final String golden = "421004000d6f75747075742d73747265616d0301020302010e0400056572726f"
				+ "72030001016902017102156c6173742d6f7065726174696f6e2d6661696c6564"
				+ "01030006636c6f736564000004000c73747265616d2d6572726f720300040400"
				+ "0c696e7075742d73747265616d030101680601707d016a010801050140020473"
				+ "656c6607036c656e7700090400225b6d6574686f645d696e7075742d73747265"
				+ "616d2e626c6f636b696e672d72656164010a016800016a000105014002047365"
				+ "6c660b08636f6e74656e747308000c04002e5b6d6574686f645d6f7574707574"
				+ "2d73747265616d2e626c6f636b696e672d77726974652d616e642d666c757368" + "010d";
		assertThat(hex(ComponentWriter.instanceTypeOf(decls))).isEqualTo(golden);
	}

	@Test
	void instanceTypeMatchesWasmToolsReference() {
		// Rebuild the imported wasi:keyvalue/store@0.2.0-draft instance type exactly as
		// `wasm-tools component new` encodes it, and pin the 393 golden bytes captured
		// from the reference probe. Local type index space: type declarations AND
		// type-bound export declarations append to it; function exports do not.
		final byte[] str = ComponentWriter.valTypePrim(ComponentWriter.VT_STRING);
		final byte[] boolVt = ComponentWriter.valTypePrim(ComponentWriter.VT_BOOL);
		final List<byte @Nullable []> errCases = new java.util.ArrayList<>();
		errCases.add(null);
		errCases.add(null);
		errCases.add(str);
		final List<byte[]> decls = List.of(
				// local 0: the bucket resource (a sub-resource-bound export)
				ComponentWriter.instanceDeclExportResource("bucket"),
				// local 1: variant error
				ComponentWriter.instanceDeclType(
						ComponentWriter.definedVariantOf(List.of("no-such-store", "access-denied", "other"), errCases)),
				// local 2: export "error" = eq(1)
				ComponentWriter.instanceDeclExportTypeEq("error", 1),
				// local 3: list<string>, local 4: option<u64>, local 5: record
				ComponentWriter.instanceDeclType(ComponentWriter.definedListOf(str)),
				ComponentWriter.instanceDeclType(
						ComponentWriter.definedOptionOf(ComponentWriter.valTypePrim(ComponentWriter.VT_U64))),
				ComponentWriter.instanceDeclType(ComponentWriter.definedRecordOf(List.of("keys", "cursor"),
						List.of(ComponentWriter.valTypeIndex(3), ComponentWriter.valTypeIndex(4)))),
				// local 6: export "key-response" = eq(5)
				ComponentWriter.instanceDeclExportTypeEq("key-response", 5),
				// local 7: borrow<bucket>, local 8: list<u8>, local 9: option(8),
				// local 10: result(9, 2), local 11: func get
				ComponentWriter.instanceDeclType(ComponentWriter.definedBorrow(0)),
				ComponentWriter.instanceDeclType(
						ComponentWriter.definedListOf(ComponentWriter.valTypePrim(ComponentWriter.VT_U8))),
				ComponentWriter.instanceDeclType(ComponentWriter.definedOptionOf(ComponentWriter.valTypeIndex(8))),
				ComponentWriter.instanceDeclType(ComponentWriter.definedResultOf(ComponentWriter.valTypeIndex(9),
						ComponentWriter.valTypeIndex(2))),
				ComponentWriter.instanceDeclType(ComponentWriter.funcTypeOf(List.of("self", "key"),
						List.of(ComponentWriter.valTypeIndex(7), str), ComponentWriter.valTypeIndex(10))),
				ComponentWriter.instanceDeclExportFunc("[method]bucket.get", 11),
				// local 12: result(_, 2), local 13: func set
				ComponentWriter
					.instanceDeclType(ComponentWriter.definedResultOf(null, ComponentWriter.valTypeIndex(2))),
				ComponentWriter.instanceDeclType(ComponentWriter.funcTypeOf(List.of("self", "key", "value"),
						List.of(ComponentWriter.valTypeIndex(7), str, ComponentWriter.valTypeIndex(8)),
						ComponentWriter.valTypeIndex(12))),
				ComponentWriter.instanceDeclExportFunc("[method]bucket.set", 13),
				// local 14: func delete
				ComponentWriter.instanceDeclType(ComponentWriter.funcTypeOf(List.of("self", "key"),
						List.of(ComponentWriter.valTypeIndex(7), str), ComponentWriter.valTypeIndex(12))),
				ComponentWriter.instanceDeclExportFunc("[method]bucket.delete", 14),
				// local 15: result(bool, 2), local 16: func exists
				ComponentWriter
					.instanceDeclType(ComponentWriter.definedResultOf(boolVt, ComponentWriter.valTypeIndex(2))),
				ComponentWriter.instanceDeclType(ComponentWriter.funcTypeOf(List.of("self", "key"),
						List.of(ComponentWriter.valTypeIndex(7), str), ComponentWriter.valTypeIndex(15))),
				ComponentWriter.instanceDeclExportFunc("[method]bucket.exists", 16),
				// local 17: result(6, 2), local 18: func list-keys
				ComponentWriter.instanceDeclType(ComponentWriter.definedResultOf(ComponentWriter.valTypeIndex(6),
						ComponentWriter.valTypeIndex(2))),
				ComponentWriter.instanceDeclType(ComponentWriter.funcTypeOf(List.of("self", "cursor"),
						List.of(ComponentWriter.valTypeIndex(7), ComponentWriter.valTypeIndex(4)),
						ComponentWriter.valTypeIndex(17))),
				ComponentWriter.instanceDeclExportFunc("[method]bucket.list-keys", 18),
				// local 19: own<bucket>, local 20: result(19, 2), local 21: func open
				ComponentWriter.instanceDeclType(ComponentWriter.definedOwn(0)),
				ComponentWriter.instanceDeclType(ComponentWriter.definedResultOf(ComponentWriter.valTypeIndex(19),
						ComponentWriter.valTypeIndex(2))),
				ComponentWriter.instanceDeclType(ComponentWriter.funcTypeOf(List.of("identifier"), List.of(str),
						ComponentWriter.valTypeIndex(20))),
				ComponentWriter.instanceDeclExportFunc("open", 21));
		final String golden = "421c0400066275636b657403010171030d6e6f2d737563682d73746f72650000"
				+ "0d6163636573732d64656e6965640000056f746865720173000400056572726f"
				+ "72030001017073016b77017202046b6579730306637572736f720404000c6b65"
				+ "792d726573706f6e736503000501680001707d016b08016a0109010201400204"
				+ "73656c6607036b657973000a0400125b6d6574686f645d6275636b65742e6765"
				+ "74010b016a0001020140030473656c6607036b6579730576616c756508000c04"
				+ "00125b6d6574686f645d6275636b65742e736574010d0140020473656c660703"
				+ "6b657973000c0400155b6d6574686f645d6275636b65742e64656c657465010e"
				+ "016a017f01020140020473656c6607036b657973000f0400155b6d6574686f64"
				+ "5d6275636b65742e6578697374730110016a010601020140020473656c660706"
				+ "637572736f720400110400185b6d6574686f645d6275636b65742e6c6973742d"
				+ "6b6579730112016900016a011301020140010a6964656e746966696572730014" + "0400046f70656e0115";
		assertThat(hex(ComponentWriter.instanceTypeOf(decls))).isEqualTo(golden);
	}

	@Test
	void canonFutureBuiltinEncodings() {
		assertThat(hex(ComponentWriter.canonFutureNew(2))).isEqualTo("1502");
		assertThat(hex(ComponentWriter.canonFutureDropReadable(5))).isEqualTo("1a05");
		assertThat(hex(ComponentWriter.canonFutureDropWritable(2))).isEqualTo("1b02");
	}

}
