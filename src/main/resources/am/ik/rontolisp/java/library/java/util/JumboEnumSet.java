// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// An EnumSet of an enum of more than 64 constants.
final class JumboEnumSet<E extends Enum<E>> extends EnumSet<E> {

    JumboEnumSet(Class<E> elementType, E[] universe) {
        super(elementType, universe);
    }
}
