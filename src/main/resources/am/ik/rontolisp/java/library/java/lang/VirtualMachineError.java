// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

public abstract class VirtualMachineError extends Error {

    public VirtualMachineError() {
    }

    public VirtualMachineError(String message) {
        super(message);
    }

    public VirtualMachineError(String message, Throwable cause) {
        super(message, cause);
    }

    public VirtualMachineError(Throwable cause) {
        super(cause);
    }
}
