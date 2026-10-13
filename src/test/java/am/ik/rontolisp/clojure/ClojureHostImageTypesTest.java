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
 * {@code ClojureInteropLowering.HOSTLESS_RECEIVERS}, {@code ClojureValueMethodLowering}'s
 * host kinds, {@code ClojureTimeValueLowering}'s kinds, {@code ClojureIoLowering}'s API
 * types, {@code Object} and {@code Throwable} -- is registered as a type in
 * {@code reachability-metadata.json}, so the image answers every member query of it as a
 * JVM does. A class a program names outside them stays partial without a JDK.
 */
class ClojureHostImageTypesTest {

	private static final Path METADATA = Path.of("src", "main", "resources", "META-INF", "native-image",
			"am.ik.rontolisp", "rontolisp", "reachability-metadata.json");

	@Test
	void theImageRegistersEveryJdkClassTheFrontendNames() throws Exception {
		JsonNode reflection = JsonMapper.builder()
			.build()
			.readTree(Files.readString(METADATA, StandardCharsets.UTF_8))
			.path("reflection");
		assertThat(reflection.isMissingNode()).as("reflection in %s", METADATA).isFalse();
		List<String> registered = new ArrayList<>();
		for (JsonNode entry : reflection) {
			assertThat(entry.size()).as("keys of %s", entry).isEqualTo(1);
			String type = entry.path("type").asString(null);
			assertThat(type).as("type of %s", entry).isNotNull();
			registered.add(type);
		}
		assertThat(registered).as("reflection types in %s", METADATA).isSorted();
		assertThat(registered).as("reflection types in %s", METADATA).containsExactlyElementsOf(expectedHostTypes());
		for (String type : registered) {
			assertThat(jdkClass(type)).as(type).isNotNull();
		}
	}

	/**
	 * The JDK classes the front end's lowering decisions read, on every target: the
	 * {@code PARENTS} throwables and their parents, the hostless receivers, the value
	 * methods' host kinds, the time values' kinds, the io values' API types, and
	 * {@code Object} and {@code Throwable}. The {@code clojure.lang} throwables of
	 * {@code PARENTS} are no JDK classes, so they stay out: the image holds none of them.
	 * @return the binary names, in name order
	 */
	private static List<String> expectedHostTypes() {
		TreeSet<String> expected = new TreeSet<>();
		for (Map.Entry<String, String> row : ClojureThrowables.PARENTS.entrySet()) {
			expected.add(row.getKey());
			expected.add(row.getValue());
		}
		expected.addAll(ClojureInteropLowering.HOSTLESS_RECEIVERS);
		expected.addAll(ClojureValueMethodLowering.hostKindTypes());
		expected.addAll(ClojureTimeValueLowering.kindHosts());
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
