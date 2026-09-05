package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code checkpoint} and {@code safetensors} packages ({@code checkpoint.lisp} /
 * {@code safetensors.lisp}, spliced/loaded by {@link CheckpointLibrary} /
 * {@link SafetensorsLibrary}): the interpreter half. The cross-backend half is
 * {@code examples/llm/safetensors-check.lisp} over its checked-in fixture
 * ({@code examples.yaml}), and the real thing is TinyLlama's BF16 checkpoint through
 * {@code examples/llm/llm.lisp} ({@code .kb/checkpoint-readers.md}).
 */
class SafetensorsLibraryTest {

	@TempDir
	Path dir;

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	// --- the libraries are reachable with nothing required -----------------------

	@Test
	void aCheckpointCallOnABareEvaluatorLoadsTheLibrary() {
		assertThat(eval("(checkpoint:make-tensor 3 'single-float)").print()).isEqualTo("#f(0.0 0.0 0.0)");
		assertThat(eval("(array-dimensions (checkpoint:make-tensor '(2 3) 'double-float))").print()).isEqualTo("(2 3)");
	}

	@Test
	void makeTensorRefusesAnElementTypeThatWouldNotBePacked() {
		assertThatThrownBy(() -> eval("(checkpoint:make-tensor 3 'fixnum)")).hasMessageContaining("not a packed float");
	}

	@Test
	void theCompilePathSplicesEachLibraryOnlyWhenTheProgramUsesIt() {
		List<LispVal> unrelated = LispReader.readAllFromString("(print (+ 1 2))");
		assertThat(SafetensorsLibrary.process(unrelated)).isSameAs(unrelated);
		assertThat(CheckpointLibrary.process(unrelated)).isSameAs(unrelated);
		// the reader pulls the staging in: safetensors: is spliced first, and the
		// checkpoint: references inside its definitions make the second pass fire
		List<LispVal> user = LispReader.readAllFromString("(print (safetensors:read \"model.safetensors\"))");
		List<LispVal> spliced = CheckpointLibrary.process(SafetensorsLibrary.process(user));
		assertThat(definitionNames(spliced)).contains("SAFETENSORS:READ", "CHECKPOINT:STAGE-FLOAT-BITS",
				"CHECKPOINT:MAKE-TENSOR");
		// a bare exported name under (in-package checkpoint) counts too
		List<LispVal> inPackage = LispReader
			.readAllFromString("(in-package :checkpoint) (print (make-tensor 3 'single-float))");
		assertThat(CheckpointLibrary.process(inPackage)).hasSizeGreaterThan(inPackage.size());
	}

	@Test
	void theSplicedDefinitionsArePrunable() {
		List<LispVal> pruned = LibraryDefunPruner.prune(CheckpointLibrary
			.process(LispReader.readAllFromString("(print (checkpoint:make-tensor 3 'single-float))")));
		List<String> names = definitionNames(pruned);
		assertThat(names).contains("CHECKPOINT:MAKE-TENSOR");
		assertThat(names).doesNotContain("CHECKPOINT:STAGE-FLOAT-BITS", "CHECKPOINT:SKIP-BYTES");
	}

	// --- a file, read -------------------------------------------------------------

	@Test
	void readsEveryWidthIntoPackedArraysAndRefusesTheRest() throws IOException {
		Path file = writeFixture(this.dir.resolve("model.safetensors"));
		String path = file.toString().replace("\\", "/");
		// the header alone
		assertThat(eval("(nth-value 1 (safetensors:header \"" + path + "\"))").print()).isEqualTo("248");
		assertThat(eval("(mapcar #'first (safetensors:entries (safetensors:header \"" + path + "\")))").print())
			.isEqualTo("(\"a.weight\" \"b.weight\" \"c\" \"ids\")");
		// every dtype, in the shape and width the file has
		String read = "(safetensors:read \"" + path + "\" :only (lambda (n) (not (string= n \"ids\"))))";
		assertThat(eval("(gethash \"a.weight\" " + read + ")").print()).isEqualTo("#f((1.5 -2.0) (0.25 8.0))");
		assertThat(eval("(gethash \"b.weight\" " + read + ")").print()).isEqualTo("#f(1.0 -0.5 65504.0)");
		assertThat(eval("(gethash \"c\" " + read + ")").print()).isEqualTo("#f(-2.5)");
		assertThat(eval("(array-element-type (gethash \"c\" " + read + "))").print()).isEqualTo("SINGLE-FLOAT");
		// into doubles
		assertThat(eval("(gethash \"b.weight\" (safetensors:read \"" + path
				+ "\" :only (lambda (n) (string= n \"b.weight\")) :element-type 'double-float))")
			.print()).isEqualTo("#d(1.0 -0.5 65504.0)");
		// the I64 tensor is refused by name when it is not excluded
		assertThatThrownBy(() -> eval("(safetensors:read \"" + path + "\")")).hasMessageContaining("ids is I64");
	}

	// :element-type 'bfloat16 is the third destination (interpreter and JVM only; every
	// other backend refuses the width by name). What each dtype costs to get there is
	// the point, and it is NOT uniform -- the frozen interface in .todo/675 said the
	// values would be EQUAL to the single-float read because "widening is exact", which
	// holds for a BF16 SOURCE and for nothing else:
	//
	// BF16 source -> the file's own bytes, one read-sequence, no conversion: equal.
	// F32 source -> narrowed as it streams; equal only where the value already fits
	// eight mantissa bits, which the fixture's four F32 values do.
	// F16 source -> staged through an f32 scratch and narrowed; 65504 is the f16
	// maximum and is NOT a bfloat16, so it rounds to nearest even and
	// comes back 65536.
	@Test
	void readsIntoABfloat16DestinationAndNarrowsOnlyWhereTheWidthRequiresIt() throws IOException {
		Path file = writeFixture(this.dir.resolve("model.safetensors"));
		String path = file.toString().replace("\\", "/");
		String read = "(safetensors:read \"" + path + "\" :element-type 'bfloat16"
				+ " :only (lambda (n) (not (string= n \"ids\"))))";
		assertThat(eval("(array-element-type (gethash \"c\" " + read + "))").print()).isEqualTo("BFLOAT16");
		// BF16 -> bfloat16: the same two bytes, so the value is the one the
		// single-float read gives.
		assertThat(eval("(gethash \"c\" " + read + ")").print()).isEqualTo("#bf16(-2.5)");
		// F32 -> bfloat16, narrowed while streaming, never through a whole f32 tensor.
		assertThat(eval("(gethash \"a.weight\" " + read + ")").print()).isEqualTo("#bf16((1.5 -2.0) (0.25 8.0))");
		// F16 -> bfloat16 through the f32 scratch; the f16 maximum does not survive the
		// narrower mantissa, and round-to-nearest-even is what decides where it lands.
		assertThat(eval("(gethash \"b.weight\" " + read + ")").print()).isEqualTo("#bf16(1.0 -0.5 65500.0)");
		// The same pattern, read as a number rather than printed: 0x4780 is 65536.
		assertThat(eval("(rontolisp:bfloat16-bits (row-major-aref (gethash \"b.weight\" " + read + ") 2))").print())
			.isEqualTo("18304");
	}

	/**
	 * A four-tensor file: a 2x2 F32, a 3-element F16 (with the f16 maximum), a
	 * one-element BF16 and an I64 to refuse, with a header padded to 8 bytes the way
	 * {@code safetensors} pads it.
	 */
	private static Path writeFixture(Path file) throws IOException {
		ByteBuffer data = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		data.putFloat(1.5f).putFloat(-2.0f).putFloat(0.25f).putFloat(8.0f); // a.weight,
																			// 16 bytes
		data.putShort((short) 0x3C00).putShort((short) 0xB800).putShort((short) 0x7BFF); // b.weight,
																							// 6
		data.putShort((short) 0xC020); // c = -2.5 in bf16, 2 bytes
		data.putLong(7L).putLong(-1L); // ids, 16 bytes
		String header = "{\"a.weight\":{\"dtype\":\"F32\",\"shape\":[2,2],\"data_offsets\":[0,16]},"
				+ "\"b.weight\":{\"dtype\":\"F16\",\"shape\":[3],\"data_offsets\":[16,22]},"
				+ "\"c\":{\"dtype\":\"BF16\",\"shape\":[1],\"data_offsets\":[22,24]},"
				+ "\"ids\":{\"dtype\":\"I64\",\"shape\":[2],\"data_offsets\":[24,40]}}";
		byte[] json = header.getBytes(StandardCharsets.UTF_8);
		int padded = (json.length + 7) / 8 * 8;
		ByteBuffer out = ByteBuffer.allocate(8 + padded + data.position()).order(ByteOrder.LITTLE_ENDIAN);
		out.putLong(padded);
		out.put(json);
		for (int i = json.length; i < padded; i++) {
			out.put((byte) ' ');
		}
		out.put(data.array(), 0, data.position());
		Files.write(file, out.array());
		return file;
	}

	private static List<String> definitionNames(List<LispVal> forms) {
		return forms.stream()
			.filter(LispCons.class::isInstance)
			.map(LispCons.class::cast)
			.filter(cons -> cons.car() instanceof LispSymbol op && op.name().startsWith("DEF"))
			.filter(cons -> cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol)
			.map(cons -> ((LispSymbol) ((LispCons) cons.cdr()).car()).name())
			.toList();
	}

}
