package am.ik.maven;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The 4.0.0 POM's element structure, and the checks Maven 3.9's generated reader
 * ({@code MavenXpp3Reader}, maven-model 3.9.16) makes while it reads a POM, in either of
 * its modes, over a parsed tree:
 * <ul>
 * <li>both modes: a known field written twice ({@code Duplicated tag}), an element inside
 * a value, non-whitespace text between the items of a list or a {@code properties}
 * map;</li>
 * <li>strict only: a root other than {@code project}, an element no field of its parent
 * names ({@code Unrecognised tag}), an attribute its element does not declare (a prefixed
 * one, such as {@code xmlns:xsi}, is ignored), non-whitespace text between the fields of
 * a structure.</li>
 * </ul>
 * The lenient mode skips an unknown element whole and reads text between a structure's
 * fields as nothing (its {@code nextTag} steps over one text event). A list's own
 * attributes and a value's are never looked at; a configuration ({@code configuration},
 * {@code goals} of a plugin, {@code reports}) is a free-form tree. The first problem in
 * document order is the one reported, as the reader stops at it.
 */
final class PomSchema {

	/** What a field holds. */
	private enum Kind {

		/** A text value. */
		LEAF,

		/** A structure of the named type. */
		STRUCT,

		/** A list of items of one name: structures of the named type, or values. */
		LIST,

		/** Any elements, each a text value (Maven's properties maps). */
		PROPERTIES,

		/** A free-form tree. */
		DOM

	}

	/**
	 * A field.
	 *
	 * @param name the canonical name, the one a duplicate is reported by
	 * @param kind what it holds
	 * @param type the structure type of a {@code STRUCT}, or of a list's items
	 * ({@code null} for a list of values)
	 * @param item a list's item element name
	 */
	private record Field(String name, Kind kind, @Nullable String type, @Nullable String item) {
	}

	/**
	 * A structure type.
	 *
	 * @param fields its fields by element name, an alias included
	 * @param attributes the unprefixed attributes it declares
	 */
	private record Type(Map<String, Field> fields, Set<String> attributes) {
	}

	/**
	 * The types, each {@code Name: field field ...}: {@code f} a value, {@code f:T} a
	 * structure of type T, {@code f[i:T]} a list of {@code i} items of type T,
	 * {@code f[i]} a list of values, {@code f{}} a properties map, {@code f*} a free-form
	 * tree, {@code f|g} a field with an alias, {@code @a} an attribute. Read off the
	 * generated reader's {@code parse*} methods.
	 */
	private static final List<String> SPEC = List.of(
			"Model: @xmlns @child.project.url.inherit.append.path modelVersion parent:Parent groupId artifactId version"
					+ " packaging name description url inceptionYear organization|organisation:Organization"
					+ " licenses[license:License] developers[developer:Developer]"
					+ " contributors[contributor:Contributor] mailingLists[mailingList:MailingList]"
					+ " prerequisites:Prerequisites modules[module] scm:Scm issueManagement:IssueManagement"
					+ " ciManagement:CiManagement distributionManagement:DistributionManagement properties{}"
					+ " dependencyManagement:DependencyManagement dependencies[dependency:Dependency]"
					+ " repositories[repository:Repository] pluginRepositories[pluginRepository:Repository]"
					+ " build:Build reports* reporting:Reporting profiles[profile:Profile]",
			"Parent: groupId artifactId version relativePath", "Organization: name url",
			"License: name url distribution comments",
			"Developer: id name email url organization|organisation organizationUrl|organisationUrl roles[role]"
					+ " timezone properties{}",
			"Contributor: name email url organization|organisation organizationUrl|organisationUrl roles[role]"
					+ " timezone properties{}",
			"MailingList: name subscribe unsubscribe post archive otherArchives[otherArchive]", "Prerequisites: maven",
			"Scm: @child.scm.connection.inherit.append.path @child.scm.developerConnection.inherit.append.path"
					+ " @child.scm.url.inherit.append.path connection developerConnection tag url",
			"IssueManagement: system url", "CiManagement: system url notifiers[notifier:Notifier]",
			"Notifier: type sendOnError sendOnFailure sendOnSuccess sendOnWarning address configuration{}",
			"DistributionManagement: repository:DeploymentRepository snapshotRepository:DeploymentRepository"
					+ " site:Site downloadUrl relocation:Relocation status",
			"DeploymentRepository: uniqueVersion releases:RepositoryPolicy snapshots:RepositoryPolicy id name url"
					+ " layout",
			"Site: @child.site.url.inherit.append.path id name url", "Relocation: groupId artifactId version message",
			"DependencyManagement: dependencies[dependency:Dependency]",
			"Dependency: groupId artifactId version type classifier scope systemPath exclusions[exclusion:Exclusion]"
					+ " optional",
			"Exclusion: groupId artifactId",
			"Repository: releases:RepositoryPolicy snapshots:RepositoryPolicy id name url layout",
			"RepositoryPolicy: enabled updatePolicy checksumPolicy",
			"Build: sourceDirectory scriptSourceDirectory testSourceDirectory outputDirectory testOutputDirectory"
					+ " extensions[extension:Extension] defaultGoal resources[resource:Resource]"
					+ " testResources[testResource:Resource] directory finalName filters[filter]"
					+ " pluginManagement:PluginManagement plugins[plugin:Plugin]",
			"BuildBase: defaultGoal resources[resource:Resource] testResources[testResource:Resource] directory"
					+ " finalName filters[filter] pluginManagement:PluginManagement plugins[plugin:Plugin]",
			"Extension: groupId artifactId version",
			"Resource: targetPath filtering directory includes[include] excludes[exclude]",
			"PluginManagement: plugins[plugin:Plugin]",
			"Plugin: groupId artifactId version extensions executions[execution:PluginExecution]"
					+ " dependencies[dependency:Dependency] goals* inherited configuration*",
			"PluginExecution: id phase goals[goal] inherited configuration*",
			"Reporting: excludeDefaults outputDirectory plugins[plugin:ReportPlugin]",
			"ReportPlugin: groupId artifactId version reportSets[reportSet:ReportSet] inherited configuration*",
			"ReportSet: id reports[report] inherited configuration*",
			"Profile: id activation:Activation build:BuildBase modules[module]"
					+ " distributionManagement:DistributionManagement properties{}"
					+ " dependencyManagement:DependencyManagement dependencies[dependency:Dependency]"
					+ " repositories[repository:Repository] pluginRepositories[pluginRepository:Repository] reports*"
					+ " reporting:Reporting",
			"Activation: activeByDefault jdk os:ActivationOS property:ActivationProperty file:ActivationFile",
			"ActivationOS: name family arch version", "ActivationProperty: name value",
			"ActivationFile: missing exists");

	private static final Map<String, Type> TYPES = types();

	private PomSchema() {
	}

	private static Map<String, Type> types() {
		Map<String, Type> types = new HashMap<>();
		for (String spec : SPEC) {
			int colon = spec.indexOf(':');
			Map<String, Field> fields = new HashMap<>();
			Set<String> attributes = new HashSet<>();
			for (String word : spec.substring(colon + 1).trim().split(" ")) {
				if (word.startsWith("@")) {
					attributes.add(word.substring(1));
					continue;
				}
				Field field = field(word);
				int bar = word.indexOf('|');
				fields.put(field.name(), field);
				if (bar > 0) {
					String rest = word.substring(bar + 1);
					fields.put(rest.split("[:\\[{*]")[0], field);
				}
			}
			types.put(spec.substring(0, colon), new Type(fields, attributes));
		}
		return types;
	}

	private static Field field(String word) {
		String name = word.split("[|:\\[{*]")[0];
		int bracket = word.indexOf('[');
		if (bracket > 0) {
			String inside = word.substring(bracket + 1, word.length() - 1);
			int colon = inside.indexOf(':');
			return colon < 0 ? new Field(name, Kind.LIST, null, inside)
					: new Field(name, Kind.LIST, inside.substring(colon + 1), inside.substring(0, colon));
		}
		if (word.endsWith("{}")) {
			return new Field(name, Kind.PROPERTIES, null, null);
		}
		if (word.endsWith("*")) {
			return new Field(name, Kind.DOM, null, null);
		}
		int colon = word.lastIndexOf(':');
		return colon < 0 ? new Field(name, Kind.LEAF, null, null)
				: new Field(name, Kind.STRUCT, word.substring(colon + 1), null);
	}

	/**
	 * Checks a POM's tree as the reader reads it.
	 * @param project the root element
	 * @param strict the reader's strict mode
	 * @throws XmlParser.Malformed at the first problem, with the reader's message
	 */
	static void check(XmlElement project, boolean strict) throws XmlParser.Malformed {
		if (strict && !"project".equals(project.name())) {
			throw new XmlParser.Malformed(
					"Expected root element 'project' but found '" + project.name() + "' (line " + project.line() + ")");
		}
		structure(project, type("Model"), strict);
	}

	private static Type type(@Nullable String name) {
		Type type = TYPES.get(name);
		if (type == null) {
			throw new IllegalStateException("no POM type " + name);
		}
		return type;
	}

	private static void structure(XmlElement element, Type type, boolean strict) throws XmlParser.Malformed {
		if (strict) {
			// the reader walks the attributes last to first
			List<String> names = List.copyOf(element.attributes().keySet());
			for (int i = names.size() - 1; i >= 0; i--) {
				String attribute = names.get(i);
				if (attribute.indexOf(':') < 0 && !type.attributes().contains(attribute)) {
					throw new XmlParser.Malformed("Unknown attribute '" + attribute + "' for tag '" + element.name()
							+ "' (line " + element.line() + ")");
				}
			}
		}
		Set<String> parsed = new HashSet<>();
		List<XmlElement> children = element.children();
		for (int i = 0; i < children.size(); i++) {
			if (strict) {
				text(element, i);
			}
			XmlElement child = children.get(i);
			Field field = type.fields().get(child.name());
			if (field == null) {
				if (strict) {
					throw new XmlParser.Malformed(
							"Unrecognised tag: '" + child.name() + "' (line " + child.line() + ")");
				}
				continue;
			}
			if (!parsed.add(field.name())) {
				throw new XmlParser.Malformed("Duplicated tag: '" + field.name() + "' (line " + child.line() + ")");
			}
			switch (field.kind()) {
				case LEAF -> value(child);
				case STRUCT -> structure(child, type(field.type()), strict);
				case LIST -> list(child, field, strict);
				case PROPERTIES -> properties(child);
				case DOM -> {
				}
			}
		}
		if (strict) {
			text(element, children.size());
		}
	}

	private static void list(XmlElement list, Field field, boolean strict) throws XmlParser.Malformed {
		List<XmlElement> children = list.children();
		for (int i = 0; i < children.size(); i++) {
			text(list, i);
			XmlElement child = children.get(i);
			if (!child.name().equals(field.item())) {
				if (strict) {
					throw new XmlParser.Malformed(
							"Unrecognised tag: '" + child.name() + "' (line " + child.line() + ")");
				}
				continue;
			}
			if (field.type() == null) {
				value(child);
			}
			else {
				structure(child, type(field.type()), strict);
			}
		}
		text(list, children.size());
	}

	private static void properties(XmlElement properties) throws XmlParser.Malformed {
		List<XmlElement> children = properties.children();
		for (int i = 0; i < children.size(); i++) {
			text(properties, i);
			value(children.get(i));
		}
		text(properties, children.size());
	}

	/** The pull parser's {@code nextTag} before the given child: text is refused. */
	private static void text(XmlElement element, int child) throws XmlParser.Malformed {
		if (element.textAt() == child) {
			throw new XmlParser.Malformed("expected START_TAG or END_TAG not TEXT (in <" + element.name() + ">, line "
					+ element.line() + ")");
		}
	}

	/** The pull parser's {@code nextText}: an element inside is refused. */
	static void value(XmlElement element) throws XmlParser.Malformed {
		if (!element.children().isEmpty()) {
			throw new XmlParser.Malformed("parser must be on START_TAG or TEXT to read text (<" + element.name()
					+ "> holds <" + element.children().get(0).name() + ">, line " + element.line() + ")");
		}
	}

}
