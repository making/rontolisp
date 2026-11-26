// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// A frame as a program makes one: the frames of a thrown exception are not kept, so
// getStackTrace() answers none.
public final class StackTraceElement {

    private final String declaringClass;

    private final String methodName;

    private final String fileName;

    private final int lineNumber;

    public StackTraceElement(String declaringClass, String methodName, String fileName, int lineNumber) {
        if (declaringClass == null) {
            throw new NullPointerException("Declaring class is null");
        }
        if (methodName == null) {
            throw new NullPointerException("Method name is null");
        }
        this.declaringClass = declaringClass;
        this.methodName = methodName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    public String getClassName() {
        return declaringClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public boolean isNativeMethod() {
        return lineNumber == -2;
    }

    public String toString() {
        String place = lineNumber == -2 ? "Native Method"
                : fileName == null ? "Unknown Source" : lineNumber >= 0 ? fileName + ":" + lineNumber : fileName;
        return declaringClass + "." + methodName + "(" + place + ")";
    }

    public boolean equals(Object o) {
        return o instanceof StackTraceElement other && declaringClass.equals(other.declaringClass)
                && methodName.equals(other.methodName) && lineNumber == other.lineNumber
                && (fileName == null ? other.fileName == null : fileName.equals(other.fileName));
    }

    public int hashCode() {
        int hash = 31 * declaringClass.hashCode() + methodName.hashCode();
        hash = 31 * hash + (fileName == null ? 0 : fileName.hashCode());
        return 31 * hash + lineNumber;
    }
}
