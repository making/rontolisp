package am.ik.wit;

/**
 * One {@code .wit} file of a package read from its directory
 * ({@link WitPackageAssembler}).
 *
 * @param name the file's name as an error should print it (its path)
 * @param text the file's WIT text
 */
public record WitSourceFile(String name, String text) {
}
