package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code gguf} package ({@code gguf.lisp}, spliced/loaded by {@link GgufLibrary})
 * against a checked-in GGUF written by llama.cpp's OWN {@code gguf} Python writer.
 *
 * <p>
 * That is the point of the fixture: {@code src/test/resources/gguf/synthetic.gguf} is not
 * this repository's idea of the format, it is the reference implementation's, so agreeing
 * with it is a statement about GGUF rather than about self-consistency. It carries all
 * thirteen metadata value types, arrays of four of them, an alignment that is NOT the
 * default 32, tensors of rank 1, 2 and 3, one tensor per loadable width (F32, F16, BF16),
 * a Q8_0 and a Q4_K to refuse, and the tokenizer fields a byte-level BPE needs. Every
 * expectation below was read off the official reader ({@code .kb/gguf.md} has the
 * generator and the numbers).
 *
 * <p>
 * This is the interpreter half; the cross-backend half is the {@code gguf} case of
 * {@code ci-spec.yaml}, which writes a small GGUF from Lisp and reads it back on all four
 * backends.
 */
class GgufLibraryTest {

	@TempDir
	static Path tempDir;

	private static Path fixture;

	@BeforeAll
	static void copyFixture() throws IOException {
		fixture = tempDir.resolve("synthetic.gguf");
		try (InputStream in = GgufLibraryTest.class.getResourceAsStream("/gguf/synthetic.gguf")) {
			assertThat(in).as("the synthetic.gguf fixture").isNotNull();
			Files.copy(in, fixture, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private String eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result.print();
	}

	/**
	 * The fixture opened metadata-only, with FORM evaluated over the file in {@code f}.
	 */
	private String read(String form) {
		return eval("""
				(defparameter f (gguf:read "%s" :metadata-only t))
				%s
				""".formatted(fixture.toString().replace("\\", "\\\\"), form));
	}

	/** The fixture with the loadable tensors read, with FORM evaluated over it. */
	private String loaded(String form) {
		return eval("""
				(defparameter f (gguf:read "%s"
				                           :only '("t.f32" "t.f16" "t.bf16" "t.vec" "t.cube")))
				%s
				""".formatted(fixture.toString().replace("\\", "\\\\"), form));
	}

	// --- the header and the key/value block ---------------------------------------

	@Test
	void theHeaderAndTheArchitectureAreRead() {
		assertThat(read("(gguf:version f)")).isEqualTo("3");
		assertThat(read("(gguf:metadata-value f \"general.architecture\")")).isEqualTo("\"synthetic\"");
	}

	/**
	 * All thirteen value types. The unsigned 64-bit case is the one a naive reader gets
	 * wrong: 12297829382473034410 is past a signed 64-bit maximum, so it has to arrive as
	 * a bignum rather than as a negative number.
	 */
	@Test
	void everyScalarValueTypeIsRead() {
		assertThat(read("(gguf:metadata-value f \"t.u8\")")).isEqualTo("200");
		assertThat(read("(gguf:metadata-value f \"t.i8\")")).isEqualTo("-100");
		assertThat(read("(gguf:metadata-value f \"t.u16\")")).isEqualTo("60000");
		assertThat(read("(gguf:metadata-value f \"t.i16\")")).isEqualTo("-30000");
		assertThat(read("(gguf:metadata-value f \"t.u32\")")).isEqualTo("4000000000");
		assertThat(read("(gguf:metadata-value f \"t.i32\")")).isEqualTo("-2000000000");
		assertThat(read("(gguf:metadata-value f \"t.u64\")")).isEqualTo("12297829382473034410");
		assertThat(read("(gguf:metadata-value f \"t.i64\")")).isEqualTo("-1234567890123456789");
		assertThat(read("(gguf:metadata-value f \"t.f32\")")).isEqualTo("0.15625");
		assertThat(read("(gguf:metadata-value f \"t.f64\")")).isEqualTo("-0.0025");
		assertThat(read("(gguf:metadata-value f \"t.bool.true\")")).isEqualTo("T");
		assertThat(read("(gguf:metadata-value f \"t.string\")")).isEqualTo("\"hello, 世界 😀\"");
	}

	/**
	 * A GGUF boolean false is a stored nil, so "absent" cannot be told from "false" by
	 * the value: gguf:metadata-value asks the table whether the key is present.
	 */
	@Test
	void anAbsentKeyIsToldFromAStoredFalse() {
		assertThat(read("(gguf:metadata-value f \"t.bool.false\" :default)")).isEqualTo("NIL");
		assertThat(read("(gguf:metadata-value f \"t.missing\" :default)")).isEqualTo(":DEFAULT");
		assertThat(read("(gguf:metadata-value f \"t.missing\")")).isEqualTo("NIL");
	}

	@Test
	void arraysComeBackAsVectors() {
		assertThat(read("(gguf:metadata-value f \"t.arr.i32\")")).isEqualTo("#(1 -2 3 -4)");
		assertThat(read("(gguf:metadata-value f \"t.arr.f32\")")).isEqualTo("#(0.5 0.25 -0.125)");
		assertThat(read("(gguf:metadata-value f \"t.arr.u64\")")).isEqualTo("#(1 2 4294967296)");
		assertThat(read("(gguf:metadata-value f \"t.arr.str\")")).isEqualTo("#(\"a\" \"\" \"éé\" \"d\")");
		assertThat(read("(gguf:metadata-value f \"t.arr.bool\")")).isEqualTo("#(T NIL T)");
	}

	// --- the tensor directory -----------------------------------------------------

	@Test
	void theDirectoryIsReadInFileOrder() {
		assertThat(read("(gguf:tensor-names f)"))
			.isEqualTo("(\"t.f32\" \"t.f16\" \"t.bf16\" \"t.vec\" \"t.cube\" \"t.q8_0\" \"t.q4_k\")");
	}

	/**
	 * ggml stores dims fastest-varying first, so the {@code [4 3]} the file carries for
	 * t.f32 is a 3x4 matrix. The reader reverses them once, on the way in, so nothing
	 * downstream has to remember.
	 */
	@Test
	void dimsAreReversedIntoRowMajorOrder() {
		assertThat(read("(getf (gguf:tensor-info f \"t.f32\") :dims)")).isEqualTo("(3 4)");
		assertThat(read("(getf (gguf:tensor-info f \"t.vec\") :dims)")).isEqualTo("(5)");
		assertThat(read("(getf (gguf:tensor-info f \"t.cube\") :dims)")).isEqualTo("(2 3 4)");
	}

	@Test
	void theDirectoryCarriesTheTypeOffsetAndByteCount() {
		assertThat(read("(getf (gguf:tensor-info f \"t.bf16\") :type-name)")).isEqualTo("\"BF16\"");
		assertThat(read("(getf (gguf:tensor-info f \"t.q4_k\") :type-name)")).isEqualTo("\"Q4_K\"");
		assertThat(read("(getf (gguf:tensor-info f \"t.cube\") :offset)")).isEqualTo("256");
		assertThat(read("(getf (gguf:tensor-info f \"t.cube\") :bytes)")).isEqualTo("96");
		// 2 blocks of 34 bytes, and 1 block of 144 -- the block shapes, not the element
		// counts.
		assertThat(read("(getf (gguf:tensor-info f \"t.q8_0\") :bytes)")).isEqualTo("68");
		assertThat(read("(getf (gguf:tensor-info f \"t.q4_k\") :bytes)")).isEqualTo("144");
	}

	@Test
	void metadataOnlyLoadsNoTensorAtAll() {
		assertThat(read("(gguf:tensor f \"t.f32\")")).isEqualTo("NIL");
	}

	// --- the tensors --------------------------------------------------------------

	@Test
	void f32TensorsAreReadStraightIntoAPackedArray() {
		assertThat(loaded("(gguf:tensor f \"t.f32\")"))
			.isEqualTo("#f((-0.5 -0.375 -0.25 -0.125) (0.0 0.125 0.25 0.375) (0.5 0.625 0.75 0.875))");
		assertThat(loaded("(gguf:tensor f \"t.vec\")")).isEqualTo("#f(1.0 2.0 3.0 4.0 5.0)");
		assertThat(loaded("(array-dimensions (gguf:tensor f \"t.cube\"))")).isEqualTo("(2 3 4)");
		assertThat(loaded("(aref (gguf:tensor f \"t.cube\") 1 2 3)")).isEqualTo("23.0");
	}

	/** F16 and BF16 arrive through rontolisp:widen-float-bits, exactly. */
	@Test
	void f16AndBf16TensorsAreWidened() {
		assertThat(loaded("(gguf:tensor f \"t.f16\")"))
			.isEqualTo("#f((-1.0 -0.75 -0.5 -0.25 0.0) (0.25 0.5 0.75 1.0 1.25))");
		assertThat(loaded("(gguf:tensor f \"t.bf16\")"))
			.isEqualTo("#f((1.0 -2.5) (0.25 0.0) (-0.75 3.5) (8.0 -0.125))");
	}

	@Test
	void theElementTypeChoosesTheWidth() {
		assertThat(eval("""
				(gguf:tensor (gguf:read "%s" :only '("t.vec") :element-type 'double-float) "t.vec")
				""".formatted(fixture.toString().replace("\\", "\\\\")))).isEqualTo("#d(1.0 2.0 3.0 4.0 5.0)");
		assertThat(eval("""
				(handler-case (gguf:read "%s" :element-type 'fixnum)
				  (error (e) (format nil "~a" e)))
				""".formatted(fixture.toString().replace("\\", "\\\\"))))
			.contains(":element-type must be 'single-float, 'double-float or 'bfloat16");
	}

	// The third destination (interpreter and JVM only; every other backend refuses the
	// width by name). What each source type costs to reach it differs, and the fixture
	// shows all three: a BF16 tensor is the file's own bytes and comes back EQUAL to the
	// single-float read, because widening a bfloat16 is exact; an F32 tensor is narrowed
	// AS IT STREAMS, never through a whole f32 transient; an F16 tensor goes through the
	// chunk-sized f32 scratch. Every value in this fixture happens to fit eight mantissa
	// bits, so all three come back unchanged -- which is the point of the fixture, not a
	// property of the conversion (.todo/487 steps 3 and 4, .kb/bfloat16.md).
	@Test
	void everyTensorTypeReadsIntoABfloat16Destination() {
		String path = fixture.toString().replace("\\", "\\\\");
		String read = "(gguf:read \"%s\" :only '(\"t.f32\" \"t.f16\" \"t.bf16\" \"t.vec\") :element-type 'bfloat16)"
			.formatted(path);
		assertThat(eval("(array-element-type (gguf:tensor %s \"t.bf16\"))".formatted(read))).isEqualTo("BFLOAT16");
		assertThat(eval("(gguf:tensor %s \"t.bf16\")".formatted(read)))
			.isEqualTo("#bf16((1.0 -2.5) (0.25 0.0) (-0.75 3.5) (8.0 -0.125))");
		assertThat(eval("(gguf:tensor %s \"t.f16\")".formatted(read)))
			.isEqualTo("#bf16((-1.0 -0.75 -0.5 -0.25 0.0) (0.25 0.5 0.75 1.0 1.25))");
		assertThat(eval("(gguf:tensor %s \"t.vec\")".formatted(read))).isEqualTo("#bf16(1.0 2.0 3.0 4.0 5.0)");
	}

	// --- what is refused, and when ------------------------------------------------

	/**
	 * A quantized tensor is refused when its BODY is asked for and not before: the
	 * header, the key/value block and the whole directory of a file that carries one
	 * still read, so a quantized checkpoint can be inspected and its tokenizer taken.
	 */
	@Test
	void aQuantizedTensorIsRefusedOnlyWhenItsBodyIsAskedFor() {
		assertThat(read("(getf (gguf:tensor-info f \"t.q4_k\") :type-name)")).isEqualTo("\"Q4_K\"");
		String refusal = eval("""
				(handler-case (gguf:read "%s" :only '("t.q4_k"))
				  (error (e) (format nil "~a" e)))
				""".formatted(fixture.toString().replace("\\", "\\\\")));
		assertThat(refusal).contains("t.q4_k").contains("Q4_K").contains("F32, F16").contains(":metadata-only");
	}

	/**
	 * A Q8_0 tensor loads as a quantized matrix ({@code .kb/quantized-matrix.md}): the
	 * file's blocks are the matrix's own storage, so the fixture's block -- {@code d} =
	 * 0.5 as a binary16, then the quants -16..15 -- reads back as -8.0 .. 7.5.
	 */
	@Test
	void aQ80TensorLoadsAsAQuantizedMatrix() {
		String program = """
				(defparameter f (gguf:read "%s" :only '("t.q8_0")))
				(defparameter m (gguf:tensor f "t.q8_0"))
				""".formatted(fixture.toString().replace("\\", "\\\\"));
		assertThat(eval(program + "m")).isEqualTo("#<quantized-matrix q8-0 (1 64)>");
		assertThat(eval(program + "(list (array-element-type m) (rontolisp:quantized-matrix-p m)"
				+ " (rontolisp::%quantized-scale m 0 0) (aref m 0 0) (aref m 0 16) (aref m 0 31))"))
			.isEqualTo("(Q8-0 T 0.5 -8.0 0.0 7.5)");
		assertThat(eval(program + "(getf (gguf:tensor-info f \"t.q8_0\") :bytes)")).isEqualTo("68");
		// :element-type does not apply to a quantized tensor; it keeps its format.
		assertThat(eval("""
				(gguf:tensor (gguf:read "%s" :only '("t.q8_0") :element-type 'double-float) "t.q8_0")
				""".formatted(fixture.toString().replace("\\", "\\\\")))).isEqualTo("#<quantized-matrix q8-0 (1 64)>");
	}

	@Test
	void onlySkipsWhatItDoesNotName() {
		assertThat(loaded("(gguf:tensor f \"t.q8_0\")")).isEqualTo("NIL");
		assertThat(loaded("(gguf:tensor f \"t.q4_k\")")).isEqualTo("NIL");
	}

	@Test
	void aFileThatIsNotGgufIsRefusedByItsMagic() {
		Path notGguf = tempDir.resolve("not.gguf");
		try {
			Files.writeString(notGguf, "this is not a checkpoint");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		assertThat(eval("""
				(handler-case (gguf:read "%s")
				  (error (e) (format nil "~a" e)))
				""".formatted(notGguf.toString().replace("\\", "\\\\")))).contains("not a GGUF file");
	}

	// --- the tokenizer fields -------------------------------------------------------

	/**
	 * The fields go to tokenizer:make-bpe / tokenizer:make-sentencepiece as they are, so
	 * they are surfaced unchanged rather than interpreted here.
	 */
	@Test
	void theTokenizerFieldsComeOutInTheShapeTheTokenizerPackageTakes() {
		assertThat(read("(getf (gguf:tokenizer-fields f) :model)")).isEqualTo("\"gpt2\"");
		assertThat(read("(getf (gguf:tokenizer-fields f) :pre)")).isEqualTo("\"smollm\"");
		assertThat(read("(getf (gguf:tokenizer-fields f) :tokens)"))
			.isEqualTo("#(\"<|endoftext|>\" \"h\" \"e\" \"l\" \"o\" \"he\")");
		assertThat(read("(getf (gguf:tokenizer-fields f) :merges)")).isEqualTo("#(\"h e\")");
		assertThat(read("(getf (gguf:tokenizer-fields f) :token-type)")).isEqualTo("#(3 1 1 1 1 1)");
		assertThat(read("(getf (gguf:tokenizer-fields f) :bos)")).isEqualTo("0");
		// The synthetic file carries no scores, and an absent field is nil rather than an
		// error: a gpt2 vocabulary has merges, a llama one has scores.
		assertThat(read("(getf (gguf:tokenizer-fields f) :scores)")).isEqualTo("NIL");
	}

	/** The fields really do drive the tokenizer: encode with what the file carried. */
	@Test
	void theFieldsDriveTheTokenizerPackage() {
		assertThat(read("""
				(let* ((fields (gguf:tokenizer-fields f))
				       (tk (tokenizer:make-bpe (getf fields :tokens) (getf fields :merges)
				                               :kind (getf fields :pre)
				                               :specials '("<|endoftext|>")
				                               :bos (getf fields :bos))))
				  (tokenizer:encode tk "hello"))
				""")).isEqualTo("(5 3 3 4)");
	}

	// --- the splice -----------------------------------------------------------------

	@Test
	void processSplicesOnlyWhenThePackageIsReferenced() {
		List<LispVal> untouched = LispReader.readAllFromString("(print (+ 1 2))");
		assertThat(GgufLibrary.process(untouched)).isSameAs(untouched);
		List<LispVal> using = LispReader.readAllFromString("(gguf:read \"m.gguf\")");
		assertThat(GgufLibrary.process(using)).hasSizeGreaterThan(using.size());
	}

	@Test
	void theLibraryReachesForNothingButCommonLispFileIoAndTheSharedStaging() {
		String source = GgufLibrary.forms().stream().map(LispVal::print).reduce("", String::concat);
		assertThat(source).doesNotContain("LINALG:").doesNotContain("OBJC:").doesNotContain("JAVA:");
		// file-position is not in it, deliberately: it repositions nothing on any
		// backend, so the data is walked sequentially instead (.kb/gguf.md).
		assertThat(source).doesNotContain("FILE-POSITION");
		// The staging is the checkpoint package's, shared with the safetensors reader
		// rather than written twice, so this file holds the FORMAT and nothing else.
		assertThat(source).contains("CHECKPOINT:MAKE-TENSOR")
			.contains("CHECKPOINT:STAGE-FLOAT32")
			.contains("CHECKPOINT:STAGE-FLOAT-BITS")
			.contains("CHECKPOINT:SKIP-BYTES");
	}

}
