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

}
