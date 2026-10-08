package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.runtime.RontoHttpServer;
import am.ik.rontolisp.testsupport.CliStack;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The built-in Ring namespaces ({@link ClojureBuiltinNamespaces}): their kernels against
 * the JDK code the oracle (ring-core 1.15.5, ring-codec 1.3.0) runs -- the URL coders,
 * {@code new String(bytes, charset)} with its replacement of malformed input, the
 * content-type charset regex and the keyword-syntax regexes, over seeded random inputs on
 * the interpreter (the kernels are one Common Lisp definition every backend compiles;
 * {@code clojure-spec.yaml}'s {@code ring-*} cases pin that the backends agree) -- plus
 * the refusals, the source-path order and a real request through {@code wrap-params}.
 */
class ClojureRingUtilTest {

	private static final List<Charset> CHARSETS = List.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1,
			StandardCharsets.US_ASCII);

	@Test
	void theDecodersAnswerWhatTheJdkDecodes() throws Exception {
		Random random = new Random(29);
		int[] interesting = { 0x00, 0x0a, 0x41, 0x7f, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc0, 0xc1, 0xc2, 0xc3, 0xdf,
				0xe0, 0xe1, 0xec, 0xed, 0xee, 0xef, 0xf0, 0xf1, 0xf3, 0xf4, 0xf5, 0xf7, 0xf8, 0xfe, 0xff };
		String[] junk = { "+", "%", "%4", "%zz", "%+1", "a", "\u00e9", "\ud83d\ude00", "=", "&" };
		List<String> inputs = new ArrayList<>();
		for (int i = 0; i < 600; i++) {
			StringBuilder s = new StringBuilder();
			int parts = 1 + random.nextInt(4);
			for (int p = 0; p < parts; p++) {
				if (random.nextInt(4) == 0) {
					s.append(junk[random.nextInt(junk.length)]);
					continue;
				}
				int bytes = 1 + random.nextInt(5);
				for (int b = 0; b < bytes; b++) {
					int value = random.nextInt(5) == 0 ? random.nextInt(256)
							: interesting[random.nextInt(interesting.length)];
					s.append('%').append(String.format(random.nextBoolean() ? "%02X" : "%02x", value));
				}
			}
			inputs.add(s.toString());
		}
		StringBuilder program = new StringBuilder("(ns probe (:require [ring.util.codec :as c]))\n" + CPS);
		List<String> expected = new ArrayList<>();
		for (String input : inputs) {
			for (Charset charset : CHARSETS) {
				program.append("(prn (cps (c/url-decode ")
					.append(literal(input))
					.append(" \"")
					.append(charset.name())
					.append("\")) (cps (c/form-decode-str ")
					.append(literal(input))
					.append(" \"")
					.append(charset.name())
					.append("\")))\n");
				expected
					.add(codePoints(percentDecode(input, charset)) + " " + codePoints(formDecodeStr(input, charset)));
			}
		}
		assertThat(lines(interpret(program.toString()))).containsExactlyElementsOf(expected);
	}

	@Test
	void theEncodersAnswerWhatTheJdkEncodes() throws Exception {
		Random random = new Random(31);
		int[] pool = { ' ', '+', '-', '.', '_', '~', '*', '%', '&', '=', '/', 'a', 'Z', '0', 0x7f, 0xe9, 0xff, 0x100,
				0x3042, 0x65e5, 0xfffd, 0x1f600, 0x10ffff };
		List<String> inputs = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			StringBuilder s = new StringBuilder();
			int n = 1 + random.nextInt(6);
			for (int j = 0; j < n; j++) {
				s.appendCodePoint(pool[random.nextInt(pool.length)]);
			}
			inputs.add(s.toString());
		}
		StringBuilder program = new StringBuilder("(ns probe (:require [ring.util.codec :as c]))\n");
		List<String> expected = new ArrayList<>();
		for (String input : inputs) {
			for (Charset charset : CHARSETS) {
				String cs = " \"" + charset.name() + "\"";
				program.append("(prn (c/url-encode ")
					.append(literal(input))
					.append(cs)
					.append(") (c/form-encode ")
					.append(literal(input))
					.append(cs)
					.append(") (c/percent-encode ")
					.append(literal(input))
					.append(cs)
					.append("))\n");
				expected.add("\"" + urlEncode(input, charset) + "\" \"" + URLEncoder.encode(input, charset) + "\" \""
						+ percentEncode(input, charset) + "\"");
			}
		}
		assertThat(lines(interpret(program.toString()))).containsExactlyElementsOf(expected);
	}

	@Test
	void theContentTypeCharsetIsTheOraclesRegexMatch() throws Exception {
		// ring.util.parsing/re-charset as the oracle builds it
		String token = "[!#$%&'*\\-+.0-9A-Z\\^_`a-z\\|~]+";
		String quoted = "\"((?:\\\\\"|[^\"])*)\"";
		Pattern charset = Pattern.compile(";(?:.*\\s)?(?i:charset)=(?:(" + token + ")|" + quoted + ")\\s*(?:;|$)");
		Random random = new Random(37);
		String[] spaces = { " ", "\t", "\n", "\r", "\r\n", "\u2028", "", "", "" };
		String[] junk = { "x=1", "a b", "; ", "=", ";", " q", "\n", "x\"y" };
		String[] values = { "utf-8", "UTF-8", "a\"b", "\"q\"", "\"q\\\"r\"", "\"a;b\"", "\"\\\"", "\"\\\\\"",
				"\"\\\"\\\"\"", "x y", "", "\"", "%&'*+-.^_`|~", "\"un", "\u00e9" };
		StringBuilder program = new StringBuilder(
				"(ns probe (:require [ring.util.response :as r] [ring.util.request :as q]))\n" + CPS);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1500; i++) {
			StringBuilder s = new StringBuilder(List.of("text/html", "", "a/b").get(random.nextInt(3)));
			int params = 1 + random.nextInt(3);
			for (int p = 0; p < params; p++) {
				s.append(';');
				if (random.nextBoolean()) {
					for (int j = random.nextInt(4); j > 0; j--) {
						s.append(random.nextBoolean() ? junk[random.nextInt(junk.length)]
								: spaces[random.nextInt(spaces.length)]);
					}
				}
				if (random.nextInt(5) < 3) {
					s.append(spaces[random.nextInt(spaces.length)]);
				}
				s.append(List.of("charset", "CHARSET", "Charset", "charse").get(random.nextInt(4)));
				s.append(List.of("=", "=", "= ", "").get(random.nextInt(4)));
				s.append(values[random.nextInt(values.length)]);
				for (int j = random.nextInt(3); j > 0; j--) {
					s.append(List.of(" ", "\n", ";", "x", "\"").get(random.nextInt(5)));
				}
			}
			String type = s.toString();
			program.append("(prn (cps (r/get-charset {:headers {\"Content-Type\" ")
				.append(literal(type))
				.append("}})) (cps (q/character-encoding {:headers {\"content-type\" ")
				.append(literal(type))
				.append("}})))\n");
			Matcher m = charset.matcher(type);
			String found = m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
			expected.add(codePoints(found) + " " + codePoints(found));
		}
		assertThat(lines(interpret(program.toString()))).containsExactlyElementsOf(expected);
	}

	@Test
	void aParameterNameBecomesAKeywordWhereTheOraclesRegexesMatch() throws Exception {
		Pattern plain = Pattern.compile("[\\p{L}*+!_?-][\\p{L}\\d*+!_?-]*");
		Pattern namespaced = Pattern.compile("[\\p{L}*+!_?-][\\p{L}\\d*+!_?.-]*/[\\p{L}*+!_?-][\\p{L}\\d*+!_?-]*");
		Random random = new Random(41);
		String special = "*+!_?-./:0123456789aZ";
		StringBuilder program = new StringBuilder("(ns probe (:require [ring.middleware.keyword-params :as kp]))\n"
				+ "(defn kw? [s opts] (keyword? (key (first (:params (kp/keyword-params-request {:params {s 1}} opts))))))\n");
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1500; i++) {
			StringBuilder s = new StringBuilder();
			for (int n = 1 + random.nextInt(4); n > 0; n--) {
				if (random.nextInt(5) < 3) {
					int cp;
					do {
						cp = random.nextInt(4) == 0 ? random.nextInt(0x80) : random.nextInt(0x30000);
					}
					while (!Character.isDefined(cp) || Character.isSurrogate((char) cp) && cp <= 0xffff
							|| Character.getType(cp) == Character.CONTROL);
					s.appendCodePoint(cp);
				}
				else {
					s.append(special.charAt(random.nextInt(special.length())));
				}
			}
			String key = s.toString();
			program.append("(prn (kw? ")
				.append(literal(key))
				.append(" {}) (kw? ")
				.append(literal(key))
				.append(" {:parse-namespaces? true}))\n");
			boolean isPlain = plain.matcher(key).matches();
			expected.add(isPlain + " " + (isPlain || namespaced.matcher(key).matches()));
		}
		assertThat(lines(interpret(program.toString()))).containsExactlyElementsOf(expected);
	}

	@Test
	void theLetterTableIsTheJdksLetterCategory() throws IOException {
		String library;
		try (InputStream in = LispEvaluator.class.getResourceAsStream("clojure.lisp")) {
			assertThat(in).isNotNull();
			library = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int defun = library.indexOf("(defun rontolisp::%clojure-ring-letter-table");
		int open = library.indexOf('"', library.indexOf("(text", defun)) + 1;
		String text = library.substring(open, library.indexOf('"', open));
		List<Integer> decoded = new ArrayList<>();
		int value = 0;
		int digits = 0;
		for (char c : text.toCharArray()) {
			if (c < 48) {
				continue;
			}
			value = value * 64 + (c - (c > 92 ? 49 : 48));
			if (++digits == 4) {
				decoded.add(value);
				value = 0;
				digits = 0;
			}
		}
		List<Integer> jdk = new ArrayList<>();
		int start = -1;
		for (int cp = 128; cp <= Character.MAX_CODE_POINT + 1; cp++) {
			boolean letter = cp <= Character.MAX_CODE_POINT && Character.isLetter(cp);
			if (letter && start < 0) {
				start = cp;
			}
			else if (!letter && start >= 0) {
				jdk.add(start);
				jdk.add(cp - 1);
				start = -1;
			}
		}
		assertThat(decoded).as("regenerate the table from the JDK (four base-64 digits per bound)")
			.containsExactlyElementsOf(jdk);
	}

	@Test
	void aVarTheOraclesNamespaceHasAndTheBuiltInOneLeavesOutIsRefusedByName() {
		assertThatThrownBy(
				() -> Clojure.read("(ns a (:require [ring.util.response :as r])) (r/file-response \"x\")", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("ring.util.response/file-response is not built in: it serves a java.io.File");
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [ring.util.codec :refer [base64-encode]]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("ring.util.codec/base64-encode is not built in: it takes a byte array");
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [ring.util.response :as r])) (r/nope 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: r/nope");
	}

	@Test
	void aRingNamespaceNotShippedIsRefusedByName() {
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [ring.middleware.session :as s]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("ring.middleware.session is not built in: the built-in Ring namespaces are "
					+ "ring.middleware.content-type, ring.middleware.keyword-params, ring.middleware.params, "
					+ "ring.util.codec, ring.util.mime-type, ring.util.request, ring.util.response");
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [ring.adapter.jetty :refer [run-jetty]]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("serve a Ring handler with ring.adapter.rontolisp/run-server");
	}

	@Test
	void theKernelsAreNoUserSurface() {
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [rontolisp.internal.ring :as k]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("rontolisp.internal.ring is internal to the built-in Ring namespaces");
	}

	@Test
	void aProjectFileShadowsTheBuiltInNamespace() {
		Map<String, String> files = Map.of("src/ring/util/response.clj",
				"(ns ring.util.response) (defn response [b] [:mine b]) (defn file-response [p] p)");
		String out = Clojure
			.read("(ns a (:require [ring.util.response :as r])) (r/response 1) (r/file-response 2)", null, null,
					new MemoryClojureFiles(files))
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(out).contains("mine").doesNotContain("%CLOJURE-RING");
	}

	@Test
	void wrapParamsReadsARealRequestOnTheInterpreter() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		String program = """
				(ns params-probe
				  (:require [ring.adapter.rontolisp :refer [run-server]]
				            [ring.middleware.params :refer [wrap-params]]
				            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
				            [ring.util.response :as r]))
				(defn handler [{:keys [params]}]
				  (-> (r/response (str (:name params) "|" (:q params) "|" (get params "2x")))
				      (r/content-type "text/plain")
				      (r/charset "UTF-8")))
				(println (run-server (-> handler wrap-keyword-params wrap-params)
				                     {:port %PORT% :host "127.0.0.1" :join? false}))
				""".replace("%PORT%", Integer.toString(port));
		long handle = Long.parseLong(interpret(program).strip());
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			HttpResponse<String> response = client
				.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/form?q=%E6%97%A5+1&2x=y"))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString("name=J%C3%BCrgen+M"))
					.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).isEqualTo("J\u00fcrgen M|\u65e5 1|y");
			assertThat(response.headers().firstValue("content-type")).hasValue("text/plain; charset=UTF-8");
		}
		finally {
			RontoHttpServer.stopServer(handle);
		}
	}

	/** Prints a string's code points: nil for nil, else {@code [97 98]}. */
	private static final String CPS = "(defn cps [s] (if (nil? s) nil (mapv int s)))\n";

	private static String codePoints(@Nullable String s) {
		return s == null ? "nil"
				: s.codePoints().mapToObj(Integer::toString).collect(Collectors.joining(" ", "[", "]"));
	}

	// ring.util.codec/percent-decode: each run of %XX escapes as new String(bytes,
	// charset)
	private static String percentDecode(String s, Charset charset) {
		return replaceRuns(Pattern.compile("(?:%[A-Fa-f0-9]{2})+"), s, run -> {
			byte[] bytes = new byte[run.length() / 3];
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte) Integer.parseInt(run.substring(3 * i + 1, 3 * i + 3), 16);
			}
			return new String(bytes, charset);
		});
	}

	// ring.util.codec/form-decode-str
	private static @Nullable String formDecodeStr(String s, Charset charset) {
		if (!s.contains("+") && !s.contains("%")) {
			return s;
		}
		try {
			return URLDecoder.decode(s, charset);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	// ring.util.codec/url-encode
	private static String urlEncode(String s, Charset charset) {
		return replaceRuns(Pattern.compile("[^A-Za-z0-9_~.+-]+"), s, run -> percentEncode(run, charset));
	}

	// ring.util.codec/percent-encode
	private static String percentEncode(String s, Charset charset) {
		StringBuilder out = new StringBuilder();
		for (byte b : s.getBytes(charset)) {
			out.append(String.format("%%%02X", b & 0xff));
		}
		return out.toString();
	}

	private static String replaceRuns(Pattern pattern, String s, Function<String, String> replacement) {
		Matcher m = pattern.matcher(s);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(m.group())));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** A Clojure string literal of the text. */
	private static String literal(String text) {
		StringBuilder out = new StringBuilder("\"");
		text.codePoints().forEach(cp -> {
			switch (cp) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (cp < 0x20 || cp >= 0x7f && cp < 0xa0 || cp == 0x2028 || cp == 0x2029) {
						out.append(String.format("\\u%04x", cp));
					}
					else {
						out.appendCodePoint(cp);
					}
				}
			}
		});
		return out.append('"').toString();
	}

	private static List<String> lines(String output) {
		return output.lines().toList();
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-ring-util", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "probe.clj")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

}
