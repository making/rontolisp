package am.ik.rontolisp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User source text reaches core forms through exactly one seam,
 * {@code am.ik.rontolisp.eval.SourceLanguage}: every site that turns USER source into
 * {@code List<LispVal>} reads through it, so the rules about how source is read (the
 * {@code #.} decision, the extension that picks the language) are stated once instead of
 * hand-copied per site.
 *
 * <p>
 * The callers below are exempt because they never read user source (see
 * {@code .kb/source-language.md} for the rule and its mechanics):
 * <ul>
 * <li>Library source shipped inside the jar -- or a form the implementation synthesizes
 * itself -- is Common Lisp whatever the user's language is, so the library splices and
 * the macro package keep reading it through {@code LispReader} directly.</li>
 * <li>The runtime {@code read}/{@code read-from-string} data reads in {@code Environment}
 * are reads of DATA, not of user source.</li>
 * <li>{@code AsdfSystems} reads {@code .asd} system metadata (in a tolerant mode the seam
 * deliberately does not offer) and scans only a file's leading package declaration; the
 * program itself is the load's business.</li>
 * <li>The {@code reader} package implements the seam, so it reads through itself.</li>
 * </ul>
 *
 * <p>
 * Each exemption names its class explicitly, with its reason: a new direct call anywhere
 * else fails the first test, and an exemption whose class stopped reading directly fails
 * the second, so neither side can rot silently.
 */
class SourceLanguageSeamTest {

	private static final List<Path> SOURCE_ROOTS = List.of(Path.of("src", "main", "java"),
			Path.of("src", "web", "java"));

	private static final Pattern DIRECT_READ = Pattern
		.compile("LispReader\\s*\\.\\s*(readAll|readFromString|readFirstForm)\\w*|new\\s+LispLexer\\s*\\(");

	/** Library source (or a self-synthesized form) that stays Common Lisp. */
	private static final String SHIPPED_SOURCE = "reads Common Lisp the implementation ships or synthesizes itself"
			+ " (a jar-bundled library, an inline-synthesized form, generated tables) -- never user source";

	/** A runtime read of data, not of user source. */
	private static final String RUNTIME_DATA = "reads data at runtime (read/read-from-string), not user source";

	/** System metadata and leading-declaration scans, not the program. */
	private static final String SYSTEM_METADATA = "reads .asd system metadata and leading package declarations,"
			+ " not the program being loaded";

	private static final Map<String, String> EXEMPT = Map.ofEntries(
			Map.entry("am.ik.rontolisp.eval.AppKitLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.AsdfRuntimeLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.AsdfSystems", SYSTEM_METADATA),
			Map.entry("am.ik.rontolisp.eval.CheckpointLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.ClUnicodeTables", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.Environment", RUNTIME_DATA),
			Map.entry("am.ik.rontolisp.eval.EnvironmentLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.ExitLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.GeomLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.GgufLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.GrayStreamsLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.HostFetchLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.HttpLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.HttpReactorInliner", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.HttpReactorLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.HttpServerLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.JsonLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.LinalgLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.LispPreludeLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.MetalLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.SafetensorsLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.SceneLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.ShimLibraries", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.SocketsLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.StdinLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.TlsLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.TokenizersLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.TorchLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.UiopLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.UnreadCharLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.UsocketLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.UserMacroExpander", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.VecLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.WaitForLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.WitLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.eval.UrlLibrary", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.macro.CompileRuntime", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.macro.FormatRenderer", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.macro.LispMacroExpander", SHIPPED_SOURCE),
			Map.entry("am.ik.rontolisp.macro.MopProtocol", SHIPPED_SOURCE));

	@Test
	void userSourceReachesFormsOnlyThroughTheSeam() throws IOException {
		Map<String, List<String>> directReaders = directReaders();
		List<String> violators = new ArrayList<>();
		for (Map.Entry<String, List<String>> reader : directReaders.entrySet()) {
			String className = reader.getKey();
			if (className.equals("am.ik.rontolisp.eval.SourceLanguage")) {
				continue;
			}
			if (className.startsWith("am.ik.rontolisp.reader.")) {
				continue;
			}
			if (!EXEMPT.containsKey(className)) {
				violators.add(className + " <- " + String.join(", ", reader.getValue()));
			}
		}
		assertThat(violators)
			.as("A class reads user source without going through SourceLanguage. Either route it through the seam,"
					+ " or -- when it reads shipped library source, runtime data or system metadata instead of"
					+ " user source -- add it to EXEMPT with its reason.")
			.isEmpty();
	}

	@Test
	void everyExemptionStillReadsDirectly() throws IOException {
		Map<String, List<String>> directReaders = directReaders();
		List<String> stale = new ArrayList<>();
		for (String exempted : EXEMPT.keySet()) {
			if (!directReaders.containsKey(exempted)) {
				stale.add(exempted);
			}
		}
		assertThat(stale)
			.as("An EXEMPT entry no longer reads through LispReader directly. Drop the entry --"
					+ " an exemption that exempts nothing hides the next real violator.")
			.isEmpty();
	}

	@Test
	void theScanReachesTheWholeSourceTree() throws IOException {
		Map<String, List<String>> directReaders = directReaders();
		assertThat(directReaders).as("the seam itself must be among the direct readers")
			.containsKey("am.ik.rontolisp.eval.SourceLanguage");
		assertThat(directReaders.keySet().stream().filter(name -> name.startsWith("am.ik.rontolisp.eval.")).count())
			.as("the library splices read their shipped sources directly")
			.isGreaterThan(20);
		assertThat(directReaders.keySet()).as("neither the CLI nor the playground may read around the seam")
			.doesNotContain("am.ik.rontolisp.cli.CompileFrontend", "am.ik.rontolisp.cli.LoadInliner",
					"am.ik.rontolisp.cli.RontoLispCli", "am.ik.rontolisp.cli.ReplBuffer",
					"am.ik.rontolisp.web.RontoPlayground");
	}

	private static Map<String, List<String>> directReaders() throws IOException {
		Map<String, List<String>> readers = new TreeMap<>();
		for (Path root : SOURCE_ROOTS) {
			for (Path file : filesUnder(root)) {
				String body;
				try {
					body = Files.readString(file);
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
				List<String> hits = DIRECT_READ.matcher(body)
					.results()
					.map(match -> match.group().replaceAll("\\s+", " "))
					.toList();
				if (!hits.isEmpty()) {
					readers.put(className(root, file), hits);
				}
			}
		}
		return readers;
	}

	private static String className(Path root, Path file) {
		String relative = root.relativize(file).toString();
		return relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
	}

	private static List<Path> filesUnder(Path root) throws IOException {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.sorted()
				.toList();
		}
		catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
	}

}
