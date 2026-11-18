// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

public interface TransferQueue<E> extends BlockingQueue<E> {

    boolean tryTransfer(E e);

    void transfer(E e) throws InterruptedException;

    boolean tryTransfer(E e, long timeout, TimeUnit unit) throws InterruptedException;

    boolean hasWaitingConsumer();

    int getWaitingConsumerCount();
}
