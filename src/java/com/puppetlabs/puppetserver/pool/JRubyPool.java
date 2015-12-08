package com.puppetlabs.puppetserver.pool;

import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A data structure to be used as a Pool, encapsulating a ReentrantLock around
 * a LinkedList.
 *
 * @param <E> the type of element that can be added to the LinkedList.
 */
public final class JRubyPool<E> implements LockablePool<E> {
    // Underlying queue which holds the elements that clients can borrow.
    private final LinkedList<E> liveQueue;

    // Lock which guards all accesses to the underlying queue and registered
    // element set.  Constructed as "nonfair" for performance, like the lock
    // that a <tt>LinkedBlockingDeque</tt> does.  Not clear that we need this
    // to be a "fair" lock.
    private final ReentrantLock lock = new ReentrantLock();

    // Condition signaled when all elements that have been registered have been
    // returned to the queue.  Awaited when a lock has been requested but
    // one or more registered elements has been borrowed from the pool.
    private final Condition allRegisteredInQueue = lock.newCondition();

    // Condition signaled when an element has been added into the queue.
    // Awaited when a request has been made to borrow an item but no elements
    // currently exist in the queue.
    private final Condition notEmpty = lock.newCondition();

    // Condition signaled when the pool has been unlocked.  Awaited when a
    // request has been made to borrow an item or lock the pool but the pool
    // is currently locked.
    private final Condition notLocked = lock.newCondition();

    // Holds a reference to all of the elements that have been registered.
    // Newly registered elements are also added into the <tt>liveQueue</tt>.
    // Elements only exist in the <tt>liveQueue</tt> when not currently
    // borrowed whereas elements that have been registered (but not
    // yet unregistered) will be accessible via <tt>registeredElements</tt>
    // even while they are borrowed.
    private final Set<E> registeredElements = new CopyOnWriteArraySet<>();

    // Maximum size that the underlying queue can grow to.
    private int maxSize;

    // Id of the thread which currenly holds the write lock.  -1 indicates that
    // there is no current write lock holder.
    private volatile long lockThreadId = -1;

    /**
     * Create a JRubyPool
     *
     * @param size maximum capacity for the pool.
     */
    public JRubyPool(int size) {
        liveQueue = new LinkedList<>();
        maxSize = size;
    }

    /**
     * This method is analogous to <tt>addLast</tt> in the <tt>LinkedList</tt>
     * class, but also causes the element to be added to the list of
     * "registered" elements that will be returned by
     * <tt>getRegisteredInstances</tt>.
     *
     * @param e the element to register and put at the end of the queue.
     * @throws IllegalStateException if an attempt is made to register an
     *                               element but the number of registered
     *                               instances is already equal to the maximum
     *                               capacity for the pool.
     */
    @Override
    public void register(E e) {
        lock.lock();
        try {
            if (registeredElements.size() == maxSize)
                throw new IllegalStateException(
                        "Unable to register additional instance, pool full");
            registeredElements.add(e);
            liveQueue.addLast(e);
            signalNotEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * This method removes an element from the list of "registered" elements,
     * such that it will no longer be returned by calls to
     * <tt>getRegisteredInstances</tt>.
     * <p>
     * This method does not remove the element from the underlying queue; it
     * is assumed that the caller has already done so via the methods of the
     * parent class.
     *
     * @param e the element to remove from the list of registered instances.
     */
    @Override
    public void unregister(E e) {
        lock.lock();
        try {
            registeredElements.remove(e);
            signalIfAllRegisteredInQueue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * This method is analogous to <tt>removeFirst</tt> in the
     * <tt>LinkedList</tt> class.  An element will not be returned until
     * the queue is unlocked and an element can be pulled out of the queue
     * for return.
     *
     * @return The head of the queue.
     * @throws InterruptedException if the calling thread is interrupted
     *                              while waiting for the pool to be
     *                              unlocked or for an element to
     *                              be available in the queue for borrowing.
     */
    @Override
    public E borrowItem() throws InterruptedException {
        E item = null;

        lock.lock();
        try {
            long currentThreadId = getThreadId(Thread.currentThread());
            while (item == null) {
                if (lockThreadId != -1 && lockThreadId != currentThreadId) {
                    notLocked.await();
                } else if (liveQueue.size() < 1) {
                    notEmpty.await();
                } else {
                    item = liveQueue.removeFirst();
                }
            }
        } finally {
            lock.unlock();
        }

        return item;
    }

    /**
     * This method is analogous to <tt>removeFirst</tt> in the
     * <tt>LinkedList</tt> class but with a timed maximum wait for an element
     * to be available in the queue for borrowing.
     *
     * @param timeout how long to wait before giving up, in units of unit
     * @param unit    a <tt>TimeUnit</tt> determining how to interpret the
     *                <tt>timeout parameter</tt>
     * @return The head of the queue, or <tt>null</tt> if the specified waiting
     *         time elapses before an element is available.
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for the pool to be unlocked or for
     *                              an element to be available in the queue
     *                              for borrowing.
     */
    @Override
    public E borrowItemWithTimeout(long timeout, TimeUnit unit) throws
            InterruptedException {
        E item = null;

        long remainingMaxTimeToWait = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            long currentThreadId = getThreadId(Thread.currentThread());
            while (item == null && remainingMaxTimeToWait > 0) {
                if (lockThreadId != -1 && lockThreadId != currentThreadId) {
                    remainingMaxTimeToWait =
                            notLocked.awaitNanos(remainingMaxTimeToWait);
                } else if (liveQueue.size() < 1) {
                    remainingMaxTimeToWait =
                            notEmpty.awaitNanos(remainingMaxTimeToWait);
                } else {
                    item = liveQueue.removeFirst();
                }
            }
        } finally {
            lock.unlock();
        }

        return item;
    }

    /**
     * This method is analogous to <tt>addLast</tt> in the <tt>LinkedList</tt>
     * class.
     *
     * @param e the element to return to the pool
     */
    @Override
    public void releaseItem(E e) {
        releaseItem(e, true);
    }

    /**
     * This method is analogous to <tt>addFirst</tt> in the <tt>LinkedList</tt>
     * class.
     *
     * @param e            the element to return to the pool
     * @param returnToPool whether to return the element to the pool (true)
     *                     or just throw the element away (false)
     */
    @Override
    public void releaseItem(E e, boolean returnToPool) {
        lock.lock();
        try {
            if (returnToPool) {
                addFirst(e);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * This method is analogous to <tt>addFirst</tt> in the
     * <tt>LinkedList</tt> class.  It should only ever be used to
     * insert a `PoisonPill` or `RetryPoisonPill` to the pool.  The
     * implementation of this method is equivalent to calling
     * releaseItem(e, true).
     *
     * @param e the element to add to the queue
     */
    @Override
    public void insertPill(E e) {
        lock.lock();
        try {
            addFirst(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * This method is analogous to <tt>clear</tt> in the <tt>LinkedList</tt>
     * class.  This method clears all elements currently in the queue and
     * also unregisters them from the set of registered elements.
     */
    @Override
    public void clear() {
        lock.lock();
        try {
            int queueSize = liveQueue.size();
            for (int i=0; i<queueSize; i++) {
                registeredElements.remove(liveQueue.removeFirst());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * This method calculates the remaining capacity in the queue from
     * the supplied <tt>size</tt> at construction minus the current number of
     * elements in the underlying <tt>LinkedList</tt>.
     *
     * @return the number of additional elements that the queue can accept.
     */
    @Override
    public int remainingCapacity() {
        int remainingCapacity;
        lock.lock();
        try {
            remainingCapacity = maxSize - liveQueue.size();
        } finally {
            lock.unlock();
        }
        return remainingCapacity;
    }

    /**
     * This method is analogous to <tt>size</tt> in the <tt>LinkedList</tt>
     * class.
     *
     * @return the number of elements in the queue
     */
    @Override
    public int size() {
        int size;
        lock.lock();
        try {
            size = liveQueue.size();
        } finally {
            lock.unlock();
        }
        return size;
    }

    /**
     * Acquires a "lock" on the underlying queue, preventing any would-be
     * future borrowers from acquiring an element or any future pool lockers
     * until the lock is freed.
     *
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for the pool to be unlocked.
     */
    @Override
    public void lock() throws InterruptedException {
        lock.lock();
        try {
            long currentThreadId = getThreadId(Thread.currentThread());
            while (currentThreadId != lockThreadId) {
                if (lockThreadId == -1) {
                    lockThreadId = currentThreadId;
                } else {
                    notLocked.await();
                }
            }
            while (registeredElements.size() != liveQueue.size()) {
                allRegisteredInQueue.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases the write lock.
     *
     * @throws IllegalStateException if the calling thread does not currently
     *                               hold the write lock.
     */
    @Override
    public void unlock() throws InterruptedException {
        lock.lock();
        try {
            long currentThreadId = getThreadId(Thread.currentThread());
            if (currentThreadId != lockThreadId) {
                String threadIdForMessage;
                if (lockThreadId == -1) {
                    threadIdForMessage = "not held by any thread";
                } else {
                    threadIdForMessage = "held by thread id " + lockThreadId;
                }
                throw new IllegalStateException(
                        "Unlock requested from thread not holding the lock.  " +
                                "Requested from thread id " + currentThreadId +
                                " but lock " + threadIdForMessage);
            }
            lockThreadId = -1;
            // Need to use 'signalAll' here because there might be multiple
            // waiters (e.g., multiple borrowers) queued up, waiting for the
            // pool to be unlocked.
            notLocked.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return a set of all of the known elements that have been registered with
     *         this pool.
     */
    public Set<E> getRegisteredElements() {
        return registeredElements;
    }

    private void addFirst(E e) {
        liveQueue.addFirst(e);
        signalNotEmpty();
    }

    private void signalNotEmpty() {
        // Could use 'signalAll' here instead of 'signal' but 'signal' is
        // less expensive in that only one waiter will be woken up.  Can use
        // signal here because the thread being awoken will be able to borrow
        // a pool instance and any further waiters will be woken up by
        // subsequent posts of this signal when instances are added/returned to
        // the queue.
        notEmpty.signal();
        signalIfAllRegisteredInQueue();
    }

    private void signalIfAllRegisteredInQueue() {
        // Could use 'signalAll' here instead of 'signal'.  Doesn't really
        // matter though in that there will only be one waiter at most which
        // is active at a time - a caller of lock() that has just acquired
        // the lock but is waiting for all registered elements to be returned
        // to the queue.
        if (registeredElements.size() == liveQueue.size()) {
            allRegisteredInQueue.signal();
        }
    }

    /**
     * Returns the thread id for a given thread.  For safety, we might want /
     * need to replace this with the scary-looking method that the OpenJDK uses
     * in <tt>ReentrantReadWriteLock</tt> - see
     * http://hg.openjdk.java.net/jdk8/jdk8/jdk/rev/1919c226b427#l1.124.
     */
     private static long getThreadId(Thread thread) {
         return thread.getId();
     }
}
