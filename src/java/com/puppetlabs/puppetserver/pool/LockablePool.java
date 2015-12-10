package com.puppetlabs.puppetserver.pool;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public interface LockablePool<E> {

   /**
    * Introduce a new element to the pool.
    *
    * @param e the element to register
    * @throws IllegalStateException if an attempt is made to register an
    *                               element but the number of registered
    *                               elements is already equal to the maximum
    *                               capacity for the pool
    */
    void register(E e) throws IllegalStateException;

    /**
     * Unregister an element from the pool.  It is assumed that the
     * caller of this method has previously called {@link #borrowItem()} to
     * retrieve the item back from the pool.  This method does not
     * implicitly remove the element from the list of elements available for
     * {@link #borrowItem()} calls.  This method does remove the element
     * from the set returned from subsequent calls to
     * {@link #getRegisteredElements()}.
     *
     * @param e the element to remove from the list of registered elements
     */
    void unregister(E e);

    /**
     * Borrow an element from the pool.  This method will block until
     * whichever of the following happens first:
     *
     * - the element can be returned
     * - the caller's thread is interrupted
     *
     * On a successful return, subsequent calls to {@link #borrowItem()} and
     * {@link #borrowItemWithTimeout(long, TimeUnit)} will not return the same
     * element returned for the call to this method until/unless a subsequent
     * call to {@link #releaseItem(Object)} is made to return the element back
     * to the pool.
     *
     * @return the borrowed element
     * @throws InterruptedException if the calling thread is interrupted
     *                              while waiting for the pool to be
     *                              unlocked or for an element to
     *                              be available in the queue for borrowing
     * @see #borrowItemWithTimeout(long, TimeUnit)
     */
    E borrowItem() throws InterruptedException;

    /**
     * Borrow an element from the pool.  This method will block until
     * whichever of the following happens first:
     *
     * - the element can be returned
     * - the maximum time specified by the <tt>timeout</tt> parameter has
     *   elapsed
     * - the caller's thread is interrupted
     *
     * On a successful return, subsequent calls to {@link #borrowItem()} and
     * {@link #borrowItemWithTimeout(long, TimeUnit)} will not return the same
     * element returned for the call to this method until/unless a subsequent
     * call to {@link #releaseItem(Object)} is made to return the element
     * back to the pool.
     *
     * @param timeout how long to wait before giving up, in units of unit
     * @param unit    a <tt>TimeUnit</tt> determining how to interpret the
     *                <tt>timeout</tt> parameter
     * @return The borrowed element or <tt>null</tt> if the specified waiting
     *         time elapses before an element is available
     * @throws InterruptedException if the calling thread is interrupted
     *                              while waiting for the pool to be
     *                              unlocked or for an element to
     *                              be available in the queue for borrowing
     * @see #borrowItem()
     */
    E borrowItemWithTimeout(long timeout, TimeUnit unit)
            throws InterruptedException;

   /**
    * Release an item back into the pool.
    *
    * @param e the element to return to the pool
    */
    void releaseItem(E e);

   /**
    * Release an item.
    *
    * @param e the element
    * @param returnToPool if <tt>true</tt>, return the item back to the
    *                     pool.  For a value of <tt>false</tt>, discard the
    *                     item.
    */
    void releaseItem(E e, boolean returnToPool);

   /**
    * Insert a poison pill into the pool.
    *
    * @param e the pill element to add to the queue
    */
    void insertPill(E e);

    /**
     * Unregister all elements which are currently in the pool.  Elements
     * in the pool at the time this method is called would no longer be
     * returned in subsequent {@link #borrowItem()},
     * {@link #borrowItemWithTimeout(long, TimeUnit)}, or
     * {@link #getRegisteredElements()} calls.  Note that any elements that
     * have been borrowed but not yet returned to the pool at the time this
     * method is called will remain registered.
     */
    void clear();

    /**
     * Returns the number of elements that can be added into the pool.  Equal
     * to the capacity of the pool minus the number of elements that have
     * been registered with but not borrowed from the pool.
     */
    int remainingCapacity();

    /**
     * Returns the number of elements that have been registered with but not
     * borrowed from the pool.
     */
    int size();

   /**
    * Lock the pool. This method should make the following guarantees:
    *
    *  a) blocks until all registered elements are returned to the pool
    *  b) once this method is called (even before it returns), any new threads
    *     that attempt a <tt>borrow</tt> should block until {@link #unlock()}
    *     is called
    *  c) if there are other threads that were already blocking in a
    *     <tt>borrow</tt> before this method was called, they should continue
    *     to block until {@link #unlock()} is called
    *  d) elements may be returned by other threads while this method is
    *     being executed
    *  e) when the method returns, the caller holds an exclusive lock on the
    *     pool; the lock should be re-entrant in the sense that this thread
    *     should still be able to perform borrows while it's holding the lock
    *
    * @throws InterruptedException if the calling thread is interrupted while
    *                              waiting for the pool to be unlocked
    */
    void lock() throws InterruptedException;

   /**
    * Release the exclusive pool lock so that other threads may begin to
    * perform borrow operations again.  Note that this method must be called
    * from the same thread from which {@link #lock} was called in order to
    * obtain the exclusive pool lock.
    *
    * @throws IllegalStateException if the calling thread does not currently
    *                               hold the exclusive lock
    */
    void unlock() throws InterruptedException;

   /**
    * Returns a set of all of the elements that are currently registered with
    * this pool.  The set includes both elements that are available to be
    * borrowed and elements that have already been borrowed.
    */
    Set<E> getRegisteredElements();
}
