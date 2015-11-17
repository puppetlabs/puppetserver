package com.puppetlabs.puppetserver;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A LinkedBlockingDeque that assumes the structure will be used as a Pool,
 * where elements are recycled over time.  Adds a <tt>registerLast</tt> method
 * that can be used to register new elements when they are first introduced to
 * the Pool, and a <tt>getRegisteredElements</tt> method that can be used to
 * get a set of all of the known elements, regardless of whether they are
 * currently available in the pool or not.
 *
 * @param <E> the type of element that can be added to the queue.
 */
public class RegisteredLinkedBlockingDeque<E> extends LinkedBlockingDeque<E> {
    private final Set<E> registeredElements = new CopyOnWriteArraySet<>();

    public RegisteredLinkedBlockingDeque(int capacity) {
        super(capacity);
    }

    /**
     * This method is analagous to <tt>putLast</tt> in the parent class, but
     * also causes the element to be added to the list of "registered" elements
     * that will be returned by <tt>getRegisteredInstances</tt>.
     *
     * Note that this method is synchronized to try to ensure that the addition
     * to the queue and the list of registered instances are visible roughly
     * atomically to consumers, but because the underlying queue uses
     * its own lock, it is possible for it to be modified on another thread while
     * this method is being executed.
     *
     * @param e the element to register and put at the end of the queue.
     */
    synchronized public void registerLast(E e) throws InterruptedException {
        registeredElements.add(e);
        putLast(e);
    }

    /**
     * This method removes an element from the list of "registered" elements,
     * such that it will no longer be returned by calls to
     * <tt>getRegisteredInstances</tt>.
     *
     * This method does not remove the element from the underlying queue; it
     * is assumed that the caller has already done so via the methods of the
     * parent class.
     *
     * @param e the element to remove from the list of registered instances.
     */
    synchronized public void unregister(E e) throws InterruptedException {
        registeredElements.remove(e);
    }

    /**
     * @return a set of all of the known elements that have been registered with
     *         this queue.
     */
    public Set<E> getRegisteredElements() {
        return registeredElements;
    }
}
