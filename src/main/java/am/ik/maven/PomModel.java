package am.ik.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The part of a POM's model that decides a dependency graph, as Maven's model classes
 * hold it: a field a POM leaves out is {@code null}, one it writes empty is the empty
 * string (Maven tells the two apart: an empty version is "missing", an absent one
 * inherits). The raw model {@link PomReader} answers and every stage of
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
 */
record PomModel(@Nullable String modelVersion, @Nullable Parent parent, @Nullable String groupId,
		@Nullable String artifactId, @Nullable String version, @Nullable String packaging, @Nullable String name,
		@Nullable String description, Map<String, String> properties, List<Dep> dependencies,
		@Nullable List<Dep> managedDependencies, List<Profile> profiles, @Nullable Relocation relocation,
		List<String> modules) {

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
				this.profiles, this.relocation, this.modules);
	}

	PomModel withContent(@Nullable String newModelVersion, @Nullable String newDescription,
			Map<String, String> newProperties, List<Dep> newDependencies, @Nullable List<Dep> newManaged,
			List<String> newModules) {
		return new PomModel(newModelVersion, this.parent, this.groupId, this.artifactId, this.version, this.packaging,
				this.name, newDescription, newProperties, newDependencies, newManaged, this.profiles, this.relocation,
				newModules);
	}

	PomModel withDependencies(List<Dep> newDependencies, @Nullable List<Dep> newManaged) {
		return new PomModel(this.modelVersion, this.parent, this.groupId, this.artifactId, this.version, this.packaging,
				this.name, this.description, this.properties, newDependencies, newManaged, this.profiles,
				this.relocation, this.modules);
	}

	/**
	 * A parent reference.
	 *
	 * @param groupId the parent's group id
	 * @param artifactId the parent's artifact id
	 * @param version the parent's version
	 */
	record Parent(@Nullable String groupId, @Nullable String artifactId, @Nullable String version) {

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
	 */
	record Profile(@Nullable String id, @Nullable Activation activation, Map<String, String> properties,
			List<Dep> dependencies, @Nullable List<Dep> managedDependencies, List<String> modules) {

		Profile {
			properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
			dependencies = List.copyOf(dependencies);
			managedDependencies = managedDependencies == null ? null : List.copyOf(managedDependencies);
			modules = List.copyOf(modules);
		}

		Profile withActivation(@Nullable Activation newActivation) {
			return new Profile(this.id, newActivation, this.properties, this.dependencies, this.managedDependencies,
					this.modules);
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
