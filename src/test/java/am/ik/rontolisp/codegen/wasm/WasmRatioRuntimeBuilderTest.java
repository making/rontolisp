package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WasmRatioRuntimeBuilderTest {

	// The body's leading bytes after the local declarations: local.get 0; ref.test i31;
	// local.get 1; ref.test i31; i32.and; if (void).
	private static byte[] i31Head() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		return out.toByteArray();
	}

	private static boolean opensWithI31Head(byte[] body) {
		byte[] head = i31Head();
		// body[0] is the local-declaration count: 0, no extra locals.
		return body[0] == 0 && Arrays.equals(body, 1, 1 + head.length, head, 0, head.length);
	}

	@Test
	void addSubAndMulOpenWithAnI31Head() {
		assertThat(
				opensWithI31Head(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_ADD, Instruction.F64_ADD)))
			.isTrue();
		assertThat(
				opensWithI31Head(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_SUB, Instruction.F64_SUB)))
			.isTrue();
		assertThat(
				opensWithI31Head(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_MUL, Instruction.F64_MUL)))
			.isTrue();
	}

	@Test
	void theSizeLevelNormalizesEveryRatioThroughTheTierAwareSteps() {
		// _rat_new's i32 normalization of two i31 operands is a speed path only -- the
		// _big_* steps after it answer every operand -- so --optimize=size drops it.
		assertThat(WasmRatioRuntimeBuilder.buildRatNewBody(false, false).length)
			.isLessThan(WasmRatioRuntimeBuilder.buildRatNewBody(true, false).length);
	}

}
