/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zaxxer.hikari.util;

import static com.zaxxer.hikari.util.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.IConcurrentBagEntry.STATE_REMOVED;
import static com.zaxxer.hikari.util.IConcurrentBagEntry.STATE_RESERVED;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.AbstractQueuedLongSynchronizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a specialized concurrent bag that achieves superior performance
 * to LinkedBlockingQueue and LinkedTransferQueue for the purposes of a
 * connection pool.  It uses ThreadLocal storage when possible to avoid
 * locks, but resorts to scanning a common collection if there are no
 * available items in the ThreadLocal list.  Not-in-use items in the
 * ThreadLocal lists can be "stolen" when the borrowing thread has none
 * of its own.  It is a "lock-less" implementation using a specialized
 * AbstractQueuedLongSynchronizer to manage cross-thread signaling.
 *
 * Note that items that are "borrowed" from the bag are not actually
 * removed from any collection, so garbage collection will not occur
 * even if the reference is abandoned.  Thus care must be taken to
 * "requite" borrowed objects otherwise a memory leak will result.  Only
 * the "remove" method can completely remove an object from the bag.
 *
 * @author Brett Wooldridge
 *
 * @param <T> the templated type to store in the bag
 */
public class FastConcurrentBag<T extends IConcurrentBagEntry>
{
   private static final Logger LOGGER = LoggerFactory.getLogger(FastConcurrentBag.class);

   protected final AbstractQueuedLongSynchronizer synchronizer;
   protected final CopyOnWriteArrayList<T> sharedList;
   protected final AtomicLong sequence;

   private final ThreadLocal<ArrayList<WeakReference<IConcurrentBagEntry>>> threadList;
   private final IBagStateListener listener;
   private volatile boolean closed;

   /**
    * Construct a ConcurrentBag with the specified listener.
    *
    * @param listener the IBagStateListener to attach to this bag
    */
   public FastConcurrentBag(IBagStateListener listener)
   {
      this.sharedList = new CopyOnWriteArrayList<T>();
      this.synchronizer = createQueuedSynchronizer();
      this.sequence = new AtomicLong(1);
      this.listener = listener;
      this.threadList = new ThreadLocal<ArrayList<WeakReference<IConcurrentBagEntry>>>();
   }

   protected AbstractQueuedLongSynchronizer createQueuedSynchronizer()
   {
      throw new RuntimeException("createQueuedSynchronizer() method must be overridden");
   }

   /**
    * The method will borrow a BagEntry from the bag, blocking for the
    * specified timeout if none are available.
    * 
    * @param timeout how long to wait before giving up, in units of unit
    * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
    * @return a borrowed instance from the bag or null if a timeout occurs
    * @throws InterruptedException if interrupted while waiting
    */
   @SuppressWarnings("unchecked")
   public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException
   {
      if (!synchronizer.hasQueuedThreads()) {
         // Try the thread-local list first
         final ArrayList<WeakReference<IConcurrentBagEntry>> list = threadList.get();
         if (list == null) {
            threadList.set(new ArrayList<WeakReference<IConcurrentBagEntry>>(16));
         }
         else {
            for (int i = list.size() - 1; i >= 0; i--) {
               final IConcurrentBagEntry bagEntry = list.remove(i).get();
               if (bagEntry != null && bagEntry.state().compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                  return (T) bagEntry;
               }
            }
         }
      }

      // Otherwise, scan the shared list ... for maximum of timeout
      timeout = timeUnit.toNanos(timeout);
      Future<Boolean> addItemFuture = null;
      final long startScan = System.nanoTime();
      final long originTimeout = timeout;
      do {
         long startSeq;
         do {
            startSeq = sequence.get();
            for (final T bagEntry : sharedList) {
               if (bagEntry.state().compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                  return bagEntry;
               }
            }
         } while (startSeq < sequence.get());

         if (addItemFuture == null || addItemFuture.isDone()) {
            addItemFuture = listener.addBagItem();
         }

         if (!synchronizer.tryAcquireSharedNanos(startSeq, timeout)) {
            return null;
         }

         final long elapsed = (System.nanoTime() - startScan);
         timeout = originTimeout - Math.max(elapsed, 100L);  // don't trust the nanoTime() impl. not to go backwards due to NTP adjustments
      }
      while (timeout > 1000L);  // 1000ns is the minimum resolution on many systems

      return null;
   }

   /**
    * This method will return a borrowed object to the bag.  Objects
    * that are borrowed from the bag but never "requited" will result
    * in a memory leak.
    *
    * @param bagEntry the value to return to the bag
    * @throws NullPointerException if value is null
    * @throws IllegalStateException if the requited value was not borrowed from the bag
    */
   public void requite(final T bagEntry)
   {
      if (bagEntry.state().compareAndSet(STATE_IN_USE, STATE_NOT_IN_USE)) {
         final ArrayList<WeakReference<IConcurrentBagEntry>> list = threadList.get();
         if (list != null) {
            list.add(new WeakReference<IConcurrentBagEntry>(bagEntry));
         }
         synchronizer.releaseShared(sequence.incrementAndGet());
      }
      else {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry.toString());
      }
   }

   /**
    * Add a new object to the bag for others to borrow.
    *
    * @param bagEntry an object to add to the bag
    */
   public void add(final T bagEntry)
   {
      if (closed) {
         LOGGER.info("ConcurrentBag has been closed, ignoring add()");
         throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
      }

      sharedList.add(bagEntry);
      synchronizer.releaseShared(sequence.incrementAndGet());
   }

   /**
    * Remove a value from the bag.  This method should only be called
    * with objects obtained by <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
    *
    * @param bagEntry the value to remove
    * @return true if the entry was removed, false otherwise
    * @throws IllegalStateException if an attempt is made to remove an object
    *         from the bag that was not borrowed or reserved first
    */
   public boolean remove(final T bagEntry)
   {
      if (!bagEntry.state().compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.state().compareAndSet(STATE_RESERVED, STATE_REMOVED) && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", bagEntry.toString());
         return false;
      }

      final boolean removed = sharedList.remove(bagEntry);
      if (!removed && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry.toString());
      }
      return removed;
   }

   /**
    * Close the bag to further adds.
    */
   public void close()
   {
      closed = true;
   }

   /**
    * This method provides a "snaphot" in time of the BagEntry
    * items in the bag in the specified state.  It does not "lock"
    * or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in list before performing any action on them.
    *
    * @param state one of STATE_NOT_IN_USE or STATE_IN_USE
    * @return a possibly empty list of objects having the state specified
    */
   public List<T> values(final int state)
   {
      final ArrayList<T> list = new ArrayList<T>(sharedList.size());
      if (state == STATE_IN_USE || state == STATE_NOT_IN_USE) {
         for (final T reference : sharedList) {
            if (reference.state().get() == state) {
               list.add(reference);
            }
         }
      }
      return list;
   }

   /**
    * The method is used to make an item in the bag "unavailable" for
    * borrowing.  It is primarily used when wanting to operate on items
    * returned by the <code>values(int)</code> method.  Items that are
    * reserved can be removed from the bag via <code>remove(T)</code>
    * without the need to unreserve them.  Items that are not removed
    * from the bag can be make available for borrowing again by calling
    * the <code>unreserve(T)</code> method.
    *
    * @param bagEntry the item to reserve
    * @return true if the item was able to be reserved, false otherwise
    */
   public boolean reserve(final T bagEntry)
   {
      return bagEntry.state().compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
   }

   /**
    * This method is used to make an item reserved via <code>reserve(T)</code>
    * available again for borrowing.
    *
    * @param bagEntry the item to unreserve
    */
   public void unreserve(final T bagEntry)
   {
      final long checkInSeq = sequence.incrementAndGet();
      if (bagEntry.state().compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
         synchronizer.releaseShared(checkInSeq);
      }
      else {
         LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry.toString());
      }
   }

   /**
    * Get the number of threads pending (waiting) for an item from the
    * bag to become available.
    *
    * @return the number of threads waiting for items from the bag
    */
   public int getPendingQueue()
   {
      return synchronizer.getQueueLength();
   }

   /**
    * Get a count of the number of items in the specified state at the time of this call.
    *
    * @param state the state of the items to count
    * @return a count of how many items in the bag are in the specified state
    */
   public int getCount(final int state)
   {
      int count = 0;
      for (final T reference : sharedList) {
         if (reference.state().get() == state) {
            count++;
         }
      }
      return count;
   }

   /**
    * Get the total number of items in the bag.
    *
    * @return the number of items in the bag 
    */
   public int size()
   {
      return sharedList.size();
   }

   public void dumpState()
   {
      for (T bagEntry : sharedList) {
         LOGGER.info(bagEntry.toString());
      }
   }
}
