package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte arrays ({@link ClojureBytesLowering}) against the JDK code the oracle runs:
 * {@code String.getBytes} of a charset -- the replacement of an unmappable character and
 * of a lone surrogate included -- and {@code new String(bytes, charset)} with its
 * replacement of malformed input, whole and of a part, over seeded random inputs on the
 * interpreter. The runtime is one Common Lisp definition every backend compiles;
 * {@code clojure-spec.yaml}'s byte-array cases pin that the backends agree.
 */
class ClojureBytesTest {

	private static final List<Charset> CHARSETS = List.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1,
			StandardCharsets.US_ASCII);

	@Test
	void getBytesEncodesAsTheJdkEncodes() throws Exception {
		Random random = new Random(41);
		int[] pool = { 'a', 'Z', '0', ' ', 0x7f, 0x80, 0xe9, 0xff, 0x100, 0x7ff, 0x800, 0x3042, 0xd7ff, 0xd800, 0xdbff,
				0xdc00, 0xdfff, 0xe000, 0xfffd, 0xffff, 0x10000, 0x1f600, 0x10ffff };
		StringBuilder program = new StringBuilder();
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			StringBuilder s = new StringBuilder();
			int n = random.nextInt(7);
			for (int j = 0; j < n; j++) {
				s.appendCodePoint(pool[random.nextInt(pool.length)]);
			}
			for (Charset charset : CHARSETS) {
				program.append("(prn (vec (.getBytes ")
					.append(literal(s.toString()))
					.append(" \"")
					.append(charset.name())
					.append("\")))\n");
				expected.add(vector(s.toString().getBytes(charset)));
			}
		}
		assertThat(interpret(program.toString()).lines().toList()).containsExactlyElementsOf(expected);
	}

	@Test
	void aStringOfBytesDecodesAsTheJdkDecodes() throws Exception {
		Random random = new Random(43);
		int[] interesting = { 0x00, 0x0a, 0x41, 0x7f, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc0, 0xc1, 0xc2, 0xc3, 0xdf,
				0xe0, 0xe1, 0xec, 0xed, 0xee, 0xef, 0xf0, 0xf1, 0xf3, 0xf4, 0xf5, 0xf7, 0xf8, 0xfe, 0xff };
		StringBuilder program = new StringBuilder("(defn cps [s] (mapv int s))\n");
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			byte[] bytes = new byte[random.nextInt(9)];
			for (int k = 0; k < bytes.length; k++) {
				bytes[k] = (byte) (random.nextInt(4) == 0 ? random.nextInt(256)
						: interesting[random.nextInt(interesting.length)]);
			}
			int off = bytes.length == 0 ? 0 : random.nextInt(bytes.length);
			int len = random.nextInt(bytes.length - off + 1);
			String array = vector(bytes).replace('[', '(').replace(']', ')');
			for (Charset charset : CHARSETS) {
				program.append("(let [b (byte-array '")
					.append(array)
					.append(")] (prn (cps (String. b \"")
					.append(charset.name())
					.append("\")) (cps (String. b ")
					.append(off)
					.append(' ')
					.append(len)
					.append(" \"")
					.append(charset.name())
					.append("\"))))\n");
				expected.add(codePoints(new String(bytes, charset)) + " "
						+ codePoints(new String(bytes, off, len, charset)));
			}
		}
		assertThat(interpret(program.toString()).lines().toList()).containsExactlyElementsOf(expected);
	}

	private static String vector(byte[] bytes) {
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < bytes.length; i++) {
			out.append(i == 0 ? "" : " ").append(bytes[i]);
		}
		return out.append(']').toString();
	}

	private static String codePoints(String s) {
		return s.codePoints().mapToObj(Integer::toString).collect(Collectors.joining(" ", "[", "]"));
	}

	/**
	 * A Clojure string literal of the text: a character past ASCII as itself, a lone
	 * surrogate and a control character escaped, so the reader reads the code point the
	 * JDK sees.
	 */
	private static String literal(String text) {
		StringBuilder out = new StringBuilder("\"");
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean pair = Character.isHighSurrogate(c) && i + 1 < text.length()
					&& Character.isLowSurrogate(text.charAt(i + 1));
			if (pair) {
				out.append(c).append(text.charAt(i + 1));
				i++;
			}
			else if (c == '"' || c == '\\') {
				out.append('\\').append(c);
			}
			else if (c < 0x20 || c == 0x7f || Character.isSurrogate(c)) {
				out.append(String.format("\\u%04x", (int) c));
			}
			else {
				out.append(c);
			}
		}
		return out.append('"').toString();
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-bytes", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator
				.clojureProgram(SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "probe.clj"))) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

}
