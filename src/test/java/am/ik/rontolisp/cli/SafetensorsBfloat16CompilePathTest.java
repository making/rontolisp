package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code safetensors:read :element-type 'bfloat16} through the REAL compile front end
 * ({@link CompileFrontend}, the splice order and all), run as a compiled {@code .class},
 * against the interpreter running the same program (.kb/checkpoint-readers.md).
 * <p>
 * The width exists on the two engines only, so this is where their agreement is pinned:
 * the primitives underneath have per-engine pins already ({@code make-array} with a
 * runtime element type, and the bulk {@code read-sequence} over a {@code #bf16} array),
 * and what neither covers is the READER built out of them -- three source dtypes reaching
 * one destination by three different routes, one of which stages, one of which narrows as
 * it streams and one of which does not convert at all. A disagreement here is a
 * checkpoint that loads differently depending on how the program was run.
 * <p>
 * Only bit patterns are printed. A bf16 value's pattern is an integer on both engines, so
 * the comparison cannot pass on a float formatting accident, and the fixture carries the
 * two ties {@code 1.00390625} / {@code 1.01171875} (round-to-nearest-EVEN keeps the first
 * and lifts the second) so a narrowing that truncated would show rather than round-trip.
 */
class SafetensorsBfloat16CompilePathTest {

	@TempDir
	Path tempDir;

	/**
	 * The reader, twice over the same file: once as the file's tensors come, once with
	 * the staging chunk forced down to five elements so every tensor longer than that
	 * lands through several chunks at non-zero offsets -- the path a 2 GB checkpoint
	 * takes, on a few dozen bytes. {@code ids} is I64 and is what {@code :only} leaves on
	 * disk, so {@code checkpoint:skip-bytes} walks past it in both passes.
	 */
	private static final String PROGRAM = """
			(defun show (name a)
			  (format t "~a ~a ~a" name (array-element-type a) (array-dimensions a))
			  (dotimes (i (array-total-size a))
			    (format t " ~a" (rontolisp:bfloat16-bits (row-major-aref a i))))
			  (terpri))

			(defun show-all (table)
			  (let ((names '()))
			    (maphash (lambda (k v) (push k names)) table)
			    (dolist (name (sort names #'string<))
			      (show name (gethash name table)))))

			(defun read-all ()
			  (safetensors:read "%s" :element-type 'bfloat16
			                    :only (lambda (n) (not (string= n "ids")))))

			(show-all (read-all))
			(setq checkpoint::%%chunk 5)
			(setq checkpoint::%%staging-buffer nil)
			(show-all (read-all))
			""";

	/**
	 * F32 narrowed as it streams, F16 through the f32 scratch, BF16 not converted at all
	 * -- and every element the same on both engines and in both chunkings.
	 */
	private static final String EXPECTED = """
			a.weight BFLOAT16 (2 6) 16320 49152 16000 16640 16256 16258 17096 48896 16448 16608 15872 50304
			b.weight BFLOAT16 (7) 16256 48896 18304 0 16384 49216 14464
			c BFLOAT16 (1) 49184
			d BFLOAT16 (4) 16256 48896 16128 16384
			""";

	@Test
	void aBfloat16ReadIsTheSameArraysCompiledAsInterpreted() throws Exception {
		Path fixture = writeFixture(this.tempDir.resolve("model.safetensors"));
		Path program = this.tempDir.resolve("read.lisp");
		Files.writeString(program, PROGRAM.formatted(fixture.toString().replace("\\", "/")));
		String interpreted = runInterpreter(program);
		String compiled = runCompiled(program);
		assertThat(compiled).as("the compiled class must read the checkpoint the interpreter reads")
			.isEqualTo(interpreted);
	}

	@Test
	void everyDtypeReachesTheWidthByItsOwnRouteAndTheChunkingDoesNotChangeIt() throws Exception {
		Path fixture = writeFixture(this.tempDir.resolve("model.safetensors"));
		Path program = this.tempDir.resolve("read.lisp");
		Files.writeString(program, PROGRAM.formatted(fixture.toString().replace("\\", "/")));
		String expected = (EXPECTED + EXPECTED).stripTrailing();
		assertThat(runInterpreter(program)).isEqualTo(expected);
		assertThat(runCompiled(program)).isEqualTo(expected);
	}

	private String runInterpreter(Path program) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out));
		cli.run(new String[] { program.toString() });
		return out.toString(StandardCharsets.UTF_8).stripTrailing();
	}

	/**
	 * Compiles the program to a {@code .class} through the CLI -- the same backend half
	 * an embedder gets -- and runs it the way the manual verification does,
	 * {@code java -cp} the class directory, in a JVM of its own. Not in this one: a test
	 * that redirected {@code System.out} would collect whatever the other tests of a
	 * parallel run printed at the same moment.
	 */
	private String runCompiled(Path program) throws Exception {
		Path classes = this.tempDir.resolve("classes");
		ByteArrayOutputStream compileOut = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(compileOut));
		cli.run(new String[] { program.toString(), "-o", classes.resolve("Read.class").toString() });
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				classes.toString(), "Read")
			.redirectErrorStream(true)
			.start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as("java -cp %s Read said: %s", classes, out).isZero();
		return out.stripTrailing();
	}

	/**
	 * Five tensors, laid out contiguously in the offset order a reader walks: a 2x6 F32
	 * (both round-to-nearest-even ties among its values), a 7-element F16 (the f16
	 * maximum, which is not a bfloat16, and the f16 minimum normal), a one-element BF16,
	 * a 4-element BF16 opening with the pattern {@code 0x3F80} -- 1.0, and {@code 0x803F}
	 * byte-swapped, a tiny negative denormal -- and an I64 for {@code :only} to leave
	 * behind.
	 */
	private static Path writeFixture(Path file) throws IOException {
		ByteBuffer data = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN);
		for (float f : new float[] { 1.5f, -2.0f, 0.25f, 8.0f, 1.00390625f, 1.01171875f, 100.0f, -0.5f, 3.0f, 7.0f,
				0.125f, -1024.0f }) {
			data.putFloat(f); // a.weight, 48 bytes
		}
		for (int bits : new int[] { 0x3C00, 0xB800, 0x7BFF, 0x0000, 0x4000, 0xC200, 0x0400 }) {
			data.putShort((short) bits); // b.weight, 14 bytes
		}
		data.putShort((short) 0xC020); // c = -2.5 in bf16, 2 bytes
		for (int bits : new int[] { 0x3F80, 0xBF00, 0x3F00, 0x4000 }) {
			data.putShort((short) bits); // d, 8 bytes
		}
		data.putLong(7L).putLong(-1L); // ids, 16 bytes
		String header = "{\"a.weight\":{\"dtype\":\"F32\",\"shape\":[2,6],\"data_offsets\":[0,48]},"
				+ "\"b.weight\":{\"dtype\":\"F16\",\"shape\":[7],\"data_offsets\":[48,62]},"
				+ "\"c\":{\"dtype\":\"BF16\",\"shape\":[1],\"data_offsets\":[62,64]},"
				+ "\"d\":{\"dtype\":\"BF16\",\"shape\":[4],\"data_offsets\":[64,72]},"
				+ "\"ids\":{\"dtype\":\"I64\",\"shape\":[2],\"data_offsets\":[72,88]}}";
		byte[] json = header.getBytes(StandardCharsets.UTF_8);
		int padded = (json.length + 7) / 8 * 8;
		ByteBuffer out = ByteBuffer.allocate(8 + padded + data.position()).order(ByteOrder.LITTLE_ENDIAN);
		out.putLong(padded).put(json);
		for (int i = json.length; i < padded; i++) {
			out.put((byte) ' ');
		}
		out.put(data.array(), 0, data.position());
		Files.write(file, out.array());
		return file;
	}

}
