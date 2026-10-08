package am.ik.maven;

import java.util.List;

/**
 * A collected dependency graph: the requested dependencies as roots, every version each
 * path reaches below them, and the warnings the POMs on the way raised. Selection --
 * which version of an artifact wins -- is the caller's.
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

}
