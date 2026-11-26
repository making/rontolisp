// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

// The host's file system, the one there is: every path is a LocalPath of it, its one root
// is /, and it is never closed. A matcher's syntax is glob or regex in any case, matched
// against the whole text of a path. Its views are the basic one, and the owner, posix and
// unix ones where the host keeps permission bits (a JDK on a Unix host has dos and user
// too).
public final class LocalFileSystem extends FileSystem {

    public static final LocalFileSystem HOST = new LocalFileSystem();

    private LocalFileSystem() {
    }

    public Iterable<Path> getRootDirectories() {
        return List.of(Path.of("/"));
    }

    public boolean isOpen() {
        return true;
    }

    public boolean isReadOnly() {
        return false;
    }

    public String getSeparator() {
        return "/";
    }

    public Path getPath(String first, String... more) {
        return LocalPath.of(first, more);
    }

    public void close() {
        throw new UnsupportedOperationException();
    }

    public Set<String> supportedFileAttributeViews() {
        Set<String> views = new HashSet<>();
        views.add("basic");
        if (Disk.modes()) {
            views.add("owner");
            views.add("posix");
            views.add("unix");
        }
        return Collections.unmodifiableSet(views);
    }

    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int colon = syntaxAndPattern.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException();
        }
        String syntax = syntaxAndPattern.substring(0, colon);
        String pattern = syntaxAndPattern.substring(colon + 1);
        if (syntax.equalsIgnoreCase("glob")) {
            Glob glob = Glob.of(pattern);
            return path -> glob.matches(path.toString());
        }
        if (syntax.equalsIgnoreCase("regex")) {
            Pattern regex = Pattern.compile(pattern);
            return path -> regex.matcher(path.toString()).matches();
        }
        throw new UnsupportedOperationException("Syntax '" + syntax + "' not recognized");
    }
}
