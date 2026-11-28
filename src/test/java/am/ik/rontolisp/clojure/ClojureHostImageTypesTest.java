package am.ik.rontolisp.clojure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDK classes a native image without a JDK's class files lowers a Clojure host form
 * from ({@code .kb/clojure-frontend.md}, "Host classes"): every one the front end names
 * in its own code -- the {@code ClojureThrowables.PARENTS} throwables,
 * {@code ClojureInteropLowering.HOSTLESS_RECEIVERS},
 * {@code ClojureInteropLowering.HOST_STATIC_CLASSES} (whose invoked members are
 * registered too, so macro-time evaluation runs them),
 * {@code ClojureValueMethodLowering}'s host kinds, {@code ClojureTimeValueLowering}'s
 * kinds, the decimal's {@code BigDecimal}, {@code ClojureIoLowering}'s API types,
 * {@code Object} and {@code Throwable} -- is registered as a type in
 * {@code reachability-metadata.json}, so the image answers every member query of it as a
 * JVM does. A class a program names outside them stays partial without a JDK.
 * <p>
 * Each entry registers its public constructors and fields too, and the types a program
 * makes or calls ({@code HOST_CALLABLE_CLASSES}) their public methods: a program builds a
 * host class and calls it through the bridge's reflection, which answered no constructor
 * of a type registered without them ({@code java.util.HashMap}), refused one it answered
 * ({@code java.util.GregorianCalendar}), and refused the call of a member it resolved
 * ({@code Integer.parseInt}, {@code HashMap.put}, measured 2026-11-28).
 */
class ClojureHostImageTypesTest {

	private static final Path NATIVE_IMAGE = Path.of("src", "main", "resources", "META-INF", "native-image",
			"am.ik.rontolisp");

	/**
	 * The binary reads both; {@code java.lang.Thread}'s file is apart because the
	 * playground's Web Image build excludes it (svm-wasm has no thread parker).
	 */
	private static final List<Path> METADATA = List.of(
			NATIVE_IMAGE.resolve(Path.of("rontolisp", "reachability-metadata.json")),
			NATIVE_IMAGE.resolve(Path.of("rontolisp-thread", "reachability-metadata.json")));

	@Test
	void theImageRegistersEveryJdkClassTheFrontendNames() throws Exception {
		List<JsonNode> entries = new ArrayList<>();
		for (Path file : METADATA) {
			JsonNode reflection = JsonMapper.builder()
				.build()
				.readTree(Files.readString(file, StandardCharsets.UTF_8))
				.path("reflection");
			assertThat(reflection.isMissingNode()).as("reflection in %s", file).isFalse();
			List<String> types = new ArrayList<>();
			for (JsonNode entry : reflection) {
				entries.add(entry);
				types.add(entry.path("type").asString(""));
			}
			assertThat(types).as("reflection types in %s", file).isSorted();
		}
		List<String> registered = new ArrayList<>();
		List<String> callable = new ArrayList<>();
		for (JsonNode entry : entries) {
			String type = entry.path("type").asString(null);
			assertThat(type).as("type of %s", entry).isNotNull();
			registered.add(type);
			assertThat(entry.path("allPublicConstructors").asBoolean(false))
				.as("constructors of %s: the bridge constructs a host class reflectively", entry)
				.isTrue();
			assertThat(entry.path("allPublicFields").asBoolean(false)).as("fields of %s", entry).isTrue();
			if (entry.path("allPublicMethods").asBoolean(false)) {
				callable.add(type);
			}
			List<String> members = ClojureInteropLowering.HOST_STATIC_CLASSES.get(type);
			if (members == null || members.isEmpty()) {
				assertThat(entry.path("methods").isMissingNode()).as("methods of %s", entry).isTrue();
			}
			else {
				List<String> methods = new ArrayList<>();
				for (JsonNode method : entry.path("methods")) {
					methods.add(method.path("name").asString(null));
				}
				assertThat(methods).as("methods of %s", entry).containsExactlyElementsOf(members);
			}
		}
		assertThat(callable).as("the types registered with their members, in %s", METADATA)
			.containsExactlyInAnyOrderElementsOf(ClojureInteropLowering.HOST_CALLABLE_CLASSES);
		assertThat(registered).as("reflection types in %s", METADATA)
			.containsExactlyInAnyOrderElementsOf(expectedHostTypes())
			.doesNotHaveDuplicates();
		for (String type : registered) {
			assertThat(jdkClass(type)).as(type).isNotNull();
		}
	}

	/**
	 * The JDK classes the front end's lowering decisions read, on every target: the
	 * {@code PARENTS} throwables and their parents, the hostless receivers, the host
	 * statics macro-time evaluation invokes, the value methods' host kinds, the time
	 * values' kinds, the io values' API types, {@code Object} and {@code Throwable}, and
	 * the types a program makes or calls ({@code HOST_CALLABLE_CLASSES}). The
	 * {@code clojure.lang} throwables of {@code PARENTS} are no JDK classes, so they stay
	 * out: the image holds none of them.
	 * @return the binary names, in name order
	 */
	private static List<String> expectedHostTypes() {
		TreeSet<String> expected = new TreeSet<>();
		for (Map.Entry<String, String> row : ClojureThrowables.PARENTS.entrySet()) {
			expected.add(row.getKey());
			expected.add(row.getValue());
		}
		expected.addAll(ClojureInteropLowering.HOSTLESS_RECEIVERS);
		expected.addAll(ClojureInteropLowering.HOST_STATIC_CLASSES.keySet());
		expected.addAll(ClojureInteropLowering.HOST_CALLABLE_CLASSES);
		expected.addAll(ClojureValueMethodLowering.hostKindTypes());
		expected.addAll(ClojureTimeValueLowering.kindHosts());
		expected.add(ClojureDecimalLowering.HOST);
		expected.addAll(ClojureIoLowering.hostApiTypes());
		expected.add("java.lang.Object");
		expected.add(ClojureThrowables.THROWABLE);
		expected.removeIf(name -> !name.startsWith("java."));
		return List.copyOf(expected);
	}

	private static @Nullable Class<?> jdkClass(String name) {
		try {
			return Class.forName(name, false, ClojureHostImageTypesTest.class.getClassLoader());
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return null;
		}
	}

}
