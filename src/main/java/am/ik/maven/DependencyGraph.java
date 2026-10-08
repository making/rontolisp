package am.ik.maven;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A dependency graph: the requested dependencies as roots, the nodes below them, and the
 * warnings the POMs on the way raised. Collected ({@link MavenResolver#collect}), it
 * holds every version each path reaches, and selection is the caller's; resolved
 * ({@link MavenResolver#resolve}), Maven's conflict resolution has kept one node per
 * artifact.
 *
 * @param roots one node per requested dependency, in request order
 * @param warnings the POMs that were missing or invalid, each once, in encounter order
 */
public record DependencyGraph(List<DependencyNode> roots, List<String> warnings) {

	/**
	 * Copies the lists.
	 * @param roots the root nodes
	 * @param warnings the warnings
	 */
	public DependencyGraph {
		roots = List.copyOf(roots);
		warnings = List.copyOf(warnings);
	}

	/**
	 * The runtime class path of a resolved graph, as Maven builds a project's: the nodes
	 * in preorder, each artifact once (its first occurrence), keeping those whose scope
	 * is {@code compile} or {@code runtime} and whose type is a class path entry
	 * ({@code jar}, {@code test-jar}, {@code ejb}, ...; never {@code pom} or a type the
	 * session does not know).
	 * @return the artifacts, in class path order
	 */
	public List<Artifact> runtimeClassPath() {
		Set<Artifact> seen = new LinkedHashSet<>();
		List<Artifact> entries = new ArrayList<>();
		preorder(this.roots, seen, entries);
		return entries;
	}

	private static void preorder(List<DependencyNode> nodes, Set<Artifact> seen, List<Artifact> entries) {
		for (DependencyNode node : nodes) {
			Dependency dependency = node.dependency();
			String scope = dependency.scope();
			if (seen.add(dependency.artifact()) && (scope.equals("compile") || scope.equals("runtime"))
					&& ArtifactTypes.addedToClassPath(dependency.type())) {
				entries.add(dependency.artifact());
			}
			preorder(node.children(), seen, entries);
		}
	}

}
