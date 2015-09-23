package com.puppetlabs.puppetserver.pool;

import java.util.concurrent.TimeUnit;

public interface LockablePool<E> {

    void register(E e) throws InterruptedException;

    E borrowItem() throws InterruptedException;

    E borrowItemWithTimeout(long timout, TimeUnit unit) throws InterruptedException;

    void returnItem(E e) throws InterruptedException;

    void insertPill(E e) throws InterruptedException;

    void clear();

    int remainingCapacity();

    int size();

    void lock() throws InterruptedException;

    void unlock() throws InterruptedException;
}
