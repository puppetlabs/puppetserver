package com.puppetlabs.puppetserver.pool;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public interface LockablePool<E> {

   /**
    * Introduce a new instance to the pool in a way that allows us to keep track
    * of the list of all existing instances, even if some of them are
    * borrowed.
    */
    void register(E e) throws InterruptedException;


    /**
     * Unregister an instance from the pool, removing it from the list of
     * existing instances.
     */
    void unregister(E e) throws InterruptedException;

   /**
    * Borrow an instance. This and <tt>borrowItemWithTimeout</tt> are the
    * only entry points for accessing instances.
    */
    E borrowItem() throws InterruptedException;

   /**
    * Borrow an instance, waiting up to the specified timeout if necessary
    * for an instance to become available. Returns `null` if the wait time
    * elapses before an instance is available.
    *
    * This and <tt>borrowItemWithTimeout</tt> are the only entry points for
    * accessing instances.
    */
    E borrowItemWithTimeout(long timout, TimeUnit unit) throws InterruptedException;

   /**
    * Return an instance to the pool.
    */
    void returnItem(E e) throws InterruptedException;

   /**
    * Insert a poison pill to the pool. This is different from returning an
    * instance to the pool because there are different locking semantics
    * around inserting a pill.
    */
    void insertPill(E e) throws InterruptedException;

    /**
     * Clear the pool.
     */
    void clear();

    /**
     * Returns the number of additional items that the pool can accept without
     * blocking. Equal to the initial capacity of the pool minus the current
     * <tt>size</tt> of the pool.
     */
    int remainingCapacity();

    /**
     * Returns the number of elements in the pool.
     */
    int size();

   /**
    * Lock the pool. This method should make the following guarantees:
    *
    *  a) blocks until all registered instances are returned to the pool
    *  b) once this method is called (even before it returns), any new threads
    *     that attempt a <tt>borrow</tt> should block until <tt>unlock()</tt>
    *     is called
    *  c) if there are other threads that were already blocking in a
    *     <tt>borrow</tt> before this method was called, they should continue
    *     to block until <tt>unlock()</tt> is called
    *  d) instances may be returned by other threads while this method is
    *     being executied
    *  e) when the method returns, the caller holds an exclusive lock on the
    *     pool; the lock should be re-entrant in the sense that this thread
    *     should still be able to perform borrows while it's holding the lock
    */
    void lock() throws InterruptedException;

   /**
    * Release the exclusive lock so that other threads may begin to perform
    * borrow operations again.
    */
    void unlock() throws InterruptedException;

   /**
    * Returns a set of all of the known elements that have been registered with
    * this pool, regardless of whether they are
    * currently available in the pool or not.
    */
    Set<E> getRegisteredElements();
}
