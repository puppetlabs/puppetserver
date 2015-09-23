package com.puppetlabs.puppetserver.pool;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.puppetlabs.puppetserver.pool.LockablePool;

/**
 * A data structure to be used as a Pool, encapsulating a LinkedBlockingDeque
 * and a ReentrantReadWriteLock. Implements the <tt>LockablePool</tt>
 * interface such that when a <tt>borrowItem</tt> is performed an instance is
 * taken from the deque and a read lock is acquired, when a
 * <tt>returnItem</tt> is performed an instance is put back onto the deque and
 * a read lock is released, and <tt>lock</tt> locks a write lock.
 *
 * @param <E> the type of element that can be added to the LinkedBlockingDeque.
 */
public final class JRubyPool<E> implements LockablePool<E> {
    private final LinkedBlockingDeque<E> liveQueue;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Set<E> registeredElements = new CopyOnWriteArraySet<>();

    public JRubyPool(int size) {
        liveQueue = new LinkedBlockingDeque<>(size);
    }

    /**
    * This method is analagous to <tt>putLast</tt> in the
    * <tt>LinkedBlockingDeque</tt> class, but also causes the element to be
    * added to the list of "registered" elements that will be returned by
    * <tt>getRegisteredInstances</tt>.
    *
    * Note that this method is synchronized to try to ensure that the addition
    * to the queue and the list of registered instances are visible roughly
    * atomically to consumers, but because the underlying queue uses its own
    * lock, it is possible for it to be modified on another thread while this
    * method is being executed.
    *
    * @param e the element to register and put at the end of the queue.
    *
    * @throws InterruptedException
    */
    @Override
    synchronized public void register(E e) throws InterruptedException {
        liveQueue.putLast(e);
        registeredElements.add(e);
    }

    /**
    * This method is analagous to <tt>takeFirst</tt> in the
    * <tt>LinkedBlockingDeque</tt> class, but also causes a read lock to be
    * locked from the <tt>ReentrantReadWriteLock</tt>.
    *
    * @return The head of the deque.
    *
    * @throws IllegalStateException if the thread holding the write lock
    *         attempts to call this method.
    * @throws InterruptedException
    */
    @Override
    public E borrowItem() throws InterruptedException, IllegalStateException {
        if (lock.isWriteLockedByCurrentThread()) {
          throw new IllegalStateException("The current implementation has a risk of deadlock if you attempt to borrow a JRuby instance while holding the write lock!");
        }
        E item = liveQueue.takeFirst();
        lock.readLock().lock();
        return item;
    }

    /**
    * This method is analagous to <tt>pollFirst</tt> in the
    * <tt>LinkedBlockingDeque</tt> class, but also causes a read lock to be
    * locked from the <tt>ReentrantReadWriteLock</tt>.
    *
    * @param timeout how long to wait before giving up, in units of unit
    * @param unit a <tt>TimeUnit</tt> determining how to interpret the
    *        <tt>timeout parameter</tt>
    *
    * @return The head of the deque, or <tt>null</tt> if the specified waiting
    *         time elapses before an element is available.
    *
    * @throws IllegalStateException if the thread holding the write lock
    *         attempts to call this method.
    * @throws InterruptedException
    */
    @Override
    public E borrowItemWithTimeout(long timeout, TimeUnit unit) throws InterruptedException, IllegalStateException {
        if (lock.isWriteLockedByCurrentThread()) {
          throw new IllegalStateException("The current implementation has a risk of deadlock if you attempt to borrow a JRuby instance while holding the write lock!");
        }
        E item = liveQueue.pollFirst(timeout, unit);
        lock.readLock().lock();
        return item;
    }

    /**
    * This method is analagous to <tt>putFirst</tt> in the
    * <tt>LinkedBlockingDeque</tt> class, but also causes a read lock to be
    * unlocked from the <tt>ReentrantReadWriteLock</tt>.
    *
    * @param e the element to add to the queue
    *
    * @throws InterruptedException
    */
    @Override
    public void returnItem(E e) throws InterruptedException {
        try {
            liveQueue.putFirst(e);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
    * This method is analagous to <tt>putFirst</tt> in the
    * <tt>LinkedBlockingDeque</tt> class. It should only ever be used to
    * insert a `PoisonPill` or `RetryPoisonPill` to the pool, which is an
    * operation that should be done without acquiring a read lock.
    *
    * @param e the element to add to the queue
    *
    * @throws InterruptedException
    */
    @Override
    public void insertPill(E e) throws InterruptedException {
        liveQueue.putFirst(e);
    }

    /**
    * This method is analagous to <tt>clear</tt> in the
    * <tt>LinkedBlockingDeque</tt> class.
    *
    * @throws InterruptedException
    */
    @Override
    public void clear() {
        liveQueue.clear();
    }

    /**
    * This method is analagous to <tt>remainingCapacity</tt> in the
    * <tt>LinkedBlockingDeque</tt> class.
    *
    * @return the number of additional elements that the queue can ideally
    *         accept without blocking.
    */
    @Override
    public int remainingCapacity() {
        return liveQueue.remainingCapacity();
    }

    /**
    * This method is analagous to <tt>size</tt> in the
    * <tt>LinkedBlockingDeque</tt> class.
    *
    * @return the number of elements in the queue
    */
    @Override
    public int size() {
        return liveQueue.size();
    }

    /**
    * Acquires a write lock from <tt>ReentrantReadWriteLock</tt>.
    *
    * Note that this implementation does not fulfill requirement (e); with
    * this implementation, the thread holding the lock cannot perform a borrow
    * while it's holding the lock.
    */
    @Override
    public void lock() throws InterruptedException {
        lock.writeLock().lock();
    }

    /**
    * Releases the write lock.
    */
    @Override
    public void unlock() throws InterruptedException {
        lock.writeLock().unlock();
    }

    /**
     * @return a set of all of the known elements that have been registered with
     *         this pool.
     */
    public Set<E> getRegisteredElements() {
        return registeredElements;
    }

}
