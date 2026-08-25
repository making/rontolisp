package am.ik.rontolisp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
 * The CLASS graph is held to the same rule with a stated exception: a reference cycle is
 * allowed only inside a cluster whose mutual dependence IS the design -- a sealed
 * interface and the implementations it permits, a recursive-descent dispatcher and the
 * per-form compilers that hand it their subexpressions, a front end that re-enters
 * itself. Each allowed cluster is named by one hub class in
 * {@link #DESIGNED_MUTUAL_RECURSIONS} together with the reason it is mutual; every such
 * cluster stays inside a single package, and any class cycle without a hub -- a helper
 * merely sitting in the class behind it -- is a defect to fix, not to list.
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
				"am.ik.rontolisp.reader", "am.ik.gpu", "am.ik.objc");
		// The JVM backend embeds both libraries (the bridge templates are written
		// against them and type-checked by javac).
		assertThat(graph.getOrDefault("am.ik.rontolisp.codegen.jvm", Set.of())).contains("am.ik.gpu", "am.ik.objc");
		// The language-independent libraries import no project package.
		assertThat(graph.getOrDefault("am.ik.objc", Set.of())).isEmpty();
		assertThat(graph.getOrDefault("am.ik.rontolisp.reader", Set.of())).doesNotContain("am.ik.rontolisp.eval");
	}

	/**
	 * The class cycles that are the design, each named by the one hub class every cycle
	 * in its package flows through, with the reason the mutual reference is essential. An
	 * entry here is a claim, not a waiver: the class test below fails when a cycle
	 * appears without a hub (an accidental cycle -- fix it), when a cycle crosses a
	 * package boundary, and when a listed hub no longer sits in any cycle (a stale
	 * exemption -- delete it).
	 */
	private static final Map<String, String> DESIGNED_MUTUAL_RECURSIONS = Map.of("am.ik.rontolisp.LispVal",
			"the sealed value model: LispVal permits its implementations, and the symbol/package registries deal in the values that name them",
			"am.ik.gpu.GpuDevice",
			"sealed dispatch seam: GpuDevice permits the device implementations Gpu selects among",
			"am.ik.jvm.ByteCodeWriter",
			"counted-section DSL: the writer serializes the section defs, and a def collects its entries through nested writers",
			"am.ik.wasm.WasmWriter", "counted-section DSL, same shape as am.ik.jvm",
			"am.ik.rontolisp.reader.LispReader",
			"the reader re-enters itself: feature widening re-reads the source (FeaturePushes) and #. datum validation re-parses (LispLexer)",
			"am.ik.rontolisp.eval.LispEvaluator",
			"installed builtins (linalg, ironclad, user macros) call back into the evaluator that installed them",
			"am.ik.rontolisp.codegen.jvm.JvmExprCompiler",
			"recursive-descent dispatch: a per-form compiler compiles its subexpressions through the dispatcher",
			"am.ik.rontolisp.codegen.wasm.WasmExprCompiler", "recursive-descent dispatch, same shape as codegen.jvm");

	@Test
	void classCyclesAreOnlyTheDesignedMutualRecursions() throws IOException {
		Map<String, Set<String>> graph = classGraph();
		List<Set<String>> cycles = new StronglyConnected(graph).components()
			.stream()
			.filter(component -> component.size() > 1)
			.toList();
		Set<String> hubsSeen = new TreeSet<>();
		for (Set<String> cycle : cycles) {
			Set<String> packages = cycle.stream()
				.map(PackageCycleTest::packageOf)
				.collect(java.util.stream.Collectors.toCollection(TreeSet::new));
			assertThat(packages).as("a class cycle crossing packages: %s", cycle).hasSize(1);
			Set<String> hubs = new TreeSet<>(cycle);
			hubs.retainAll(DESIGNED_MUTUAL_RECURSIONS.keySet());
			assertThat(hubs)
				.as("class cycle in %s without exactly one designed hub: %s", packages.iterator().next(),
						summarize(cycle))
				.hasSize(1);
			hubsSeen.addAll(hubs);
		}
		assertThat(hubsSeen).as("every designed-recursion entry must still name a real cycle")
			.containsExactlyInAnyOrderElementsOf(DESIGNED_MUTUAL_RECURSIONS.keySet());
		// Guards against a vacuous class scan: the WASM dispatch cluster is the largest
		// cycle in the tree by an order of magnitude, so a scan that stopped seeing
		// same-package references would shrink it far below this.
		Set<String> wasmDispatch = cycles.stream()
			.filter(cycle -> cycle.contains("am.ik.rontolisp.codegen.wasm.WasmExprCompiler"))
			.findFirst()
			.orElseThrow();
		assertThat(wasmDispatch).hasSizeGreaterThan(100);
	}

	private static String packageOf(String className) {
		return className.substring(0, className.lastIndexOf('.'));
	}

	private static String summarize(Set<String> cycle) {
		return cycle.stream().map(name -> name.substring(name.lastIndexOf('.') + 1)).sorted().limit(20).toList()
				+ (cycle.size() > 20 ? " ... (" + cycle.size() + " classes)" : "");
	}

	private static Map<Path, String> strippedSources() throws IOException {
		Map<Path, String> bodies = new LinkedHashMap<>();
		for (Path root : SOURCE_ROOTS) {
			try (Stream<Path> tree = Files.walk(root)) {
				for (Path file : tree.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
					bodies.put(file, stripCommentsAndLiterals(Files.readString(file)));
				}
			}
		}
		return bodies;
	}

	private static Map<String, Set<String>> packageGraph() throws IOException {
		Map<Path, String> bodies = strippedSources();
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
				String to = longestKnownPrefix(reference.group(), packages);
				if (!to.isEmpty() && !to.equals(from)) {
					edges.add(to);
				}
			}
		}
		return graph;
	}

	/**
	 * The class reference graph: fully-qualified top-level class to the project classes
	 * it references, by dotted {@code am.ik} name anywhere in the file plus bare simple
	 * name for a class of the same package (unless that simple name is imported from
	 * another package, where the import wins). File name is taken as the top-level class
	 * name, which holds for every source file in this repository.
	 */
	private static Map<String, Set<String>> classGraph() throws IOException {
		Map<Path, String> bodies = strippedSources();
		Map<Path, String> classes = new LinkedHashMap<>();
		Map<String, Set<String>> simpleNamesByPackage = new TreeMap<>();
		for (Map.Entry<Path, String> source : bodies.entrySet()) {
			Matcher declaration = PACKAGE_DECLARATION.matcher(source.getValue());
			String fileName = source.getKey().getFileName().toString();
			String simple = fileName.substring(0, fileName.length() - ".java".length());
			if (!declaration.find() || simple.contains("-")) {
				continue; // package-info and friends declare no top-level class
			}
			classes.put(source.getKey(), declaration.group(1) + "." + simple);
			simpleNamesByPackage.computeIfAbsent(declaration.group(1), key -> new TreeSet<>()).add(simple);
		}
		Set<String> known = new TreeSet<>(classes.values());
		Map<String, Set<String>> graph = new TreeMap<>();
		for (Map.Entry<Path, String> source : classes.entrySet()) {
			String from = source.getValue();
			String body = bodies.get(source.getKey());
			Set<String> edges = graph.computeIfAbsent(from, key -> new TreeSet<>());
			Matcher reference = PROJECT_REFERENCE.matcher(body);
			while (reference.find()) {
				String to = longestKnownPrefix(reference.group(), known);
				if (!to.isEmpty() && !to.equals(from)) {
					edges.add(to);
				}
			}
			String ownPackage = packageOf(from);
			Set<String> shadowedByImport = new HashSet<>();
			Matcher imported = IMPORT_DECLARATION.matcher(body);
			while (imported.find()) {
				if (!imported.group(1).equals(ownPackage)) {
					shadowedByImport.add(imported.group(2));
				}
			}
			for (String neighbour : simpleNamesByPackage.getOrDefault(ownPackage, Set.of())) {
				String to = ownPackage + "." + neighbour;
				if (to.equals(from) || shadowedByImport.contains(neighbour)) {
					continue;
				}
				if (Pattern.compile("\\b" + Pattern.quote(neighbour) + "\\b").matcher(body).find()) {
					edges.add(to);
				}
			}
		}
		return graph;
	}

	private static final Pattern IMPORT_DECLARATION = Pattern
		.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\.(\\w+)\\s*;");

	/**
	 * The declared package (or class) a referenced name belongs to: the longest prefix of
	 * the name that is in {@code known}, so that a nested type or a static member
	 * resolves to the same unit its top-level type does.
	 * @param reference a dotted name found in the source
	 * @param known every package (or class) declared in the repository
	 * @return the owning unit, or the empty string when the name is not ours
	 */
	private static String longestKnownPrefix(String reference, Set<String> known) {
		String candidate = reference;
		while (!candidate.isEmpty()) {
			if (known.contains(candidate)) {
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
	 * Tarjan's strongly-connected-components algorithm: every maximal set of mutually
	 * reachable nodes, singletons included (a node is trivially reachable from itself).
	 */
	private static final class StronglyConnected {

		private final Map<String, Set<String>> graph;

		private final Map<String, Integer> index = new HashMap<>();

		private final Map<String, Integer> lowLink = new HashMap<>();

		private final Deque<String> stack = new ArrayDeque<>();

		private final Set<String> onStack = new HashSet<>();

		private final List<Set<String>> components = new ArrayList<>();

		private int counter;

		private StronglyConnected(Map<String, Set<String>> graph) {
			this.graph = graph;
		}

		private List<Set<String>> components() {
			for (String node : this.graph.keySet()) {
				if (!this.index.containsKey(node)) {
					this.connect(node);
				}
			}
			return this.components;
		}

		private void connect(String node) {
			this.index.put(node, this.counter);
			this.lowLink.put(node, this.counter);
			this.counter++;
			this.stack.push(node);
			this.onStack.add(node);
			for (String next : this.graph.getOrDefault(node, Set.of())) {
				if (!this.index.containsKey(next)) {
					this.connect(next);
					this.lowLink.merge(node, this.lowLink.get(next), Math::min);
				}
				else if (this.onStack.contains(next)) {
					this.lowLink.merge(node, this.index.get(next), Math::min);
				}
			}
			if (this.lowLink.get(node).equals(this.index.get(node))) {
				Set<String> component = new TreeSet<>();
				String member;
				do {
					member = this.stack.pop();
					this.onStack.remove(member);
					component.add(member);
				}
				while (!member.equals(node));
				this.components.add(component);
			}
		}

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
