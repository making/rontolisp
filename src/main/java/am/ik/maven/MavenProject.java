package am.ik.maven;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A project's {@code pom.xml} after Maven's model building
 * ({@link MavenResolver#project}): its dependencies as the model holds them and what its
 * {@code build} says of its sources -- the effective source directory, resource
 * directories and plugins, the super POM's defaults included, each value as interpolated
 * (no project directory is known, so a relative path stays relative).
 *
 * @param dependencies the dependencies, in model order, as
 * {@link MavenResolver#projectDependencies} answers them
 * @param sourceDirectory the source directory
 * @param resourceDirectories the resources' directories, in order (a resource without a
 * directory is left out)
 * @param plugins the build's plugins in Maven's order, the plugin management merged in
 */
public record MavenProject(List<Dependency> dependencies, @Nullable String sourceDirectory,
		List<String> resourceDirectories, List<Plugin> plugins) {

	/**
	 * Copies the lists.
	 * @param dependencies the dependencies
	 * @param sourceDirectory the source directory
	 * @param resourceDirectories the resources' directories
	 * @param plugins the plugins
	 */
	public MavenProject {
		dependencies = List.copyOf(dependencies);
		resourceDirectories = List.copyOf(resourceDirectories);
		plugins = List.copyOf(plugins);
	}

	/**
	 * A build plugin.
	 *
	 * @param groupId the group id ({@code org.apache.maven.plugins} unless written)
	 * @param artifactId the artifact id
	 * @param executions the executions, in Maven's order
	 */
	public record Plugin(@Nullable String groupId, @Nullable String artifactId, List<Execution> executions) {

		/**
		 * Copies the list.
		 * @param groupId the group id
		 * @param artifactId the artifact id
		 * @param executions the executions
		 */
		public Plugin {
			executions = List.copyOf(executions);
		}

	}

	/**
	 * A plugin execution.
	 *
	 * @param id the id ({@code default} unless written)
	 * @param goals the goals
	 * @param configuration the configuration, or {@code null} without one
	 */
	public record Execution(@Nullable String id, List<String> goals, @Nullable ConfigurationNode configuration) {

		/**
		 * Copies the list.
		 * @param id the id
		 * @param goals the goals
		 * @param configuration the configuration
		 */
		public Execution {
			goals = List.copyOf(goals);
		}

	}

}
