// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// An EnumSet of an enum of 64 constants or fewer.
final class RegularEnumSet<E extends Enum<E>> extends EnumSet<E> {

    RegularEnumSet(Class<E> elementType, E[] universe) {
        super(elementType, universe);
    }
}
