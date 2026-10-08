package am.ik.maven;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Reads the {@link PomModel} out of a POM the way Maven's reader
 * ({@code MavenXpp3Reader}) does, in its strict or its lenient mode ({@link PomSchema}
 * checks the tree as that mode reads it): values are trimmed, and of the model only what
 * a dependency graph, a project's source directories or the model's validation reads is
 * kept -- of {@code build}, the directories, the resources (directory and filtering), the
 * filters and the plugins (coordinates, {@code inherited}, {@code extensions},
 * executions, configurations, dependencies) with their management; the repositories, the
 * distribution's status and repositories and the reporting plugins' coordinates.
 */
final class PomReader {

	private PomReader() {
	}

	/**
	 * A POM read.
	 *
	 * @param model the model
	 * @param malformed what the strict reader refused, when only the lenient one read it
	 */
	record Read(PomModel model, @Nullable String malformed) {
	}

	/**
	 * Reads a POM with the lenient reader.
	 * @param bytes the POM file
	 * @return the raw model
	 * @throws InvalidPomException if Maven's reader would not read it
	 */
	static PomModel read(byte[] bytes) throws InvalidPomException {
		try {
			return read(bytes, false).model();
		}
		catch (XmlParser.Malformed ex) {
			throw new InvalidPomException(ModelProblem.fatal("Non-parseable POM: " + ex.getMessage()));
		}
	}

	/**
	 * Reads a POM as Maven's model builder does: strictly when asked, and then, when the
	 * strict reader refuses it, again leniently.
	 * @param bytes the POM file
	 * @param strict whether the strict reader reads it first
	 * @return the model, and what the strict reader refused
	 * @throws XmlParser.Malformed if no reader reads it: the first reader's refusal
	 */
	static Read read(byte[] bytes, boolean strict) throws XmlParser.Malformed {
		XmlElement project = XmlParser.parse(bytes);
		if (!strict) {
			PomSchema.check(project, false);
			return new Read(project(project), null);
		}
		try {
			PomSchema.check(project, true);
			return new Read(project(project), null);
		}
		catch (XmlParser.Malformed ex) {
			try {
				PomSchema.check(project, false);
			}
			catch (XmlParser.Malformed lenient) {
				throw ex;
			}
			return new Read(project(project), ex.getMessage());
		}
	}

	private static PomModel project(XmlElement project) throws XmlParser.Malformed {
		XmlElement parent = project.child("parent");
		XmlElement management = project.child("dependencyManagement");
		XmlElement distribution = project.child("distributionManagement");
		XmlElement build = project.child("build");
		return new PomModel(leaf(project, "modelVersion"), parent == null ? null : parent(parent),
				leaf(project, "groupId"), leaf(project, "artifactId"), leaf(project, "version"),
				leaf(project, "packaging"), leaf(project, "name"), leaf(project, "description"),
				properties(project.child("properties")), dependencies(project.child("dependencies")),
				management == null ? null : management(management), profiles(project.child("profiles")),
				distribution == null ? null : relocation(distribution), modules(project.child("modules")),
				build == null ? null : build(build, true), ancillary(project));
	}

	/** A model's or a profile's repositories, distribution and reporting plugins. */
	private static PomModel.Ancillary ancillary(XmlElement base) throws XmlParser.Malformed {
		XmlElement distribution = base.child("distributionManagement");
		XmlElement reporting = base.child("reporting");
		List<PomModel.ReportPlugin> reportPlugins = null;
		if (reporting != null) {
			reportPlugins = new ArrayList<>();
			for (XmlElement plugin : items(reporting.child("plugins"), "plugin")) {
				String groupId = leaf(plugin, "groupId");
				reportPlugins.add(new PomModel.ReportPlugin(groupId == null ? "org.apache.maven.plugins" : groupId,
						leaf(plugin, "artifactId"), leaf(plugin, "inherited")));
			}
		}
		return new PomModel.Ancillary(repositories(base.child("repositories"), "repository"),
				repositories(base.child("pluginRepositories"), "pluginRepository"),
				distribution == null ? null
						: new PomModel.Distribution(leaf(distribution, "status"),
								repository(distribution.child("repository")),
								repository(distribution.child("snapshotRepository"))),
				reportPlugins);
	}

	private static List<PomModel.Repo> repositories(@Nullable XmlElement list, String itemName)
			throws XmlParser.Malformed {
		List<PomModel.Repo> result = new ArrayList<>();
		for (XmlElement repository : items(list, itemName)) {
			result.add(repository(repository));
		}
		return result;
	}

	private static PomModel.@Nullable Repo repository(@Nullable XmlElement repository) throws XmlParser.Malformed {
		return repository == null ? null
				: new PomModel.Repo(leaf(repository, "id"), leaf(repository, "url"), leaf(repository, "layout"));
	}

	private static PomModel.Parent parent(XmlElement parent) throws XmlParser.Malformed {
		return new PomModel.Parent(leaf(parent, "groupId"), leaf(parent, "artifactId"), leaf(parent, "version"),
				leaf(parent, "relativePath"));
	}

	private static List<PomModel.Dep> management(XmlElement management) throws XmlParser.Malformed {
		return dependencies(management.child("dependencies"));
	}

	private static List<PomModel.Dep> dependencies(@Nullable XmlElement dependencies) throws XmlParser.Malformed {
		List<PomModel.Dep> result = new ArrayList<>();
		for (XmlElement dependency : items(dependencies, "dependency")) {
			List<PomModel.Excl> exclusions = new ArrayList<>();
			for (XmlElement exclusion : items(dependency.child("exclusions"), "exclusion")) {
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
			XmlElement activation = profile.child("activation");
			XmlElement management = profile.child("dependencyManagement");
			XmlElement build = profile.child("build");
			String id = leaf(profile, "id");
			result.add(new PomModel.Profile(id == null ? "default" : id,
					activation == null ? null : activation(activation), properties(profile.child("properties")),
					dependencies(profile.child("dependencies")), management == null ? null : management(management),
					modules(profile.child("modules")), build == null ? null : build(build, false), ancillary(profile)));
		}
		return result;
	}

	/**
	 * A {@code build}, or a profile's: there the fields a {@code BuildBase} lacks are
	 * unknown elements, skipped.
	 */
	private static PomModel.Build build(XmlElement build, boolean full) throws XmlParser.Malformed {
		List<String> filters = new ArrayList<>();
		for (XmlElement filter : items(build.child("filters"), "filter")) {
			filters.add(value(filter));
		}
		XmlElement management = build.child("pluginManagement");
		List<PomModel.Plugin> managed = null;
		if (management != null) {
			managed = plugins(management.child("plugins"));
		}
		return new PomModel.Build(full ? leaf(build, "sourceDirectory") : null,
				full ? leaf(build, "scriptSourceDirectory") : null, full ? leaf(build, "testSourceDirectory") : null,
				full ? leaf(build, "outputDirectory") : null, full ? leaf(build, "testOutputDirectory") : null,
				leaf(build, "directory"), leaf(build, "finalName"), leaf(build, "defaultGoal"),
				resources(build.child("resources"), "resource"),
				resources(build.child("testResources"), "testResource"), filters, plugins(build.child("plugins")),
				managed);
	}

	private static List<PomModel.Resource> resources(@Nullable XmlElement resources, String itemName)
			throws XmlParser.Malformed {
		List<PomModel.Resource> result = new ArrayList<>();
		for (XmlElement resource : items(resources, itemName)) {
			result.add(new PomModel.Resource(leaf(resource, "directory"), leaf(resource, "filtering")));
		}
		return result;
	}

	private static List<PomModel.Plugin> plugins(@Nullable XmlElement plugins) throws XmlParser.Malformed {
		List<PomModel.Plugin> result = new ArrayList<>();
		for (XmlElement plugin : items(plugins, "plugin")) {
			List<PomModel.Execution> executions = new ArrayList<>();
			for (XmlElement execution : items(plugin.child("executions"), "execution")) {
				List<String> goals = new ArrayList<>();
				for (XmlElement goal : items(execution.child("goals"), "goal")) {
					goals.add(value(goal));
				}
				String id = leaf(execution, "id");
				executions.add(new PomModel.Execution(id == null ? "default" : id, leaf(execution, "phase"), goals,
						leaf(execution, "inherited"), configuration(execution)));
			}
			String groupId = leaf(plugin, "groupId");
			result.add(new PomModel.Plugin(groupId == null ? "org.apache.maven.plugins" : groupId,
					leaf(plugin, "artifactId"), leaf(plugin, "version"), leaf(plugin, "inherited"),
					configuration(plugin), executions, leaf(plugin, "extensions"),
					dependencies(plugin.child("dependencies"))));
		}
		return result;
	}

	/** A {@code configuration} element, every element below it kept. */
	private static @Nullable ConfigurationNode configuration(XmlElement container) {
		XmlElement configuration = container.child("configuration");
		return configuration == null ? null : ConfigurationNode.of(configuration);
	}

	private static PomModel.Activation activation(XmlElement activation) throws XmlParser.Malformed {
		XmlElement os = activation.child("os");
		XmlElement property = activation.child("property");
		XmlElement file = activation.child("file");
		PomModel.Os osCondition = null;
		if (os != null) {
			osCondition = new PomModel.Os(leaf(os, "name"), leaf(os, "family"), leaf(os, "arch"), leaf(os, "version"));
		}
		PomModel.Property propertyCondition = null;
		if (property != null) {
			propertyCondition = new PomModel.Property(leaf(property, "name"), leaf(property, "value"));
		}
		PomModel.FileCheck fileCondition = null;
		if (file != null) {
			fileCondition = new PomModel.FileCheck(leaf(file, "exists"), leaf(file, "missing"));
		}
		return new PomModel.Activation(leaf(activation, "activeByDefault"), leaf(activation, "jdk"), osCondition,
				propertyCondition, fileCondition);
	}

	private static PomModel.@Nullable Relocation relocation(XmlElement distribution) throws XmlParser.Malformed {
		XmlElement relocation = distribution.child("relocation");
		if (relocation == null) {
			return null;
		}
		return new PomModel.Relocation(leaf(relocation, "groupId"), leaf(relocation, "artifactId"),
				leaf(relocation, "version"), leaf(relocation, "message"));
	}

	private static Map<String, String> properties(@Nullable XmlElement properties) throws XmlParser.Malformed {
		Map<String, String> result = new LinkedHashMap<>();
		if (properties == null) {
			return result;
		}
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
	 * Checks a structure element of a {@code maven-metadata.xml}: no text between its
	 * elements, and none of its known fields twice.
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
		PomSchema.value(element);
		return element.text().trim();
	}

}
