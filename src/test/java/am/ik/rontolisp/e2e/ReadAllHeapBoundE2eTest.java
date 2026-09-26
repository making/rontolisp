package am.ik.rontolisp.e2e;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code read-all} of a large fetched body fits in a heap a small multiple of the body on
 * both JDK backends. The body is binary -- not UTF-8 -- so the decode takes the lenient
 * arms, and the program runs in a child JVM whose heap is capped at ten times the body,
 * with the serial collector so the cap is the live set's and not a concurrent collector's
 * headroom.
 *
 * <p>
 * What the cap has to hold is the body once (an octet a byte) and the string it decodes
 * to, plus one transcode buffer on the JVM. Until 2026-09-26 an octet was a {@code long}
 * on both: a 256 MiB body peaked at 6.2 GB of live heap on the interpreter (the chunks
 * and their join, plus five body-sized decode intermediates) and 3.4 GB on the JVM
 * ({@code .kb/fetch-http.md}, "Throughput").
 */
class ReadAllHeapBoundE2eTest {

	private static final int BODY_BYTES = 32 << 20;

	private static final String HEAP = "-Xmx" + (10 * (BODY_BYTES >> 20)) + "m";

	private static final byte[] BODY = new byte[BODY_BYTES];

	private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

	private static HttpServer origin;

	@TempDir
	Path dir;

	@BeforeAll
	static void startOrigin() throws IOException {
		new Random(20260926L).nextBytes(BODY);
		origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		origin.createContext("/body", exchange -> {
			exchange.sendResponseHeaders(200, BODY.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(BODY);
			}
		});
		origin.start();
	}

	@AfterAll
	static void stopOrigin() {
		origin.stop(0);
	}

	@Test
	void theInterpreterReadsABinaryBodyInTenTimesItsSize() throws Exception {
		String output = run(List.of(JAVA, HEAP, "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
				"am.ik.rontolisp.cli.RontoLispCli", program().toString()));
		int[] decoded = decodeLeniently(BODY);
		assertThat(output.trim()).isEqualTo(decoded.length + " " + decoded[decoded.length - 1]);
	}

	@Test
	void theJvmBackendReadsABinaryBodyInTenTimesItsSize() throws Exception {
		Path classes = Files.createDirectories(this.dir.resolve("classes"));
		run(List.of(JAVA, "-cp", System.getProperty("java.class.path"), "am.ik.rontolisp.cli.RontoLispCli",
				program().toString(), "-o", classes.resolve("ReadAll.class").toString()));
		String output = run(List.of(JAVA, HEAP, "-XX:+UseSerialGC", "-cp", classes.toString(), "ReadAll"));
		// A JVM string is UTF-16, so two decoded surrogates side by side read back as the
		// one character they pair into.
		int[] codePoints = decodeLeniently(BODY);
		int[] decoded = new String(codePoints, 0, codePoints.length).codePoints().toArray();
		assertThat(output.trim()).isEqualTo(decoded.length + " " + decoded[decoded.length - 1]);
	}

	private Path program() throws IOException {
		Path program = this.dir.resolve("read-all.lisp");
		Files.writeString(program, """
				(let* ((res (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/body")))
				       (s (rontolisp:await (rontolisp:read-all (getf res :body)))))
				  (princ (length s))
				  (princ " ")
				  (princ (char-code (char s (1- (length s))))))
				""".formatted(origin.getAddress().getPort()));
		return program;
	}

	private static String run(List<String> command) throws Exception {
		Process process = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(5, TimeUnit.MINUTES)).as(output).isTrue();
		assertThat(process.exitValue()).as(output).isZero();
		return output;
	}

	/**
	 * The oracle: the lenient UTF-8 rule written out once more, independently of every
	 * backend's copy -- a byte that leads no complete sequence, and a 4-byte sequence
	 * past U+10FFFF, is its own character; continuation bytes are not checked.
	 */
	private static int[] decodeLeniently(byte[] octets) {
		int n = octets.length;
		int[] out = new int[n];
		int k = 0;
		int i = 0;
		while (i < n) {
			int b = octets[i] & 0xFF;
			int cp = b;
			int length = 1;
			if (b >= 0xC0 && b < 0xE0 && i + 1 < n) {
				cp = ((b & 0x1F) << 6) | (octets[i + 1] & 0x3F);
				length = 2;
			}
			else if (b >= 0xE0 && b < 0xF0 && i + 2 < n) {
				cp = ((b & 0x0F) << 12) | ((octets[i + 1] & 0x3F) << 6) | (octets[i + 2] & 0x3F);
				length = 3;
			}
			else if (b >= 0xF0 && b < 0xF8 && i + 3 < n) {
				int four = ((b & 0x07) << 18) | ((octets[i + 1] & 0x3F) << 12) | ((octets[i + 2] & 0x3F) << 6)
						| (octets[i + 3] & 0x3F);
				if (four <= 0x10FFFF) {
					cp = four;
					length = 4;
				}
			}
			out[k++] = cp;
			i += length;
		}
		return java.util.Arrays.copyOf(out, k);
	}

}
