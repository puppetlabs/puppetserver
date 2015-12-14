package com.puppetlabs.puppetserver.pool;

import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An implementation of LockablePool for managing a pool of JRubyPuppet
 * instances.
 *
 * @param <E> the type of element that can be added to the pool.
 */
public final class JRubyPool<E> implements LockablePool<E> {
    // The `LockingPool` contract requires some synchronization behaviors that
    // are not natively present in any of the JDK deque implementations -
    // specifically to allow one calling thread to call lock() to supercede
    // and hold off any pending pool borrowers until unlock() is called.
    // This class implementation fulfills the contract by managing the
    // synchronization constructs directly rather than deferring to an
    // underlying JDK data structure to manage concurrent access.
    //
    // This implementation is modeled somewhat off of what the
    // `LinkedBlockingDeque` class in the OpenJDK does to manage
    // concurrency.  It uses a single `ReentrantLock` to provide mutual
    // exclusion around offer and take requests, with condition variables
    // used to park and later reawaken requests as needed, e.g., when pool
    // items are unavailable for borrowing or when the write lock is
    // unavailable.
    //
    // See http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/LinkedBlockingDeque.java#l157
    //
    // Because access to the underlying deque is synchronized within
    // this class, the pool is backed by a non-synchronized JDK `LinkedList`.

    // Underlying queue which holds the elements that clients can borrow.
    private final LinkedList<E> liveQueue;

    // Lock which guards all accesses to the underlying queue and registered
    // element set.  Constructed as "nonfair" for performance, like the lock
    // that a `LinkedBlockingDeque` does.  Not clear that we need this
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
    private final Condition notWriteLocked = lock.newCondition();

    // Holds a reference to all of the elements that have been registered.
    // Newly registered elements are also added into the `liveQueue`.
    // Elements only exist in the `liveQueue` when not currently
    // borrowed whereas elements that have been registered (but not
    // yet unregistered) will be accessible via `registeredElements`
    // even while they are borrowed.
    private final Set<E> registeredElements = new CopyOnWriteArraySet<>();

    // Maximum size that the underlying queue can grow to.
    private int maxSize;

    // Thread which currently holds the write lock.  null indicates that
    // there is no current write lock holder.  Using the current Thread
    // object for tracking the lock owner is comparable to what the JDK's
    // `ReentrantLock` class does:
    //
    // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/locks/ReentrantLock.java#l164
    private Thread writeLockThread = null;

    /**
     * Create a JRubyPool
     *
     * @param size maximum capacity for the pool.
     */
    public JRubyPool(int size) {
        liveQueue = new LinkedList<>();
        maxSize = size;
    }

    @Override
    public void register(E e) {
        final ReentrantLock lock = this.lock;
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

    @Override
    public void unregister(E e) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            registeredElements.remove(e);
            signalIfAllRegisteredInQueue();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E borrowItem() throws InterruptedException {
        E item = null;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Thread currentThread = Thread.currentThread();
            while (item == null) {
                if (isWriteLockHeldByAnotherThread(currentThread)) {
                    notWriteLocked.await();
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

    @Override
    public E borrowItemWithTimeout(long timeout, TimeUnit unit) throws
            InterruptedException {
        E item = null;
        final ReentrantLock lock = this.lock;
        long remainingMaxTimeToWait = unit.toNanos(timeout);

        // `lock.lockInterruptibly()` is called here as opposed to just
        // `lock.lock` to follow the pattern that the JDK's
        // `LinkedBlockingDeque` does for a timed poll from a deque.  See:
        // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/LinkedBlockingDeque.java#l516
        lock.lockInterruptibly();
        try {
            final Thread currentThread = Thread.currentThread();
            // This pattern of using timed `awaitNanos` on a condition
            // variable to track the total time spent waiting for an item to
            // be available to be borrowed follows the logic that the JDK's
            // `LinkedBlockingDeque` in `pollFirst` uses.  See:
            // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/LinkedBlockingDeque.java#l522
            while (item == null && remainingMaxTimeToWait > 0) {
                if (isWriteLockHeldByAnotherThread(currentThread)) {
                    remainingMaxTimeToWait =
                            notWriteLocked.awaitNanos(remainingMaxTimeToWait);
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

    @Override
    public void releaseItem(E e) {
        releaseItem(e, true);
    }

    @Override
    public void releaseItem(E e, boolean returnToPool) {
        final ReentrantLock lock = this.lock;
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
     * Insert a poison pill into the pool.  It should only ever be used to
     * insert a `PoisonPill` or `RetryPoisonPill` to the pool.
     */
    @Override
    public void insertPill(E e) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            addFirst(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        final ReentrantLock lock = this.lock;
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

    @Override
    public int remainingCapacity() {
        int remainingCapacity;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            remainingCapacity = maxSize - liveQueue.size();
        } finally {
            lock.unlock();
        }
        return remainingCapacity;
    }

    @Override
    public int size() {
        int size;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            size = liveQueue.size();
        } finally {
            lock.unlock();
        }
        return size;
    }

    @Override
    public void lock() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Thread currentThread = Thread.currentThread();
            while (!isWriteLockHeldByCurrentThread(currentThread)) {
                if (!isWriteLockHeld()) {
                    writeLockThread = currentThread;
                } else {
                    notWriteLocked.await();
                }
            }
            try {
                while (registeredElements.size() != liveQueue.size()) {
                    allRegisteredInQueue.await();
                }
            } catch (Exception e) {
                freeWriteLock();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void unlock() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Thread currentThread = Thread.currentThread();
            if (!isWriteLockHeldByCurrentThread(currentThread)) {
                String lockErrorMessage;
                if (isWriteLockHeldByAnotherThread(currentThread)) {
                    lockErrorMessage = "held by " + writeLockThread;
                } else {
                    lockErrorMessage = "not held by any thread";
                }
                throw new IllegalStateException(
                        "Unlock requested from thread not holding the lock.  " +
                        "Requested from " +
                        currentThread +
                        " but lock " +
                        lockErrorMessage +
                        ".");
            }
            freeWriteLock();
        } finally {
            lock.unlock();
        }
    }

    public Set<E> getRegisteredElements() {
        return registeredElements;
    }

    private void addFirst(E e) {
        liveQueue.addFirst(e);
        signalNotEmpty();
    }

    private void freeWriteLock() {
        writeLockThread = null;
        // Need to use 'signalAll' here because there might be multiple
        // waiters (e.g., multiple borrowers) queued up, waiting for the
        // pool to be unlocked.
        notWriteLocked.signalAll();
        // Borrowers that are woken up when an instance is returned to the
        // pool and the write lock is held would then start waiting on a
        // 'notWriteLocked' signal instead.  Re-signalling 'notEmpty' here
        // allows any borrowers still waiting on the 'notEmpty' signal to be
        // reawoken when the write lock is released, compensating for any
        // 'notEmpty' signals that might have been essentially ignored from
        // when the write lock was held.
        if (liveQueue.size() > 0) {
            notEmpty.signalAll();
        }
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

    private boolean isWriteLockHeld() {
        return writeLockThread != null;
    }

    private boolean isWriteLockHeldByCurrentThread(Thread currentThread) {
        return writeLockThread == currentThread;
    }

    private boolean isWriteLockHeldByAnotherThread(Thread currentThread) {
        return (writeLockThread != null) && (writeLockThread != currentThread);
    }
}
