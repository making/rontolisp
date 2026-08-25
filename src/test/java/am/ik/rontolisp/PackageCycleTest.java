package am.ik.rontolisp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package dependency graph must stay a DAG. A cycle between two packages means
 * neither one can be read, moved or tested without the other, and it silently breaks the
 * direction CLAUDE.md declares; this test pins the mechanical half of that rule -- no
 * cycles at all -- over every Java source root in the repository.
 *
 * <p>
 * Edges are read from SOURCE, not from {@code target/classes}: the class output may hold
 * a stale mixture of the default and the {@code -Pweb} source sets, and no single
 * compilation ever contains {@code src/web/java} together with the two out-of-reactor
 * modules. Comments and string/character literals are stripped first, so a class name
 * quoted in a text block or named in Javadoc is not mistaken for a dependency.
 */
class PackageCycleTest {

	private static final List<Path> SOURCE_ROOTS = List.of(Path.of("src", "main", "java"),
			Path.of("src", "web", "java"), Path.of("docs-tool", "src", "main", "java"),
			Path.of("rontolisp-maven-plugin", "src", "main", "java"));

	private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

	/** Every project type is reached through a name rooted at {@code am.ik}. */
	private static final Pattern PROJECT_REFERENCE = Pattern.compile("\\bam\\.ik\\.[\\w.]+");

	@Test
	void packageDependenciesAreAcyclic() throws IOException {
		Map<String, Set<String>> graph = packageGraph();
		assertThat(new CycleFinder(graph).find()).as("package dependency cycle").isEmpty();
	}

	@Test
	void theScanReachesTheWholeSourceTree() throws IOException {
		// Guards the check above against passing vacuously: a scan that silently found
		// nothing has no cycles either.
		Map<String, Set<String>> graph = packageGraph();
		assertThat(graph).hasSizeGreaterThan(10);
		assertThat(graph.getOrDefault("am.ik.rontolisp.eval", Set.of())).contains("am.ik.rontolisp.macro",
				"am.ik.rontolisp.reader", "am.ik.gpu");
		assertThat(graph.getOrDefault("am.ik.rontolisp.reader", Set.of())).doesNotContain("am.ik.rontolisp.eval");
	}

	private static Map<String, Set<String>> packageGraph() throws IOException {
		Map<Path, String> bodies = new LinkedHashMap<>();
		for (Path root : SOURCE_ROOTS) {
			try (Stream<Path> tree = Files.walk(root)) {
				for (Path file : tree.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
					bodies.put(file, stripCommentsAndLiterals(Files.readString(file)));
				}
			}
		}
		Set<String> packages = new TreeSet<>();
		for (String body : bodies.values()) {
			Matcher declaration = PACKAGE_DECLARATION.matcher(body);
			if (declaration.find()) {
				packages.add(declaration.group(1));
			}
		}
		Map<String, Set<String>> graph = new TreeMap<>();
		for (String body : bodies.values()) {
			Matcher declaration = PACKAGE_DECLARATION.matcher(body);
			if (!declaration.find()) {
				continue;
			}
			String from = declaration.group(1);
			Set<String> edges = graph.computeIfAbsent(from, key -> new TreeSet<>());
			Matcher reference = PROJECT_REFERENCE.matcher(body);
			while (reference.find()) {
				String to = enclosingPackage(reference.group(), packages);
				if (!to.isEmpty() && !to.equals(from)) {
					edges.add(to);
				}
			}
		}
		return graph;
	}

	/**
	 * The declared package a referenced name belongs to: the longest prefix of the name
	 * that is a package of this repository, so that a nested type or a static member
	 * resolves to the same package its top-level type does.
	 * @param reference a dotted name found in the source
	 * @param packages every package declared in the repository
	 * @return the owning package, or the empty string when the name is not ours
	 */
	private static String enclosingPackage(String reference, Set<String> packages) {
		String candidate = reference;
		while (!candidate.isEmpty()) {
			if (packages.contains(candidate)) {
				return candidate;
			}
			int dot = candidate.lastIndexOf('.');
			candidate = dot < 0 ? "" : candidate.substring(0, dot);
		}
		return "";
	}

	private static String stripCommentsAndLiterals(String source) {
		StringBuilder stripped = new StringBuilder(source.length());
		int index = 0;
		while (index < source.length()) {
			char current = source.charAt(index);
			if (source.startsWith("//", index)) {
				int end = source.indexOf('\n', index);
				index = end < 0 ? source.length() : end;
			}
			else if (source.startsWith("/*", index)) {
				int end = source.indexOf("*/", index + 2);
				index = end < 0 ? source.length() : end + 2;
			}
			else if (source.startsWith("\"\"\"", index)) {
				int end = source.indexOf("\"\"\"", index + 3);
				index = end < 0 ? source.length() : end + 3;
			}
			else if (current == '"' || current == '\'') {
				index = skipLiteral(source, index);
			}
			else {
				stripped.append(current);
				index++;
			}
		}
		return stripped.toString();
	}

	private static int skipLiteral(String source, int start) {
		char quote = source.charAt(start);
		int index = start + 1;
		while (index < source.length() && source.charAt(index) != quote) {
			index += source.charAt(index) == '\\' ? 2 : 1;
		}
		return index + 1;
	}

	/**
	 * Depth-first search that stops at the first back edge and reports the cycle it
	 * closes, as {@code a -> b -> ... -> a}.
	 */
	private static final class CycleFinder {

		private final Map<String, Set<String>> graph;

		private final Set<String> settled = new HashSet<>();

		private final Set<String> onPath = new HashSet<>();

		private final List<String> path = new ArrayList<>();

		private CycleFinder(Map<String, Set<String>> graph) {
			this.graph = graph;
		}

		private List<String> find() {
			for (String node : this.graph.keySet()) {
				if (this.visit(node)) {
					return List.copyOf(this.path);
				}
			}
			return List.of();
		}

		private boolean visit(String node) {
			if (this.onPath.contains(node)) {
				// Drop the prefix that merely leads into the cycle, then close the loop.
				this.path.subList(0, this.path.indexOf(node)).clear();
				this.path.add(node);
				return true;
			}
			if (!this.settled.add(node)) {
				return false;
			}
			this.path.add(node);
			this.onPath.add(node);
			for (String next : this.graph.getOrDefault(node, Set.of())) {
				if (this.visit(next)) {
					return true;
				}
			}
			this.path.remove(this.path.size() - 1);
			this.onPath.remove(node);
			return false;
		}

	}

}
