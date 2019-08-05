/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;
    // Recycler 中存放在线程本地变量中的 Stack，里边存放的是 DefaultHandle，它持有了 PooledUnsafeHeapByteBuf
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);  // 这里说明 Stack 持有了 Recycler，当前线程，以及 Recycler 的四个字段属性值
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;  // 任务比
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }
    // 从线程本地获取 stack，pop 出栈顶元素，类型为 DefaultHandle，如果为空就新建一个，然后设置它的 value 为新建的 PooledUnsafeDirectByteBuf，同时 DefaultHandle 的 value 就是 PooledUnsafeDirectByteBuf
    @SuppressWarnings("unchecked")
    public final T get() {  // 从线程本地获取 stack，pop 出栈顶元素，如果为空就新建一个，然后设置它的 value 为新建的 PooledUnsafeDirectByteBuf
        if (maxCapacityPerThread == 0) {    // 这里应该是线程不允许回收的情况
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        Stack<T> stack = threadLocal.get(); // 获取 threadLocal 的 index 对应的那个元素，这里说明 Recycler 是通过线程本地变量来存储 Stack 的，而它里边又是存储的 DefaultHandle，它持有了我们想要的 PooledUnsafeDirectByteBuf
        DefaultHandle<T> handle = stack.pop();   // 获取栈顶元素，可能会涉及到元素转移的过程
        if (handle == null) {   // 这里说明 Stack 中存储的是 DefaultHandle
            handle = stack.newHandle(); // 新建 DefaultHandle
            handle.value = newObject(handle);   // 新建了一个 PooledUnsafeDirectByteBuf，它持有了 DefaultHandle，同时 DefaultHandle 的 value 为新创建的 PooledUnsafeDirectByteBuf
        }
        return (T) handle.value;    // 返回新构建的那个 PooledUnsafeDirectByteBuf
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> {
        void recycle(T object);
    }

    static final class DefaultHandle<T> implements Handle<T> {
        private int lastRecycledId;
        private int recycleId;

        boolean hasBeenRecycled;

        private Stack<?> stack;
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }

            stack.push(this);
        }
    }

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            private int readIndex;
            Link next;
        }

        // This act as a place holder for the head Link but also will reclaim space once finalized.
        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
            @Override
            protected void finalize() throws Throwable {
                try {
                    super.finalize();
                } finally {
                    Link head = link;
                    link = null;
                    while (head != null) {
                        reclaimSpace(LINK_CAPACITY);    // 更新 availableSharedCapacity 可用共享空间的信息
                        Link next = head.next;
                        // Unlink to help GC and guard against GC nepotism.
                        head.next = null;
                        head = next;
                    }
                }
            }
            // 更新 availableSharedCapacity 可用共享空间的信息
            void reclaimSpace(int space) {
                assert space >= 0;
                availableSharedCapacity.addAndGet(space);
            }

            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < space) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final WeakReference<Thread> owner;
        private final int id = ID_GENERATOR.getAndIncrement();

        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            owner = new WeakReference<Thread>(thread);
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? newQueue(stack, thread) : null;
        }

        void add(DefaultHandle<?> handle) {
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);
        }
        // 如果 reader index 和实际值不一致，证明是有数据的
        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")   // 没太看懂，大致就是一个将 elements 从 WeakOrderQueue 中转移到 stack 的 elements 的过程
        boolean transfer(Stack<?> dst) {
            Link head = this.head.link; // 这里是 WeakOrderQueue 的 head.link，可以理解为一个单链表的节点
            if (head == null) {
                return false;
            }
            // 如果 link 的 readIndex 等于 LINK_CAPACITY
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {    // 如果 WeakOrderQueue 的 head（持有了 link）持有的 link 的 next 为空，直接返回 false
                    return false;
                }   // 那么就让 WeakOrderQueue 的 head（持有了 link）持有的 link 指向原 link 的下一个 link 元素
                this.head.link = head = head.next;
            }

            final int srcStart = head.readIndex;    // 记录下它的 read index
            int srcEnd = head.get();    // 原子整型的 get 方法
            final int srcSize = srcEnd - srcStart;  // 大小
            if (srcSize == 0) {
                return false;   // 大小为 0，也是直接返回
            }

            final int dstSize = dst.size;   // stack 的大小
            final int expectedCapacity = dstSize + srcSize; // 这下转移后，stack 的空间大小

            if (expectedCapacity > dst.elements.length) {   // 如果期望的大小超过了 stack elements 数组的长度
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);  // 根据原空间长度和最大长度，进行扩容，完成后进行数据拷贝，返回扩容后的大小
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);  // 修正 srcEnd 值
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements; // 获取 Link 持有的 elements
                final DefaultHandle[] dstElems = dst.elements;  // stack 持有的 elements
                int newDstSize = dstSize;   // 这里是扩容后的大小
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];    // 逐个获取 link 中的 element
                    if (element.recycleId == 0) {   // 如果 recycleId 为 0，进 recycleId 的行更新
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {   // 这里说明 element 的 recycle id 是一定等用户 last recycle id 的
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null; // 清空 link 中的原 element
                    // 如果 handler 已经是被回收状态，不错处理，否则根据 handleRecycleCount 来决定是否修改 hasBeenRecycled 的值
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;    // 让 element 持有 stack 信息
                    dstElems[newDstSize ++] = element;  // 将 element 转移到 stack 中
                }
                // 这说明 link 已经是被被回收了
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.reclaimSpace(LINK_CAPACITY);  // 更新 availableSharedCapacity 可用共享空间的信息
                    this.head.link = head.next; // 让 link 指向新的空间位置
                }

                head.readIndex = srcEnd;    // 更新 read index 信息
                if (dst.size == newDstSize) {   // 如果空间没有发生变化，那么就说明转移发生失败
                    return false;
                }
                dst.size = newDstSize;  // 更新转移后的空间大小值
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    static final class Stack<T> {   // 需要注意这个 Stack 是 netty 自身维护的一个数据结构

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        final WeakReference<Thread> threadRef;
        final AtomicInteger availableSharedCapacity;
        final int maxDelayedQueues;

        private final int maxCapacity;
        private final int ratioMask;
        private DefaultHandle<?>[] elements;
        private int size;
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }
        // 根据原空间长度和最大长度，进行扩容，完成后进行数据拷贝，返回扩容后的大小
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;  // 原长度
            int maxCapacity = this.maxCapacity; // 最大长度
            do {
                newCapacity <<= 1;  // 原长度翻倍
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);  // 这里就是一个原长度不断翻倍直到满足需求且不超过最大容量的过程
            // 够用情况下不超过 maxCapacity
            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {   // 扩容可以的话，就数据拷贝
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }
        // 获取栈顶元素，可能会涉及到元素转移的过程
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {   // 好像就是一个回收 WeakOrderQueue 链表的过程，失败的话，就将 cursor 指向 head
                    return null;
                }
                size = this.size;
            }
            size --;
            DefaultHandle ret = elements[size]; // 这是 stack 中的 element
            elements[size] = null;  // 因为是 pop，所以置空栈顶的元素
            if (ret.lastRecycledId != ret.recycleId) {  // 状态验证
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;  // 回收后，清空其 recycle id
            ret.lastRecycledId = 0;
            this.size = size;
            return ret; // 返回栈顶的元素
        }
        // 好像就是一个回收 WeakOrderQueue 链表的过程，失败的话，就将 cursor 指向 head
        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {   // 好像就是一个回收 WeakOrderQueue 链表的过程
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }
        // 好像就是一个回收 WeakOrderQueue 链表的过程
        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {   // 这里如果成立，说明 head 节点都是空的，没有进行清除，直接返回 false
                    return false;
                }
            } else {    // 如果 cursor 不为空， prev 指向当前记录的 prev
                prev = this.prev;
            }

            boolean success = false;
            do {    // 没太看懂，大致就是一个将 elements 从 WeakOrderQueue 中转移到 stack 的 elements 的过程
                if (cursor.transfer(this)) {    // 即将 cursor 中的 elements 转移到 stack 中
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {   // 如果 cursor 所属的线程不为空（从线程本地变量中获取）
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {    // 如果 reader index 和实际值不一致，证明是有数据的
                        for (;;) {
                            if (cursor.transfer(this)) {    // 将 cursor 中的 elements 转移到 stack 中
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        prev.setNext(next); // 断开被收的 link，直接将 prev 指向 next
                    }
                } else {
                    prev = cursor;
                }
                // 这里是一个向前查找的过程？？
                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            queue.add(item);
        }
        // 如果 handler 已经是被回收状态，不错处理，否则根据 handleRecycleCount 来决定是否修改 hasBeenRecycled 的值
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if ((++handleRecycleCount & ratioMask) != 0) {  // 更新处理回收的计数值
                    // Drop the object.
                    return true;
                }   // 这里是超过 7 次，就更新 hasBeenRecycled 的值
                handle.hasBeenRecycled = true;
            }
            return false;
        }
        // 新建 DefaultHandle
        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
