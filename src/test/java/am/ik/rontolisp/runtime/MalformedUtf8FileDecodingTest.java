package am.ik.rontolisp.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two character file streams that decode UTF-8 themselves --
 * {@link RontoCharFileReader} (what {@code open} answers on the interpreter, and on the
 * JVM backend in a program that names {@code file-position}) and
 * {@link RontoIoFileStream} (an {@code :io} stream) -- decode malformed input exactly as
 * Java's decoder does, which is what the JVM backend's plain {@code FileReader} answers
 * and the rule the WASM backends share ({@code .kb/character-sequence-io.md}, "Malformed
 * input"). Pinned over every sequence of one to three bytes drawn from the boundary bytes
 * of each UTF-8 range, alone and run together.
 */
public class MalformedUtf8FileDecodingTest {

	/** The first and last byte of every range the decoder tells apart, and a few more. */
	public static final int[] BOUNDARY_BYTES = { 0x00, 0x0A, 0x41, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC1,
			0xC2, 0xDF, 0xE0, 0xE1, 0xEC, 0xED, 0xEE, 0xEF, 0xF0, 0xF1, 0xF3, 0xF4, 0xF5, 0xF7, 0xF8, 0xFF };

	@TempDir
	Path dir;

	@Test
	void everyShortSequenceDecodesAsJavasDecoderDecodesIt() throws IOException {
		Path file = this.dir.resolve("seq.dat");
		int n = BOUNDARY_BYTES.length;
		for (int length = 1; length <= 3; length++) {
			int count = (int) Math.pow(n, length);
			for (int index = 0; index < count; index++) {
				byte[] bytes = new byte[length];
				for (int i = 0, rest = index; i < length; i++, rest /= n) {
					bytes[i] = (byte) BOUNDARY_BYTES[rest % n];
				}
				Files.write(file, bytes);
				List<Integer> expected = javaDecode(bytes);
				assertThat(charFileReaderDecode(file)).as("RontoCharFileReader %s", hex(bytes)).isEqualTo(expected);
				assertThat(ioFileStreamDecode(file)).as("RontoIoFileStream %s", hex(bytes)).isEqualTo(expected);
			}
		}
	}

	@Test
	void everyTripleRunTogetherDecodesAsJavasDecoderDecodesIt() throws IOException {
		byte[] bytes = tripleCorpus();
		Path file = this.dir.resolve("corpus.dat");
		Files.write(file, bytes);
		List<Integer> expected = javaDecode(bytes);
		assertThat(charFileReaderDecode(file)).isEqualTo(expected);
		assertThat(ioFileStreamDecode(file)).isEqualTo(expected);
	}

	/** Every triple of {@link #BOUNDARY_BYTES}, in order, run together. */
	public static byte[] tripleCorpus() {
		int n = BOUNDARY_BYTES.length;
		byte[] bytes = new byte[n * n * n * 3];
		int p = 0;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				for (int k = 0; k < n; k++) {
					bytes[p++] = (byte) BOUNDARY_BYTES[i];
					bytes[p++] = (byte) BOUNDARY_BYTES[j];
					bytes[p++] = (byte) BOUNDARY_BYTES[k];
				}
			}
		}
		return bytes;
	}

	/** The code points Java's UTF-8 decoder reads the bytes as. */
	public static List<Integer> javaDecode(byte[] bytes) throws IOException {
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
			return codePoints(reader);
		}
	}

	private static List<Integer> charFileReaderDecode(Path file) throws IOException {
		try (RontoCharFileReader reader = new RontoCharFileReader(file.toString())) {
			return codePoints(reader);
		}
	}

	private static List<Integer> ioFileStreamDecode(Path file) throws IOException {
		// the overwrite bit: keep the content, start at 0
		try (RontoIoFileStream stream = new RontoIoFileStream(file.toString(), 8)) {
			List<Integer> out = new ArrayList<>();
			for (int cp = stream.readCodePoint(); cp >= 0; cp = stream.readCodePoint()) {
				out.add(cp);
			}
			return out;
		}
	}

	private static List<Integer> codePoints(Reader reader) throws IOException {
		List<Integer> out = new ArrayList<>();
		for (int c = reader.read(); c >= 0; c = reader.read()) {
			if (Character.isHighSurrogate((char) c)) {
				int low = reader.read();
				out.add(Character.toCodePoint((char) c, (char) low));
			}
			else {
				out.add(c);
			}
		}
		return out;
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02X ", b & 0xFF));
		}
		return sb.toString().trim();
	}

}
