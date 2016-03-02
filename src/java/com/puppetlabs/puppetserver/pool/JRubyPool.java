package com.puppetlabs.puppetserver.pool;

import java.util.Deque;
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
    // items are unavailable for borrowing or when the pool lock is
    // unavailable.
    //
    // See http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/LinkedBlockingDeque.java#l157
    //
    // Because access to the underlying deque is synchronized within
    // this class, the pool is backed by a non-synchronized JDK `LinkedList`.

    // Underlying queue which holds the elements that clients can borrow.
    private final Deque<E> liveQueue;

    // Lock which guards all accesses to the underlying queue and registered
    // element set.  Constructed as "nonfair" for performance, like the
    // lock that a `LinkedBlockingDeque` does.  Not clear that we need this
    // to be a "fair" lock.
    private final ReentrantLock queueLock = new ReentrantLock(false);

    // Condition signaled when all elements that have been registered have been
    // returned to the queue.  Awaited when a lock has been requested but
    // one or more registered elements has been borrowed from the pool.
    private final Condition allRegisteredInQueue = queueLock.newCondition();

    // Condition signaled when an element has been added into the queue.
    // Awaited when a request has been made to borrow an item but no elements
    // currently exist in the queue.
    private final Condition queueNotEmpty = queueLock.newCondition();

    // Condition signaled when the pool has been unlocked.  Awaited when a
    // request has been made to borrow an item or lock the pool but the pool
    // is currently locked.
    private final Condition poolNotLocked = queueLock.newCondition();

    // Holds a reference to all of the elements that have been registered.
    // Newly registered elements are also added into the `liveQueue`.
    // Elements only exist in the `liveQueue` when not currently
    // borrowed whereas elements that have been registered (but not
    // yet unregistered) will be accessible via `registeredElements`
    // even while they are borrowed.
    private final Set<E> registeredElements = new CopyOnWriteArraySet<>();

    // Maximum size that the underlying queue can grow to.
    private int maxSize;

    // Thread which currently holds the pool lock.  null indicates that
    // there is no current pool lock holder.  Using the current Thread
    // object for tracking the pool lock owner is comparable to what the JDK's
    // `ReentrantLock` class does via the `AbstractOwnableSynchronizer` class:
    //
    // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/locks/ReentrantLock.java#l164
    // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/locks/AbstractOwnableSynchronizer.java#l64
    //
    // Unlike the `AbstractOwnableSynchronizer` class implementation, we marked
    // this variable as `volatile` because we couldn't convince ourselves
    // that it would be safe to update this variable from different threads and
    // not be susceptible to per-thread / per-CPU caching causing the wrong
    // value to be seen by a thread.  `volatile` seems safer and doesn't appear
    // to impose any noticeable performance degradation.
    private volatile Thread poolLockThread = null;

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
        final ReentrantLock lock = this.queueLock;
        lock.lock();
        try {
            if (registeredElements.size() == maxSize)
                throw new IllegalStateException(
                        "Unable to register additional instance, pool full");
            registeredElements.add(e);
            liveQueue.addLast(e);
            signalPoolNotEmpty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void unregister(E e) {
        final ReentrantLock lock = this.queueLock;
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
        final ReentrantLock lock = this.queueLock;
        lock.lock();
        try {
            final Thread currentThread = Thread.currentThread();
            do {
                if (isPoolLockHeldByAnotherThread(currentThread)) {
                    poolNotLocked.await();
                } else if (liveQueue.size() < 1) {
                    queueNotEmpty.await();
                } else {
                    item = liveQueue.removeFirst();
                }
            } while (item == null);
        } finally {
            lock.unlock();
        }

        return item;
    }

    @Override
    public E borrowItemWithTimeout(long timeout, TimeUnit unit) throws
            InterruptedException {
        E item = null;
        final ReentrantLock lock = this.queueLock;
        long remainingMaxTimeToWait = unit.toNanos(timeout);

        // `queueLock.lockInterruptibly()` is called here as opposed to just
        // `queueLock.queueLock` to follow the pattern that the JDK's
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
            do {
                if (isPoolLockHeldByAnotherThread(currentThread)) {
                    if (remainingMaxTimeToWait <= 0) {
                        break;
                    }
                    remainingMaxTimeToWait =
                            poolNotLocked.awaitNanos(remainingMaxTimeToWait);
                } else if (liveQueue.size() < 1) {
                    if (remainingMaxTimeToWait <= 0) {
                        break;
                    }
                    remainingMaxTimeToWait =
                            queueNotEmpty.awaitNanos(remainingMaxTimeToWait);
                } else {
                    item = liveQueue.removeFirst();
                }
            } while (item == null);
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
        final ReentrantLock lock = this.queueLock;
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
        final ReentrantLock lock = this.queueLock;
        lock.lock();
        try {
            addFirst(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        final ReentrantLock lock = this.queueLock;
        lock.lock();
        try {
            // It would be simpler to just call .clear() on both the liveQueue
            // and registeredElements here.  It is possible, however, that this
            // method might be called while one or more elements are being
            // borrowed from the liveQueue.  If the associated element from
            // registeredElements were removed, it would then be possible for
            // the borrowed elements to be returned to the pool, making them
            // appear in liveQueue but not in registeredElements.  This would
            // be bad because any subsequent actions that need to be done to
            // all members of the pool - for example, marking environments in
            // the pool instance as expired - might inadvertently skip over
            // any of the elements that are no longer in registeredElements
            // but can appear in liveQueue.
            //
            // To avoid this problem, the implementation only removes elements
            // from registeredElements which have a corresponding entry which
            // is being removed from the liveQueue.
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
        final ReentrantLock lock = this.queueLock;
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
        final ReentrantLock lock = this.queueLock;
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
        final ReentrantLock lock = this.queueLock;
        lock.lock();
        try {
            final Thread currentThread = Thread.currentThread();
            while (!isPoolLockHeldByCurrentThread(currentThread)) {
                if (!isPoolLockHeld()) {
                    poolLockThread = currentThread;
                } else {
                    poolNotLocked.await();
                }
            }
            try {
                while (registeredElements.size() != liveQueue.size()) {
                    allRegisteredInQueue.await();
                }
            } catch (Exception e) {
                freePoolLock();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isLocked() {
        boolean locked;
        final ReentrantLock lock = this.queueLock;
        lock.lock();
        try {
            locked = isPoolLockHeld();
        } finally {
            lock.unlock();
        }
        return locked;
    }

    @Override
    public void unlock() {
        final ReentrantLock lock = this.queueLock;
        lock.lock();
        try {
            final Thread currentThread = Thread.currentThread();
            if (!isPoolLockHeldByCurrentThread(currentThread)) {
                String lockErrorMessage;
                if (isPoolLockHeldByAnotherThread(currentThread)) {
                    lockErrorMessage = "held by " + poolLockThread;
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
            freePoolLock();
        } finally {
            lock.unlock();
        }
    }

    public Set<E> getRegisteredElements() {
        return registeredElements;
    }

    private void addFirst(E e) {
        liveQueue.addFirst(e);
        signalPoolNotEmpty();
    }

    private void freePoolLock() {
        poolLockThread = null;
        // Need to use 'signalAll' here because there might be multiple
        // waiters (e.g., multiple borrowers) queued up, waiting for the
        // pool to be unlocked.
        poolNotLocked.signalAll();
        // Borrowers that are woken up when an instance is returned to the
        // pool and the pool queueLock is held would then start waiting on a
        // 'poolNotLocked' signal instead.  Re-signalling 'queueNotEmpty' here
        // allows any borrowers still waiting on the 'queueNotEmpty' signal to be
        // reawoken when the pool lock is released, compensating for any
        // 'queueNotEmpty' signals that might have been essentially ignored from
        // when the pool lock was held.
        if (liveQueue.size() > 0) {
            queueNotEmpty.signalAll();
        }
    }

    private void signalPoolNotEmpty() {
        // Could use 'signalAll' here instead of 'signal' but 'signal' is
        // less expensive in that only one waiter will be woken up.  Can use
        // signal here because the thread being awoken will be able to borrow
        // a pool instance and any further waiters will be woken up by
        // subsequent posts of this signal when instances are added/returned to
        // the queue.
        queueNotEmpty.signal();
        signalIfAllRegisteredInQueue();
    }

    private void signalIfAllRegisteredInQueue() {
        // Could use 'signalAll' here instead of 'signal'.  Doesn't really
        // matter though in that there will only be one waiter at most which
        // is active at a time - a caller of lock() that has just acquired
        // the pool lock but is waiting for all registered elements to be
        // returned to the queue.
        if (registeredElements.size() == liveQueue.size()) {
            allRegisteredInQueue.signal();
        }
    }

    private boolean isPoolLockHeld() {
        return poolLockThread != null;
    }

    private boolean isPoolLockHeldByCurrentThread(Thread currentThread) {
        return poolLockThread == currentThread;
    }

    private boolean isPoolLockHeldByAnotherThread(Thread currentThread) {
        return (poolLockThread != null) && (poolLockThread != currentThread);
    }
}
