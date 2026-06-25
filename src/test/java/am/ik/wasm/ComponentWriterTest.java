package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;

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
	// `wasm-tools validate -f component-model -f cm-async -f cm-async-stackful
	// -f cm-more-async-builtins` and executed with `wasmtime run -W
	// component-model-async=y -W component-model-async-stackful=y
	// -W component-model-more-async-builtins=y -W gc=y` (printed "hello from wasi 0.3").

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
	void canonStreamBuiltinEncodings() {
		assertThat(hex(ComponentWriter.canonStreamNew(3))).isEqualTo("0e03");
		// stream.read ty 0, options [Memory(1)] = 0f 00 (count) 01 (tag) 03 (mem) 01
		assertThat(hex(ComponentWriter.canonStreamRead(0, 1))).isEqualTo("0f00010301");
		assertThat(hex(ComponentWriter.canonStreamWrite(3, 0))).isEqualTo("1003010300");
		assertThat(hex(ComponentWriter.canonStreamDropReadable(0))).isEqualTo("1300");
		assertThat(hex(ComponentWriter.canonStreamDropWritable(3))).isEqualTo("1403");
	}

	@Test
	void canonFutureBuiltinEncodings() {
		assertThat(hex(ComponentWriter.canonFutureNew(2))).isEqualTo("1502");
		assertThat(hex(ComponentWriter.canonFutureRead(5, 1))).isEqualTo("1605010301");
		// future.read ty 5, options [Memory(0), Realloc(0)] = 16 05 02 03 00 04 00
		assertThat(hex(ComponentWriter.canonFutureRead(5, 0, 0))).isEqualTo("16050203000400");
		assertThat(hex(ComponentWriter.canonFutureWrite(2, 0))).isEqualTo("1702010300");
		assertThat(hex(ComponentWriter.canonFutureDropReadable(5))).isEqualTo("1a05");
		assertThat(hex(ComponentWriter.canonFutureDropWritable(2))).isEqualTo("1b02");
	}

}
