package com.puppetlabs.puppetserver.pool;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.puppetlabs.puppetserver.pool.LockablePool;

public final class JRubyPool<E> implements LockablePool<E> {
    private final LinkedBlockingDeque<E> live_queue;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Set<E> registeredElements = new CopyOnWriteArraySet<>();

    public JRubyPool(int size) {
        live_queue = new LinkedBlockingDeque<>(size);
    }

    @Override
    public void register(E e) throws InterruptedException {
        live_queue.putLast(e);
        registeredElements.add(e);
    }

    @Override
    public E borrowItem() throws InterruptedException, IllegalStateException {
        if (lock.isWriteLockedByCurrentThread()) {
          throw new IllegalStateException("The current implementation has a risk of deadlock if you attempt to borrow a JRuby instance while holding the write lock!");
        }
        E item = live_queue.takeFirst();
        lock.readLock().lock();
        return item;
    }

    @Override
    public E borrowItemWithTimeout(long timeout, TimeUnit unit) throws InterruptedException, IllegalStateException {
        if (lock.isWriteLockedByCurrentThread()) {
          throw new IllegalStateException("The current implementation has a risk of deadlock if you attempt to borrow a JRuby instance while holding the write lock!");
        }
        E item = live_queue.pollFirst(timeout, unit);
        lock.readLock().lock();
        return item;
    }

    @Override
    public void returnItem(E e) throws InterruptedException {
        try {
            live_queue.putFirst(e);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void insertPill(E e) throws InterruptedException {
        live_queue.putFirst(e);
    }

    @Override
    public void clear() {
        live_queue.clear();
    }

    @Override
    public int remainingCapacity() {
        return live_queue.remainingCapacity();
    }

    @Override
    public int size() {
        return live_queue.size();
    }

    @Override
    public void lock() throws InterruptedException {
        lock.writeLock().lock();
    }

    @Override
    public void unlock() throws InterruptedException {
        lock.writeLock().unlock();
    }

    /**
     * @return a set of all of the known elements that have been registered with
     *         this queue.
     */
    public Set<E> getRegisteredElements() {
        return registeredElements;
    }

}
