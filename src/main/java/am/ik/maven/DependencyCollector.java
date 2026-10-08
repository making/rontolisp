package am.ik.maven;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;

/**
 * Collects a dependency graph the way Maven Resolver's depth-first collector does under
 * Maven's session (the scope, optional and exclusion selectors, the classic dependency
 * manager, the fat-artifact traverser), with the conflict resolver left out: every
 * version a path reaches stays a node.
 *
 * <ul>
 * <li>A dependency is selected before it is managed. Below the requested dependencies
 * (depth 2 on) a {@code test} or {@code provided} one is dropped -- {@code system} and
 * unknown scopes are kept, as Maven keeps them: one can win a version conflict, and below
 * a {@code runtime} parent an unknown scope derives {@code runtime} -- and so is an
 * optional one. An exclusion anywhere on the path drops the dependency at any depth.</li>
 * <li>The requested dependency management applies from depth 2 (version, scope, optional
 * flag) and adds its exclusions at every depth; the first entry of a key wins. A POM's
 * own dependency management only shaped its own descriptor.</li>
 * <li>A relocated artifact is selected and managed again under its new coordinates; a
 * relocation to another version of the same artifact keeps that version.</li>
 * <li>An artifact (any version) already on its own path is a cycle node, not
 * expanded.</li>
 * <li>{@code war}, {@code ear}, {@code rar} and {@code par} dependencies are not
 * descended into, and no POM is read for a {@code system} one (its file is its
 * {@code systemPath}).</li>
 * <li>The children of an artifact are computed once per set of exclusions in force and
 * shared after that, as Maven's data pool shares them.</li>
 * </ul>
 */
final class DependencyCollector {

	/** Reads descriptors. */
	@FunctionalInterface
	interface Descriptors {

		/**
		 * Reads a descriptor.
		 * @param artifact the artifact
		 * @return its descriptor
		 * @throws MavenResolutionException if it cannot be resolved
		 */
		ArtifactDescriptor read(Artifact artifact) throws MavenResolutionException;

	}

	private record PoolKey(Artifact artifact, Set<Exclusion> exclusions) {
	}

	private final Descriptors descriptors;

	private final Map<String, String> managedVersions = new HashMap<>();

	private final Map<String, String> managedScopes = new HashMap<>();

	private final Map<String, Boolean> managedOptionals = new HashMap<>();

	private final Map<String, Set<Exclusion>> managedExclusions = new HashMap<>();

	private final Map<PoolKey, List<DependencyNode>> pool = new HashMap<>();

	private final Set<String> warnings = new LinkedHashSet<>();

	/**
	 * Creates a collector.
	 * @param descriptors where descriptors come from
	 * @param managed the dependency management the request brings
	 */
	DependencyCollector(Descriptors descriptors, List<Dependency> managed) {
		this.descriptors = descriptors;
		for (Dependency entry : managed) {
			String key = entry.artifact().versionlessKey();
			if (!entry.artifact().version().isEmpty()) {
				this.managedVersions.putIfAbsent(key, entry.artifact().version());
			}
			if (!entry.scope().isEmpty()) {
				this.managedScopes.putIfAbsent(key, entry.scope());
			}
			Boolean optional = entry.optional();
			if (optional != null) {
				this.managedOptionals.putIfAbsent(key, optional);
			}
			if (!entry.exclusions().isEmpty()) {
				this.managedExclusions.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(entry.exclusions());
			}
		}
	}

	/**
	 * Collects the graph below the requested dependencies.
	 * @param dependencies the requested dependencies, which become the roots
	 * @return the graph
	 * @throws MavenResolutionException if a node cannot be resolved
	 */
	DependencyGraph collect(List<Dependency> dependencies) throws MavenResolutionException {
		List<DependencyNode> roots = new ArrayList<>();
		process(dependencies, 1, Set.of(), new ArrayList<>(), roots);
		return new DependencyGraph(roots, new ArrayList<>(this.warnings));
	}

	private void process(List<Dependency> dependencies, int depth, Set<Exclusion> exclusions, List<Artifact> path,
			List<DependencyNode> out) throws MavenResolutionException {
		for (Dependency dependency : dependencies) {
			processDependency(dependency, depth, exclusions, path, out, List.of(), false);
		}
	}

	private void processDependency(Dependency dependency, int depth, Set<Exclusion> exclusions, List<Artifact> path,
			List<DependencyNode> out, List<Artifact> relocations, boolean keepVersion) throws MavenResolutionException {
		if (!selected(dependency, depth, exclusions)) {
			return;
		}
		Managed managed = manage(dependency, depth, keepVersion);
		Artifact artifact = managed.dependency().artifact();
		if (artifact.version().isEmpty()) {
			throw failure(path, artifact, "no version");
		}
		String unsupported = artifact.unsupportedVersion();
		if (unsupported != null) {
			throw failure(path, artifact, unsupported);
		}
		ArtifactDescriptor descriptor;
		try {
			// A system dependency is a file at its systemPath: Maven reads no POM for it.
			descriptor = managed.dependency().scope().equals("system")
					? new ArtifactDescriptor(artifact, List.of(), List.of(), List.of(), List.of())
					: this.descriptors.read(artifact);
		}
		catch (MavenResolutionException ex) {
			throw new MavenResolutionException("while collecting " + render(path, artifact) + ": " + ex.getMessage(),
					ex);
		}
		this.warnings.addAll(descriptor.warnings());
		Dependency resolved = managed.dependency().withArtifact(descriptor.artifact());
		if (onPath(path, descriptor.artifact())) {
			out.add(new DependencyNode(resolved, relocations, managed.premanagedVersion(), managed.premanagedScope(),
					managed.premanagedOptional(), true, List.of()));
			return;
		}
		if (!descriptor.relocations().isEmpty()) {
			boolean sameArtifact = artifact.groupId().equals(descriptor.artifact().groupId())
					&& artifact.artifactId().equals(descriptor.artifact().artifactId());
			processDependency(resolved, depth, exclusions, path, out, descriptor.relocations(), sameArtifact);
			return;
		}
		List<DependencyNode> children = List.of();
		if (!ArtifactTypes.includesDependencies(resolved.type()) && !descriptor.dependencies().isEmpty()) {
			Set<Exclusion> childExclusions = new LinkedHashSet<>(exclusions);
			childExclusions.addAll(resolved.exclusions());
			PoolKey key = new PoolKey(resolved.artifact(), Set.copyOf(childExclusions));
			List<DependencyNode> pooled = this.pool.get(key);
			if (pooled == null) {
				List<DependencyNode> built = new ArrayList<>();
				path.add(resolved.artifact());
				try {
					process(descriptor.dependencies(), depth + 1, key.exclusions(), path, built);
				}
				finally {
					path.remove(path.size() - 1);
				}
				pooled = List.copyOf(built);
				this.pool.put(key, pooled);
			}
			children = pooled;
		}
		out.add(new DependencyNode(resolved, relocations, managed.premanagedVersion(), managed.premanagedScope(),
				managed.premanagedOptional(), false, children));
	}

	private static boolean selected(Dependency dependency, int depth, Set<Exclusion> exclusions) {
		if (depth >= 2) {
			String scope = dependency.scope();
			if (scope.equals("test") || scope.equals("provided")) {
				return false;
			}
			if (dependency.isOptional()) {
				return false;
			}
		}
		for (Exclusion exclusion : exclusions) {
			if (exclusion.matches(dependency.artifact())) {
				return false;
			}
		}
		return true;
	}

	private record Managed(Dependency dependency, @Nullable String premanagedVersion, @Nullable String premanagedScope,
			@Nullable Boolean premanagedOptional) {
	}

	private Managed manage(Dependency dependency, int depth, boolean keepVersion) {
		String key = dependency.artifact().versionlessKey();
		Artifact artifact = dependency.artifact();
		String scope = dependency.scope();
		Boolean optional = dependency.optional();
		List<Exclusion> exclusions = dependency.exclusions();
		String premanagedVersion = null;
		String premanagedScope = null;
		Boolean premanagedOptional = null;
		if (depth >= 2) {
			String version = this.managedVersions.get(key);
			if (version != null && !keepVersion) {
				premanagedVersion = artifact.version();
				artifact = artifact.withVersion(version);
			}
			String managedScope = this.managedScopes.get(key);
			if (managedScope != null) {
				premanagedScope = scope;
				scope = managedScope;
			}
			Boolean managedOptional = this.managedOptionals.get(key);
			if (managedOptional != null) {
				premanagedOptional = dependency.isOptional();
				optional = managedOptional;
			}
		}
		Set<Exclusion> managedExclusion = this.managedExclusions.get(key);
		if (managedExclusion != null) {
			Set<Exclusion> all = new LinkedHashSet<>(exclusions);
			all.addAll(managedExclusion);
			exclusions = new ArrayList<>(all);
		}
		return new Managed(new Dependency(artifact, dependency.type(), scope, optional, exclusions), premanagedVersion,
				premanagedScope, premanagedOptional);
	}

	private static boolean onPath(List<Artifact> path, Artifact artifact) {
		String key = artifact.versionlessKey();
		for (Artifact ancestor : path) {
			if (ancestor.versionlessKey().equals(key)) {
				return true;
			}
		}
		return false;
	}

	private static MavenResolutionException failure(List<Artifact> path, Artifact artifact, String reason) {
		return new MavenResolutionException("while collecting " + render(path, artifact) + ": " + reason);
	}

	private static String render(List<Artifact> path, Artifact artifact) {
		StringJoiner joined = new StringJoiner(" -> ");
		for (Artifact ancestor : path) {
			joined.add(ancestor.toString());
		}
		joined.add(artifact.toString());
		return joined.toString();
	}

}
