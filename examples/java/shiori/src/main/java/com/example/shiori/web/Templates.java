// Shiori: the templates, each parsed once at the start.
package com.example.shiori.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

// A page renders inside the layout, with the layout's values as the context around its
// own (Page); a piece renders alone for htmx (Fragment).
public final class Templates {

    private Templates() {
    }

    // A template is a resource beside this class, read by its name: a page names its
    // layout ({{<layout}}) and the pieces it shares ({{>spine}}). A compiled program carries
    // the files a name of this prefix and suffix may find, templates/*.mustache, and no
    // other (a served component has no file system to read them from).
    private static Reader template(String name) {
        InputStream text = Templates.class.getResourceAsStream("templates/" + name + ".mustache");
        if (text == null) {
            throw new IllegalArgumentException("no template " + name);
        }
        return new InputStreamReader(text, StandardCharsets.UTF_8);
    }

    // An empty string is no value, as null is; a null value prints as nothing.
    private static final Mustache.Compiler COMPILER = Mustache.compiler()
        .withLoader(Templates::template)
        .emptyStringIsFalse(true)
        .nullValue("");

    public static final Template HOME = compile("home");

    public static final Template LIBRARY = compile("library");

    public static final Template SHELF = compile("shelf");

    public static final Template BOOK = compile("book");

    public static final Template BOOK_FORM = compile("book-form");

    public static final Template ERROR = compile("error");

    public static final Template STARS = compile("stars");

    public static final Template PROGRESS = compile("progress");

    public static final Template NOTE = compile("note");

    private static Template compile(String name) {
        try (Reader reader = template(name)) {
            return COMPILER.compile(reader);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
