package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

import am.ik.rontolisp.codegen.wasm.WasmFdlibmRuntimeBuilder.Fn;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.wasm.ExternalKind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.images.builder.Transferable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the WASM fdlibm port to {@code java.lang.StrictMath} bit for bit: a standalone
 * module holding every {@link Fn} evaluates them over the inputs below and prints each
 * result's raw bits; the expectations are the JDK's own fdlibm. Every NaN is one NaN here
 * (the sign and payload of a computed NaN are the engine's to choose), and nothing else
 * is forgiven.
 */
@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
class WasmFdlibmRuntimeBuilderTest {

	private static final HostWasmtime wasmtime = HostWasmtime.INSTANCE;

	private static final int INPUT_BASE = 4096;

	private static final int TABLES_BASE = 0x40000;

	private static final int OUT_BASE = 0x50000;

	private static final int IOV_ADDR = 16;

	private static final int NWRITTEN_ADDR = 32;

	private static final int LINE = 17;

	// The unary functions the driver prints, in output order, then the binary ones.
	private static final Fn[] UNARY = { Fn.EXP, Fn.EXPM1, Fn.LOG, Fn.LOG1P, Fn.SIN, Fn.COS, Fn.TAN, Fn.ASIN, Fn.ACOS,
			Fn.ATAN, Fn.SINH, Fn.COSH, Fn.TANH };

	private static final Fn[] BINARY = { Fn.ATAN2, Fn.POW, Fn.HYPOT };

	private static DoubleUnaryOperator strict(Fn fn) {
		return switch (fn) {
			case EXP -> StrictMath::exp;
			case EXPM1 -> StrictMath::expm1;
			case LOG -> StrictMath::log;
			case LOG1P -> StrictMath::log1p;
			case SIN -> StrictMath::sin;
			case COS -> StrictMath::cos;
			case TAN -> StrictMath::tan;
			case ASIN -> StrictMath::asin;
			case ACOS -> StrictMath::acos;
			case ATAN -> StrictMath::atan;
			case SINH -> StrictMath::sinh;
			case COSH -> StrictMath::cosh;
			case TANH -> StrictMath::tanh;
			default -> throw new IllegalArgumentException(fn.toString());
		};
	}

	private static DoubleBinaryOperator strict2(Fn fn) {
		return switch (fn) {
			case ATAN2 -> StrictMath::atan2;
			case POW -> StrictMath::pow;
			case HYPOT -> StrictMath::hypot;
			default -> throw new IllegalArgumentException(fn.toString());
		};
	}

	@Test
	void everyFunctionAnswersStrictMathsBitsOverRandomAndSpecialArguments() throws Exception {
		double[] inputs = inputs();
		byte[] module = module(inputs);
		Path dir = Files.createTempDirectory("fdlibm");
		String path = dir.resolve("fdlibm.wasm").toString();
		wasmtime.copyFileToContainer(Transferable.of(module), path);
		HostWasmtime.ExecResult result = wasmtime.execInContainer("wasmtime", "run", path);
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		String[] lines = result.getStdout().split("\n");
		int perInput = UNARY.length + BINARY.length;
		assertThat(lines.length).isEqualTo(inputs.length * perInput);
		Map<Fn, Integer> counts = new EnumMap<>(Fn.class);
		List<String> examples = new ArrayList<>();
		int line = 0;
		for (int i = 0; i < inputs.length; i++) {
			double x = inputs[i];
			double x2 = inputs[(i + 1) % inputs.length];
			for (Fn fn : UNARY) {
				check(counts, examples, fn, x, Double.NaN, strict(fn).applyAsDouble(x), lines[line++]);
			}
			for (Fn fn : BINARY) {
				check(counts, examples, fn, x, x2, strict2(fn).applyAsDouble(x, x2), lines[line++]);
			}
		}
		assertThat(counts).as("results differing from StrictMath, per function; examples: %s", examples).isEmpty();
	}

	private static void check(Map<Fn, Integer> counts, List<String> examples, Fn fn, double x, double y,
			double expected, String hex) {
		long got = Long.parseUnsignedLong(hex, 16);
		long want = Double.doubleToRawLongBits(expected);
		if (Double.isNaN(expected) && Double.isNaN(Double.longBitsToDouble(got))) {
			return;
		}
		if (got != want) {
			int n = counts.merge(fn, 1, Integer::sum);
			if (n <= 3) {
				examples.add(fn + "(" + x + (Double.isNaN(y) ? "" : ", " + y) + ") = " + Double.longBitsToDouble(got)
						+ " [" + hex + "], StrictMath " + expected + " [" + Long.toHexString(want) + "]");
			}
		}
	}

	// Random arguments in the ranges each function is used over (and the raw bit
	// patterns that reach every edge), then the special values, whose pairs feed the
	// binary functions through the consecutive-input rule.
	private static double[] inputs() {
		Random random = new Random(842);
		List<Double> out = new ArrayList<>();
		for (int i = 0; i < 12000; i++) {
			double u = random.nextDouble();
			switch (i % 8) {
				case 0 -> out.add(Double.longBitsToDouble(random.nextLong()));
				case 1 -> out.add(u * 20 - 10);
				case 2 -> out.add(u * 2000 - 1000);
				case 3 -> out.add(Math.scalb(1 + random.nextDouble(), random.nextInt(2000) - 1000)
						* (random.nextBoolean() ? 1 : -1));
				case 4 -> out.add(u * 2 - 1);
				case 5 -> out.add((random.nextInt(2000) - 1000) * (Math.PI / 2) + (u - 0.5) * 1e-6);
				case 6 -> out.add((u - 0.5) * 2e22);
				default -> out.add(random.nextInt(41) - 20.0);
			}
		}
		double[] specials = { 0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.0, -2.0, 1.5, 3.0, 10.0, 1e-300, -1e-300, 1e300,
				-1e300, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
				Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, Math.PI, -Math.PI, Math.PI / 2,
				-Math.PI / 2, 3 * Math.PI / 2, 2 * Math.PI, 0x1.921fb54442d18p0, 0x1.921fb54442d19p0, 22.0, -22.0,
				709.78, 709.7827128933841, -745.13, 710.0, -746.0, 0x1p53, 0x1p-53, 0x1p63, 0x1p-1074, 0x1p-1000,
				0x1p1000, 0.9999999, 1.0000001, 0.3, 0.7, 0.41422, -0.2929, 1e22, 1e6, 8.2e5, 1e-8, 5.0, 0.25, -0.25,
				4.0, 100.0, 0.1, 1e-16, 0x1.00000ffffffffp31, 0x1p31, -0x1p31 };
		for (double a : specials) {
			for (double b : specials) {
				out.add(a);
				out.add(b);
			}
		}
		double[] arr = new double[out.size()];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = out.get(i);
		}
		return arr;
	}

	// A module holding every fdlibm function and a _start that evaluates them over
	// the inputs staged in a data segment, printing each result's bits as 16 hex digits.
	private static byte[] module(double[] inputs) {
		int fdWrite = 0;
		int fnBase = 1;
		int hex = fnBase + Fn.values().length;
		int start = hex + 1;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write("\0asm").writeLittleEndian4(1);
		w.writeTypeSection(types -> {
			types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 }); // 0
			types.addFunc(new Type[] {}, new Type[] {}); // 1
			types.addFunc(new Type[] { Type.F64 }, new Type[] { Type.F64 }); // 2
			types.addFunc(new Type[] { Type.F64, Type.F64 }, new Type[] { Type.F64 }); // 3
			types.addFunc(new Type[] { Type.F64, Type.F64, Type.I32 }, new Type[] { Type.F64 }); // 4
			types.addFunc(new Type[] { Type.F64 }, new Type[] { Type.I32 }); // 5
			types.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 }); // 6
			types.addFunc(new Type[] { Type.I64, Type.I32 }, new Type[] {}); // 7
		});
		w.writeImportSection(
				imports -> imports.addImport("wasi_snapshot_preview1", "fd_write", ExternalKind.FUNCTION, 0));
		w.writeFunction(funcs -> {
			for (Fn fn : Fn.values()) {
				funcs.addFunction(typeOf(fn));
			}
			funcs.addFunction(7);
			funcs.addFunction(1);
		});
		int outBytes = inputs.length * (UNARY.length + BINARY.length) * LINE;
		int pages = (OUT_BASE + outBytes + 0xffff) >>> 16;
		w.writeMemory(mem -> mem.addMemory(pages));
		w.writeExport(exports -> {
			exports.addExport("memory", ExternalKind.MEMORY, 0);
			exports.addExport("_start", ExternalKind.FUNCTION, start);
		});
		w.writeCode(code -> {
			for (Fn fn : Fn.values()) {
				code.addFunction(WasmFdlibmRuntimeBuilder.build(fn, f -> fnBase + f.ordinal(), TABLES_BASE));
			}
			code.addFunction(hexBody());
			code.addFunction(startBody(inputs.length, fnBase, hex, fdWrite));
		});
		ByteBuffer buf = ByteBuffer.allocate(inputs.length * 8).order(ByteOrder.LITTLE_ENDIAN);
		for (double d : inputs) {
			buf.putDouble(d);
		}
		w.writeDataSection(data -> {
			data.addActiveData(0, INPUT_BASE, buf.array());
			data.addActiveData(0, TABLES_BASE, WasmFdlibmRuntimeBuilder.tables());
		});
		return out.toByteArray();
	}

	private static int typeOf(Fn fn) {
		if (fn.result == WasmFdlibmRuntimeBuilder.Ty.I) {
			return fn.params.length == 1 ? 5 : 6;
		}
		return switch (fn.params.length) {
			case 1 -> 2;
			case 2 -> 3;
			default -> 4;
		};
	}

	// hex(bits i64, ptr i32): the 16 hex digits of bits at ptr, then a newline.
	private static byte[] hexBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.writeUnsignedLeb128(1);
		w.writeUnsignedLeb128(2);
		w.write(Type.I32);
		int bits = 0, ptr = 1, k = 2, n = 3;
		// k = 0; loop { n = (i32)(bits >> (60 - 4k)) & 15; store8 ptr+k, n + (n < 10 ?
		// 48 : 87); k++; br_if k < 16 }
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(k);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		w.write(Instruction.I32_CONST).writeSignedLeb128(60);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(k);
		w.write(Instruction.I32_CONST).writeSignedLeb128(2);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I64_EXTEND_S_I32);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST).writeSignedLeb128(15);
		w.write(Instruction.I32_AND);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(n);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(ptr);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(k);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(n);
		w.write(Instruction.I32_CONST).writeSignedLeb128(48);
		w.write(Instruction.I32_CONST).writeSignedLeb128(87);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(n);
		w.write(Instruction.I32_CONST).writeSignedLeb128(10);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SELECT);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8).writeUnsignedLeb128(0).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(k);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.TEE_LOCAL).writeUnsignedLeb128(k);
		w.write(Instruction.I32_CONST).writeSignedLeb128(16);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF).writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(ptr);
		w.write(Instruction.I32_CONST).writeSignedLeb128(10);
		w.write(Instruction.I32_STORE8).writeUnsignedLeb128(0).writeUnsignedLeb128(16);
		w.write(Instruction.END);
		return b.toByteArray();
	}

	// _start: for each input, print every unary function of it and every binary
	// function of it and its successor, then fd_write the whole buffer.
	private static byte[] startBody(int count, int fnBase, int hex, int fdWrite) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.writeUnsignedLeb128(2);
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);
		w.writeUnsignedLeb128(2);
		w.write(Type.F64);
		int i = 0, p = 1, off = 2, x = 3, x2 = 4;
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(OUT_BASE);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(p);
		w.write(Instruction.LOOP, 0x40);
		// x = inputs[i]; x2 = inputs[(i + 1) % count]
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(3);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.F64_LOAD).writeUnsignedLeb128(3).writeUnsignedLeb128(INPUT_BASE);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(x);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(count);
		w.write(Instruction.I32_REM_S);
		w.write(Instruction.I32_CONST).writeSignedLeb128(3);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.F64_LOAD).writeUnsignedLeb128(3).writeUnsignedLeb128(INPUT_BASE);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(x2);
		for (Fn fn : UNARY) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(x);
			w.write(Instruction.CALL).writeUnsignedLeb128(fnBase + fn.ordinal());
			w.write(Instruction.I64_REINTERPRET_F64);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(p);
			w.write(Instruction.CALL).writeUnsignedLeb128(hex);
			advance(w, p);
		}
		for (Fn fn : BINARY) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(x);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(x2);
			w.write(Instruction.CALL).writeUnsignedLeb128(fnBase + fn.ordinal());
			w.write(Instruction.I64_REINTERPRET_F64);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(p);
			w.write(Instruction.CALL).writeUnsignedLeb128(hex);
			advance(w, p);
		}
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.TEE_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(count);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF).writeUnsignedLeb128(0);
		w.write(Instruction.END);
		// off = 0; loop { iov = {OUT_BASE + off, p - OUT_BASE - off}; fd_write(1, iov,
		// 1, nwritten); off += nwritten; br_if off < p - OUT_BASE }
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(off);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(IOV_ADDR);
		w.write(Instruction.I32_CONST).writeSignedLeb128(OUT_BASE);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(off);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE).writeUnsignedLeb128(2).writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST).writeSignedLeb128(IOV_ADDR);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(p);
		w.write(Instruction.I32_CONST).writeSignedLeb128(OUT_BASE);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(off);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE).writeUnsignedLeb128(2).writeUnsignedLeb128(4);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_CONST).writeSignedLeb128(IOV_ADDR);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_CONST).writeSignedLeb128(NWRITTEN_ADDR);
		w.write(Instruction.CALL).writeUnsignedLeb128(fdWrite);
		w.write(Instruction.DROP);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(off);
		w.write(Instruction.I32_CONST).writeSignedLeb128(NWRITTEN_ADDR);
		w.write(Instruction.I32_LOAD).writeUnsignedLeb128(2).writeUnsignedLeb128(0);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.TEE_LOCAL).writeUnsignedLeb128(off);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(p);
		w.write(Instruction.I32_CONST).writeSignedLeb128(OUT_BASE);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF).writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		return b.toByteArray();
	}

	private static void advance(WasmWriter w, int p) {
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(p);
		w.write(Instruction.I32_CONST).writeSignedLeb128(LINE);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(p);
	}

}
