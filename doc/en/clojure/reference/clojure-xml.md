# clojure.xml

Reading an XML document into element maps and writing them back. Require `clojure.xml` to use
it; it is Clojure source written for rontolisp from the documented behavior of Clojure's
namespace. `parse` reads a document itself, the same on every backend; the host's SAX parser
reads it only when the program asks for one.

| Var | Behavior |
|---|---|
| `parse` | `(parse s)`: the root element of the document `s`, a File, an InputStream or a string naming a file or a URL. An element is a map of `:tag` (a keyword), `:attrs` (keywords to strings, `nil` without attributes) and `:content` (a vector of elements and strings, `nil` when empty) |
| `parse` | `(parse s startparse)`: the host's SAX parser reads it: `startparse` is called with `s` and a host `ContentHandler` (interpreter and JVM); `(parse s)` of a host object such as a host `InputStream` takes `startparse-sax-safe` |
| `tag`, `attrs`, `content` | `(tag e)`: the element's `:tag`, `:attrs`, `:content` |
| `element` | The struct of an element's three keys |
| `emit` | `(emit e)`: prints the element as an XML document, after its declaration |
| `emit-element` | `(emit-element e)`: prints the element, each tag and string on a line of its own |
| `startparse-sax`, `startparse-sax-safe` | The host's SAX parser as a `startparse`; the safe one reads no external entity (interpreter and JVM) |
| `sax-parser`, `disable-external-entities` | A new host `SAXParser`; the parser set to read no external DTD or entity (interpreter and JVM) |

```clojure
(require '[clojure.xml :as xml])
(xml/emit-element {:tag :a :attrs {:x "1"} :content ["t" {:tag :b}]})
```

```
<a x='1'>
t
<b/>
</a>
```

```console
clojure> (spit "/tmp/note.xml" "<note to='Tove'><body>Hi &amp; bye</body><empty/></note>")
nil
clojure> (xml/parse "/tmp/note.xml")
{:tag :note, :attrs {:to "Tove"}, :content [{:tag :body, :attrs nil, :content ["Hi & bye"]} {:tag :empty, :attrs nil, :content nil}]}
```

## Reading a document

The reader is a non-validating XML 1.0 one. The document is decoded from its byte order mark
or its declaration's encoding: UTF-8 (the default), UTF-16, ISO-8859-1, US-ASCII or
windows-1252. Line ends read as one newline; the general entities the internal subset declares
are expanded, markup included; no external DTD or entity is read. Character data between two
tags joins its text, CDATA sections, references and the text around comments and processing
instructions, and is dropped when it is all white space (Java's `Character.isWhitespace`). A
document that is not well-formed is an `org.xml.sax.SAXParseException` whose message is
Clojure's parser's for the common mistakes.

## Not built in

`content-handler` and the vars `*stack*`, `*current*`, `*state*` and `*sb*` are refused by
name: `parse` keeps its state in the call, and hands a `startparse` a `ContentHandler` made for
it.

## Differences

- Another encoding is a `java.io.UnsupportedEncodingException`, where Clojure's parser reads
  every charset of the JDK.
- Attribute defaults an `<!ATTLIST>` declares are not applied.
- A string naming no file is a `java.io.FileNotFoundException` naming the path as given, where
  Clojure's is a `java.net.MalformedURLException` for one that is no URL, and names a missing
  file by its absolute path.
- A rarer mistake is still a `SAXParseException`, with a message of rontolisp's own.
- On WebAssembly, `parse` with a `startparse` and the host parser functions fail when called,
  like any Java interop.
