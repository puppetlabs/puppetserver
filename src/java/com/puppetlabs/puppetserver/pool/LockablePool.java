package com.puppetlabs.puppetserver.pool;

import java.util.concurrent.TimeUnit;

public interface LockablePool<E> {
    public void register(E e) throws InterruptedException;

    public E borrowItem() throws InterruptedException;

    public E borrowItemWithTimeout(long timout, TimeUnit unit) throws InterruptedException;

    public void returnItem(E e) throws InterruptedException;

    public void insertPill(E e) throws InterruptedException;

    public void clear();

    public int remainingCapacity();

    public int size();

    public void lock() throws InterruptedException;

    public void unlock() throws InterruptedException;
}
