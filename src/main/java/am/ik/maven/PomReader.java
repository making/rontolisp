package am.ik.maven;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Reads the {@link PomModel} out of a POM the way Maven's reader does for a dependency's
 * POM (lenient mode): the root element's name is not checked, an unknown element is
 * skipped, values are trimmed, and what the reader does reject is rejected -- a field
 * written twice, text between the elements of a structure, an element inside a value.
 *
 * <p>
 * Only the elements a dependency graph needs are read; the others ({@code build},
 * {@code reporting}, {@code scm}, ...) are skipped whole, so malformed content inside
 * them that Maven's full reader would reject is accepted here.
 */
final class PomReader {

	/** The fields of a 4.0.0 project; each may appear once. */
	private static final Set<String> PROJECT_FIELDS = Set.of("modelVersion", "parent", "groupId", "artifactId",
			"version", "packaging", "name", "description", "url", "inceptionYear", "organization", "licenses",
			"developers", "contributors", "mailingLists", "prerequisites", "modules", "scm", "issueManagement",
			"ciManagement", "distributionManagement", "properties", "dependencyManagement", "dependencies",
			"repositories", "pluginRepositories", "build", "reports", "reporting", "profiles");

	private static final Set<String> PARENT_FIELDS = Set.of("groupId", "artifactId", "version", "relativePath");

	private static final Set<String> DEPENDENCY_FIELDS = Set.of("groupId", "artifactId", "version", "type",
			"classifier", "scope", "systemPath", "exclusions", "optional");

	private static final Set<String> EXCLUSION_FIELDS = Set.of("groupId", "artifactId");

	private static final Set<String> PROFILE_FIELDS = Set.of("id", "activation", "build", "modules",
			"distributionManagement", "properties", "dependencyManagement", "dependencies", "repositories",
			"pluginRepositories", "reports", "reporting");

	private static final Set<String> ACTIVATION_FIELDS = Set.of("activeByDefault", "jdk", "os", "property", "file");

	private static final Set<String> OS_FIELDS = Set.of("name", "family", "arch", "version");

	private static final Set<String> PROPERTY_FIELDS = Set.of("name", "value");

	private static final Set<String> FILE_FIELDS = Set.of("missing", "exists");

	private static final Set<String> DISTRIBUTION_FIELDS = Set.of("repository", "snapshotRepository", "site",
			"downloadUrl", "relocation", "status");

	private static final Set<String> RELOCATION_FIELDS = Set.of("groupId", "artifactId", "version", "message");

	private static final Set<String> MANAGEMENT_FIELDS = Set.of("dependencies");

	private PomReader() {
	}

	/**
	 * Reads a POM.
	 * @param bytes the POM file
	 * @return the raw model
	 * @throws InvalidPomException if Maven's reader would not read it
	 */
	static PomModel read(byte[] bytes) throws InvalidPomException {
		try {
			return project(XmlParser.parse(bytes));
		}
		catch (XmlParser.Malformed ex) {
			throw new InvalidPomException("Non-parseable POM: " + ex.getMessage());
		}
	}

	private static PomModel project(XmlElement project) throws XmlParser.Malformed {
		structure(project, PROJECT_FIELDS);
		XmlElement parent = project.child("parent");
		XmlElement management = project.child("dependencyManagement");
		XmlElement distribution = project.child("distributionManagement");
		return new PomModel(leaf(project, "modelVersion"), parent == null ? null : parent(parent),
				leaf(project, "groupId"), leaf(project, "artifactId"), leaf(project, "version"),
				leaf(project, "packaging"), leaf(project, "name"), leaf(project, "description"),
				properties(project.child("properties")), dependencies(project.child("dependencies")),
				management == null ? null : management(management), profiles(project.child("profiles")),
				distribution == null ? null : relocation(distribution), modules(project.child("modules")));
	}

	private static PomModel.Parent parent(XmlElement parent) throws XmlParser.Malformed {
		structure(parent, PARENT_FIELDS);
		return new PomModel.Parent(leaf(parent, "groupId"), leaf(parent, "artifactId"), leaf(parent, "version"));
	}

	private static List<PomModel.Dep> management(XmlElement management) throws XmlParser.Malformed {
		structure(management, MANAGEMENT_FIELDS);
		return dependencies(management.child("dependencies"));
	}

	private static List<PomModel.Dep> dependencies(@Nullable XmlElement dependencies) throws XmlParser.Malformed {
		List<PomModel.Dep> result = new ArrayList<>();
		for (XmlElement dependency : items(dependencies, "dependency")) {
			structure(dependency, DEPENDENCY_FIELDS);
			List<PomModel.Excl> exclusions = new ArrayList<>();
			for (XmlElement exclusion : items(dependency.child("exclusions"), "exclusion")) {
				structure(exclusion, EXCLUSION_FIELDS);
				exclusions.add(new PomModel.Excl(leaf(exclusion, "groupId"), leaf(exclusion, "artifactId")));
			}
			result.add(new PomModel.Dep(leaf(dependency, "groupId"), leaf(dependency, "artifactId"),
					leaf(dependency, "version"), leaf(dependency, "type"), leaf(dependency, "classifier"),
					leaf(dependency, "scope"), leaf(dependency, "systemPath"), leaf(dependency, "optional"),
					exclusions));
		}
		return result;
	}

	private static List<PomModel.Profile> profiles(@Nullable XmlElement profiles) throws XmlParser.Malformed {
		List<PomModel.Profile> result = new ArrayList<>();
		for (XmlElement profile : items(profiles, "profile")) {
			structure(profile, PROFILE_FIELDS);
			XmlElement activation = profile.child("activation");
			XmlElement management = profile.child("dependencyManagement");
			result.add(new PomModel.Profile(leaf(profile, "id"), activation == null ? null : activation(activation),
					properties(profile.child("properties")), dependencies(profile.child("dependencies")),
					management == null ? null : management(management), modules(profile.child("modules"))));
		}
		return result;
	}

	private static PomModel.Activation activation(XmlElement activation) throws XmlParser.Malformed {
		structure(activation, ACTIVATION_FIELDS);
		XmlElement os = activation.child("os");
		XmlElement property = activation.child("property");
		XmlElement file = activation.child("file");
		PomModel.Os osCondition = null;
		if (os != null) {
			structure(os, OS_FIELDS);
			osCondition = new PomModel.Os(leaf(os, "name"), leaf(os, "family"), leaf(os, "arch"), leaf(os, "version"));
		}
		PomModel.Property propertyCondition = null;
		if (property != null) {
			structure(property, PROPERTY_FIELDS);
			propertyCondition = new PomModel.Property(leaf(property, "name"), leaf(property, "value"));
		}
		PomModel.FileCheck fileCondition = null;
		if (file != null) {
			structure(file, FILE_FIELDS);
			fileCondition = new PomModel.FileCheck(leaf(file, "exists"), leaf(file, "missing"));
		}
		return new PomModel.Activation(leaf(activation, "activeByDefault"), leaf(activation, "jdk"), osCondition,
				propertyCondition, fileCondition);
	}

	private static PomModel.@Nullable Relocation relocation(XmlElement distribution) throws XmlParser.Malformed {
		structure(distribution, DISTRIBUTION_FIELDS);
		XmlElement relocation = distribution.child("relocation");
		if (relocation == null) {
			return null;
		}
		structure(relocation, RELOCATION_FIELDS);
		return new PomModel.Relocation(leaf(relocation, "groupId"), leaf(relocation, "artifactId"),
				leaf(relocation, "version"), leaf(relocation, "message"));
	}

	private static Map<String, String> properties(@Nullable XmlElement properties) throws XmlParser.Malformed {
		Map<String, String> result = new LinkedHashMap<>();
		if (properties == null) {
			return result;
		}
		containerText(properties);
		for (XmlElement property : properties.children()) {
			result.put(property.name(), value(property));
		}
		return result;
	}

	private static List<String> modules(@Nullable XmlElement modules) throws XmlParser.Malformed {
		List<String> result = new ArrayList<>();
		for (XmlElement module : items(modules, "module")) {
			result.add(value(module));
		}
		return result;
	}

	/**
	 * The items of a list element; any other child is skipped, as the lenient reader
	 * does.
	 */
	static List<XmlElement> items(@Nullable XmlElement list, String itemName) throws XmlParser.Malformed {
		if (list == null) {
			return List.of();
		}
		containerText(list);
		return list.children(itemName);
	}

	/**
	 * Checks a structure element: no text between its elements, and none of its known
	 * fields twice.
	 */
	static void structure(XmlElement element, Set<String> fields) throws XmlParser.Malformed {
		containerText(element);
		Set<String> seen = new HashSet<>();
		for (XmlElement child : element.children()) {
			if (fields.contains(child.name()) && !seen.add(child.name())) {
				throw new XmlParser.Malformed("Duplicated tag: '" + child.name() + "' (line " + child.line() + ")");
			}
		}
	}

	/** A structure holds elements and XML whitespace between them, nothing else. */
	private static void containerText(XmlElement element) throws XmlParser.Malformed {
		String text = element.text();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
				throw new XmlParser.Malformed("expected START_TAG or END_TAG not TEXT (in <" + element.name()
						+ ">, line " + element.line() + ")");
			}
		}
	}

	/** The trimmed value of the first child of that name, {@code null} without one. */
	static @Nullable String leaf(XmlElement parent, String name) throws XmlParser.Malformed {
		XmlElement child = parent.child(name);
		return child == null ? null : value(child);
	}

	private static String value(XmlElement element) throws XmlParser.Malformed {
		if (!element.children().isEmpty()) {
			throw new XmlParser.Malformed("parser must be on START_TAG or TEXT to read text (<" + element.name()
					+ "> holds <" + element.children().get(0).name() + ">, line " + element.line() + ")");
		}
		return element.text().trim();
	}

}
