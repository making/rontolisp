package am.ik.maven;

/**
 * The dependency types Maven's repository session knows
 * ({@code MavenRepositorySystemUtils.newSession}): the extension and classifier a type
 * stands for, and whether an artifact of the type bundles its dependencies (so a graph
 * does not descend into it). Any other type is its own extension, with no classifier.
 */
final class ArtifactTypes {

	private ArtifactTypes() {
	}

	static String extension(String type) {
		return switch (type) {
			case "maven-plugin", "jar", "ejb", "ejb-client", "test-jar", "javadoc", "java-source" -> "jar";
			default -> type;
		};
	}

	static String classifier(String type) {
		return switch (type) {
			case "ejb-client" -> "client";
			case "test-jar" -> "tests";
			case "javadoc" -> "javadoc";
			case "java-source" -> "sources";
			default -> "";
		};
	}

	static boolean includesDependencies(String type) {
		return switch (type) {
			case "war", "ear", "rar", "par" -> true;
			default -> false;
		};
	}

}
