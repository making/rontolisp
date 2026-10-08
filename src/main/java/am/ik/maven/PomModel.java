package am.ik.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The part of a POM's model that decides a dependency graph or a project's sources, as
 * Maven's model classes hold it: a field a POM leaves out is {@code null}, one it writes
 * empty is the empty string (Maven tells the two apart: an empty version is "missing", an
 * absent one inherits). The raw model {@link PomReader} answers and every stage of
 * {@link ModelBuilder} share this shape.
 *
 * @param modelVersion the model version
 * @param parent the parent reference
 * @param groupId the group id
 * @param artifactId the artifact id
 * @param version the version
 * @param packaging the packaging ({@code jar} when absent, see
 * {@link #effectivePackaging})
 * @param name the display name (only read through {@code ${project.name}})
 * @param description the description (only read through {@code ${project.description}})
 * @param properties the properties, in document order
 * @param dependencies the dependencies
 * @param managedDependencies the dependency management, or {@code null} without one
 * @param profiles the profiles
 * @param relocation the {@code distributionManagement} relocation
 * @param modules the module names
 * @param build the {@code build} section, or {@code null} without one
 */
record PomModel(@Nullable String modelVersion, @Nullable Parent parent, @Nullable String groupId,
		@Nullable String artifactId, @Nullable String version, @Nullable String packaging, @Nullable String name,
		@Nullable String description, Map<String, String> properties, List<Dep> dependencies,
		@Nullable List<Dep> managedDependencies, List<Profile> profiles, @Nullable Relocation relocation,
		List<String> modules, @Nullable Build build) {

	/**
	 * Copies the collections, keeping their order.
	 */
	PomModel {
		properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
		dependencies = List.copyOf(dependencies);
		managedDependencies = managedDependencies == null ? null : List.copyOf(managedDependencies);
		profiles = List.copyOf(profiles);
		modules = List.copyOf(modules);
	}

	/**
	 * The packaging, {@code jar} when the POM leaves it out.
	 * @return the packaging
	 */
	String effectivePackaging() {
		return this.packaging == null ? "jar" : this.packaging;
	}

	/**
	 * {@code groupId:artifactId:version} as written, Maven's model id.
	 * @return the id
	 */
	String id() {
		return (this.groupId == null ? "[unknown-group-id]" : this.groupId) + ":"
				+ (this.artifactId == null ? "[unknown-artifact-id]" : this.artifactId) + ":"
				+ (this.version == null ? "[unknown-version]" : this.version);
	}

	PomModel withCoordinates(@Nullable String newGroupId, @Nullable String newVersion) {
		return new PomModel(this.modelVersion, this.parent, newGroupId, this.artifactId, newVersion, this.packaging,
				this.name, this.description, this.properties, this.dependencies, this.managedDependencies,
				this.profiles, this.relocation, this.modules, this.build);
	}

	PomModel withContent(@Nullable String newDescription, Map<String, String> newProperties, List<Dep> newDependencies,
			@Nullable List<Dep> newManaged, List<String> newModules, @Nullable Build newBuild) {
		return new PomModel(this.modelVersion, this.parent, this.groupId, this.artifactId, this.version, this.packaging,
				this.name, newDescription, newProperties, newDependencies, newManaged, this.profiles, this.relocation,
				newModules, newBuild);
	}

	PomModel withDependencies(List<Dep> newDependencies, @Nullable List<Dep> newManaged) {
		return new PomModel(this.modelVersion, this.parent, this.groupId, this.artifactId, this.version, this.packaging,
				this.name, this.description, this.properties, newDependencies, newManaged, this.profiles,
				this.relocation, this.modules, this.build);
	}

	PomModel withBuild(@Nullable Build newBuild) {
		return new PomModel(this.modelVersion, this.parent, this.groupId, this.artifactId, this.version, this.packaging,
				this.name, this.description, this.properties, this.dependencies, this.managedDependencies,
				this.profiles, this.relocation, this.modules, newBuild);
	}

	/**
	 * A parent reference.
	 *
	 * @param groupId the parent's group id
	 * @param artifactId the parent's artifact id
	 * @param version the parent's version
	 * @param relativePath the {@code relativePath} as written, {@code null} when absent
	 */
	record Parent(@Nullable String groupId, @Nullable String artifactId, @Nullable String version,
			@Nullable String relativePath) {

		/**
		 * Where the parent's POM is looked for beside a child read from a file: the path
		 * written, Maven's default {@code ../pom.xml} when none is, nowhere when it is
		 * empty.
		 * @return the path
		 */
		String effectiveRelativePath() {
			return this.relativePath == null ? "../pom.xml" : this.relativePath;
		}

		String id() {
			return this.groupId + ":" + this.artifactId + ":" + this.version;
		}

	}

	/**
	 * A dependency as written ({@code dependencies} or {@code dependencyManagement}).
	 *
	 * @param groupId the group id
	 * @param artifactId the artifact id
	 * @param version the version
	 * @param type the type ({@code jar} when absent, see {@link #effectiveType})
	 * @param classifier the classifier
	 * @param scope the scope
	 * @param systemPath the system path
	 * @param optional the optional flag as written
	 * @param exclusions the exclusions
	 */
	record Dep(@Nullable String groupId, @Nullable String artifactId, @Nullable String version, @Nullable String type,
			@Nullable String classifier, @Nullable String scope, @Nullable String systemPath, @Nullable String optional,
			List<Excl> exclusions) {

		Dep {
			exclusions = List.copyOf(exclusions);
		}

		String effectiveType() {
			return this.type == null ? "jar" : this.type;
		}

		/**
		 * Maven's management key, {@code groupId:artifactId:type[:classifier]}: what
		 * inheritance, profiles and dependency management match dependencies by.
		 * @return the key
		 */
		String managementKey() {
			return this.groupId + ":" + this.artifactId + ":" + effectiveType()
					+ (this.classifier != null ? ":" + this.classifier : "");
		}

		Dep with(@Nullable String newVersion, @Nullable String newScope, @Nullable String newSystemPath,
				List<Excl> newExclusions) {
			return new Dep(this.groupId, this.artifactId, newVersion, this.type, this.classifier, newScope,
					newSystemPath, this.optional, newExclusions);
		}

	}

	/**
	 * An exclusion as written.
	 *
	 * @param groupId the group id
	 * @param artifactId the artifact id
	 */
	record Excl(@Nullable String groupId, @Nullable String artifactId) {
	}

	/**
	 * A profile.
	 *
	 * @param id the profile id
	 * @param activation the activation, or {@code null} without one
	 * @param properties the profile's properties
	 * @param dependencies the profile's dependencies
	 * @param managedDependencies the profile's dependency management, or {@code null}
	 * @param modules the profile's modules
	 * @param build the profile's {@code build} (a {@code BuildBase}: its source and
	 * output directories {@code null}), or {@code null} without one
	 */
	record Profile(@Nullable String id, @Nullable Activation activation, Map<String, String> properties,
			List<Dep> dependencies, @Nullable List<Dep> managedDependencies, List<String> modules,
			@Nullable Build build) {

		Profile {
			properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
			dependencies = List.copyOf(dependencies);
			managedDependencies = managedDependencies == null ? null : List.copyOf(managedDependencies);
			modules = List.copyOf(modules);
		}

		Profile withActivation(@Nullable Activation newActivation) {
			return new Profile(this.id, newActivation, this.properties, this.dependencies, this.managedDependencies,
					this.modules, this.build);
		}

	}

	/**
	 * A profile's activation.
	 *
	 * @param activeByDefault the {@code activeByDefault} flag as written
	 * @param jdk the JDK condition
	 * @param os the operating-system condition
	 * @param property the property condition
	 * @param file the file condition
	 */
	record Activation(@Nullable String activeByDefault, @Nullable String jdk, @Nullable Os os,
			@Nullable Property property, @Nullable FileCheck file) {
	}

	/**
	 * An operating-system condition.
	 *
	 * @param name the OS name
	 * @param family the OS family
	 * @param arch the architecture
	 * @param version the OS version
	 */
	record Os(@Nullable String name, @Nullable String family, @Nullable String arch, @Nullable String version) {
	}

	/**
	 * A property condition.
	 *
	 * @param name the property name, {@code !} prefixed for absence
	 * @param value the value, {@code !} prefixed for inequality
	 */
	record Property(@Nullable String name, @Nullable String value) {
	}

	/**
	 * A file condition.
	 *
	 * @param exists the path that must exist
	 * @param missing the path that must not
	 */
	record FileCheck(@Nullable String exists, @Nullable String missing) {
	}

	/**
	 * A {@code distributionManagement} relocation.
	 *
	 * @param groupId the new group id
	 * @param artifactId the new artifact id
	 * @param version the new version
	 * @param message the relocation message
	 */
	record Relocation(@Nullable String groupId, @Nullable String artifactId, @Nullable String version,
			@Nullable String message) {
	}

	/**
	 * A {@code build} section, or a profile's {@code BuildBase} (its source and output
	 * directories {@code null}). A field left out is {@code null}.
	 *
	 * @param sourceDirectory the source directory
	 * @param scriptSourceDirectory the script source directory
	 * @param testSourceDirectory the test source directory
	 * @param outputDirectory the output directory
	 * @param testOutputDirectory the test output directory
	 * @param directory the build directory
	 * @param finalName the final name
	 * @param defaultGoal the default goal
	 * @param resources the resources
	 * @param testResources the test resources
	 * @param filters the filter files
	 * @param plugins the plugins
	 * @param pluginManagement the plugin management's plugins, or {@code null} without
	 * one
	 */
	record Build(@Nullable String sourceDirectory, @Nullable String scriptSourceDirectory,
			@Nullable String testSourceDirectory, @Nullable String outputDirectory,
			@Nullable String testOutputDirectory, @Nullable String directory, @Nullable String finalName,
			@Nullable String defaultGoal, List<Resource> resources, List<Resource> testResources, List<String> filters,
			List<Plugin> plugins, @Nullable List<Plugin> pluginManagement) {

		/** A build with nothing in it, what Maven creates to merge into. */
		static final Build EMPTY = new Build(null, null, null, null, null, null, null, null, List.of(), List.of(),
				List.of(), List.of(), null);

		Build {
			resources = List.copyOf(resources);
			testResources = List.copyOf(testResources);
			filters = List.copyOf(filters);
			plugins = List.copyOf(plugins);
			pluginManagement = pluginManagement == null ? null : List.copyOf(pluginManagement);
		}

		Build withPlugins(List<Plugin> newPlugins, @Nullable List<Plugin> newManagement) {
			return new Build(this.sourceDirectory, this.scriptSourceDirectory, this.testSourceDirectory,
					this.outputDirectory, this.testOutputDirectory, this.directory, this.finalName, this.defaultGoal,
					this.resources, this.testResources, this.filters, newPlugins, newManagement);
		}

	}

	/**
	 * A resource; only its directory is read.
	 *
	 * @param directory the directory, {@code null} when left out
	 */
	record Resource(@Nullable String directory) {
	}

	/**
	 * A plugin.
	 *
	 * @param groupId the group id ({@code org.apache.maven.plugins} when left out, as
	 * Maven's model defaults it)
	 * @param artifactId the artifact id
	 * @param version the version
	 * @param inherited the {@code inherited} flag as written
	 * @param configuration the plugin-level configuration
	 * @param executions the executions
	 */
	record Plugin(@Nullable String groupId, @Nullable String artifactId, @Nullable String version,
			@Nullable String inherited, @Nullable ConfigurationNode configuration, List<Execution> executions) {

		/**
		 * A plugin with nothing in it, its group id too: what inheritance merges into.
		 */
		static final Plugin BLANK = new Plugin(null, null, null, null, null, List.of());

		Plugin {
			executions = List.copyOf(executions);
		}

		/**
		 * Maven's plugin key, {@code groupId:artifactId}.
		 * @return the key
		 */
		String key() {
			return this.groupId + ":" + this.artifactId;
		}

		/**
		 * {@code ConfigurationContainer.isInherited}: true unless written otherwise.
		 * @return whether a child inherits it
		 */
		boolean isInherited() {
			return this.inherited == null || Boolean.parseBoolean(this.inherited);
		}

	}

	/**
	 * A plugin execution.
	 *
	 * @param id the id ({@code default} when left out, as Maven's model defaults it)
	 * @param phase the phase
	 * @param goals the goals
	 * @param inherited the {@code inherited} flag as written
	 * @param configuration the configuration
	 */
	record Execution(@Nullable String id, @Nullable String phase, List<String> goals, @Nullable String inherited,
			@Nullable ConfigurationNode configuration) {

		Execution {
			goals = List.copyOf(goals);
		}

	}

	/**
	 * Merges {@code source} into {@code target} by management key the way Maven's model
	 * merger does: with {@code sourceDominant} a source entry replaces the target entry
	 * of the same key in place, otherwise the target entry stays; new keys are appended.
	 * @param target the receiving list
	 * @param source the merged-in list
	 * @param sourceDominant whether the source wins
	 * @return the merged list
	 */
	static List<Dep> mergeByKey(List<Dep> target, List<Dep> source, boolean sourceDominant) {
		if (source.isEmpty()) {
			return target;
		}
		Map<String, Dep> merged = new LinkedHashMap<>();
		for (Dep dep : target) {
			merged.put(dep.managementKey(), dep);
		}
		for (Dep dep : source) {
			if (sourceDominant) {
				merged.put(dep.managementKey(), dep);
			}
			else {
				merged.putIfAbsent(dep.managementKey(), dep);
			}
		}
		return new ArrayList<>(merged.values());
	}

}
