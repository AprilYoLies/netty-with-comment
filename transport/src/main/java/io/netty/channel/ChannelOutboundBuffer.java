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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static java.lang.Math.min;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 * <p>
 * All methods must be called by a transport implementation from an I/O thread, except the following ones:
 * <ul>
 * <li>{@link #size()} and {@link #isEmpty()}</li>
 * <li>{@link #isWritable()}</li>
 * <li>{@link #getUserDefinedWritability(int)} and {@link #setUserDefinedWritability(int, boolean)}</li>
 * </ul>
 * </p>
 */
public final class ChannelOutboundBuffer {
    // Assuming a 64-bit JVM:
    //  - 16 bytes object header
    //  - 8 reference fields
    //  - 2 long fields
    //  - 2 int fields
    //  - 1 boolean field
    //  - padding
    static final int CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD =
            SystemPropertyUtil.getInt("io.netty.transport.outboundBufferEntrySizeOverhead", 96);

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final FastThreadLocal<ByteBuffer[]> NIO_BUFFERS = new FastThreadLocal<ByteBuffer[]>() {
        @Override
        protected ByteBuffer[] initialValue() throws Exception {
            return new ByteBuffer[1024];
        }
    };

    private final Channel channel;

    // Entry(flushedEntry) --> ... Entry(unflushedEntry) --> ... Entry(tailEntry)
    //
    // The Entry that is the first in the linked-list structure that was flushed
    private Entry flushedEntry;
    // The Entry which is the first unflushed in the linked-list structure
    private Entry unflushedEntry;
    // The Entry which represents the tail of the buffer
    private Entry tailEntry;
    // The number of flushed entries that are not written yet
    private int flushed;

    private int nioBufferCount;
    private long nioBufferSize;

    private boolean inFail;

    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    @SuppressWarnings("UnusedDeclaration")
    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> UNWRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "unwritable");

    @SuppressWarnings("UnusedDeclaration")
    private volatile int unwritable;

    private volatile Runnable fireChannelWritabilityChangedTask;

    ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
    }

    /**
     * Add given message to this {@link ChannelOutboundBuffer}. The given {@link ChannelPromise} will be notified once
     * the message was written.
     */ // 就是用 Entry 承载待发送 msg，然后将相关的变量指向创建的 entry，最后更新将要数据字段的值
    public void addMessage(Object msg, int size, ChannelPromise promise) {
        Entry entry = Entry.newInstance(msg, size, total(msg), promise);    // 尝试从 RECYCLER 中获取 Entry，然后加参数设置给 Entry
        if (tailEntry == null) {    // tailEntry 为空处理方式
            flushedEntry = null;
        } else {
            Entry tail = tailEntry; // 不为空就是追加
            tail.next = entry;
        }
        tailEntry = entry;  // tailEntry 指向获取的 entry
        if (unflushedEntry == null) {   // 将 unflushedEntry 也指向获取的 entry
            unflushedEntry = entry;
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        incrementPendingOutboundBytes(entry.pendingSize, false);
    }   // 设置将要发送的总字节数的值，如果这个值超出了上界，还要根据情况触发 channel 可写状态变化事件

    /**
     * Add a flush to this {@link ChannelOutboundBuffer}. This means all previous added messages are marked as flushed
     * and so you will be able to handle them.
     */ // 这里差不多就是一个将 entry 从 unflushedEntry 单链表移到 flushedEntry 单链表的过程（只是修改了头的指向）
    public void addFlush() {
        // There is no need to process all entries if there was already a flush before and no new messages
        // where added in the meantime.
        //
        // See https://github.com/netty/netty/issues/2577
        Entry entry = unflushedEntry;   // 其实在 write 中，unflushedEntry 已经指向了待发送的数据了
        if (entry != null) {
            if (flushedEntry == null) {
                // there is no flushedEntry yet, so start with the entry
                flushedEntry = entry;   // flushedEntry 指向 entry
            }
            do {
                flushed ++; // 计数加加
                if (!entry.promise.setUncancellable()) {    // 检查 promise 的状态是否已经被设置过了
                    // Was cancelled so make sure we free up memory and notify about the freed bytes
                    int pending = entry.cancel();   // 取消 entry，就是对相关的参数值进行归零，返回 pendingSize
                    decrementPendingOutboundBytes(pending, false, true);    // 更新待发送字节数据的值
                }
                entry = entry.next;
            } while (entry != null);    // 因为 flushedEntry 已经指向 entry，所以这里的循环只是更新了 flushed 计数值

            // All flushed so reset unflushedEntry
            unflushedEntry = null;  // 重置 unflushedEntry 的值
        }
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(long size) {
        incrementPendingOutboundBytes(size, true);
    }
    // 设置将要发送的总字节数的值，如果这个值超出了上界，还要根据情况触发 channel 可写状态变化事件
    private void incrementPendingOutboundBytes(long size, boolean invokeLater) {
        if (size == 0) {
            return;
        }
        // 更新当前实例的 totalPendingSize 字段值
        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size);
        if (newWriteBufferSize > channel.config().getWriteBufferHighWaterMark()) {   // 如果待发送的数据超过了上限，
            setUnwritable(invokeLater); // 设置 unwritable 字段的值，如果前后值发生变化，触发 channel 可写状态变化事件
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(long size) {
        decrementPendingOutboundBytes(size, true, true);
    }

    private void decrementPendingOutboundBytes(long size, boolean invokeLater, boolean notifyWritability) {
        if (size == 0) {
            return;
        }

        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);
        if (notifyWritability && newWriteBufferSize < channel.config().getWriteBufferLowWaterMark()) {
            setWritable(invokeLater);
        }
    }
    // 获取 msg 的数据大小
    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    /**
     * Return the current message to write or {@code null} if nothing was flushed before and so is ready to be written.
     */ // 就是获取第一个 flushedEntry 的 msg
    public Object current() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return null;
        }

        return entry.msg;
    }

    /**
     * Notify the {@link ChannelPromise} of the current message about writing progress.
     */ // 这里是更新写进度，也就是更新 progress 值，然后将 progress 和 total 信息进行更新
    public void progress(long amount) {
        Entry e = flushedEntry;
        assert e != null;
        ChannelPromise p = e.promise;
        if (p instanceof ChannelProgressivePromise) {
            long progress = e.progress + amount;    // 更新进度
            e.progress = progress;          // 通知进度和总数的信息
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as success and return {@code true}. If no
     * flushed message exists at the time this method is called it will return {@code false} to signal that no more
     * messages are ready to be handled.
     */ // 将第一个 flushedEntry 从列表中移除，设置相关的 promise，释放资源，回收这个 entry
    public boolean remove() {
        Entry e = flushedEntry;
        if (e == null) {    // 如果第一个 flushedEntry 为空
            clearNioBuffers();  // 将线程本地的 ByteBuffer 数组全部置为 null，重置 nioBufferCount 值
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        removeEntry(e); // 从 flushedEntry 单链表中移除 e，修改对应的指针指向

        if (!e.cancelled) { // 这时 e 的状态一定是被取消的
            // only release message, notify and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);    // 释放 msg
            safeSuccess(promise);   // 设置操作的状态
            decrementPendingOutboundBytes(size, false, true);   // 更新待发送的字节数值
        }

        // recycle the entry
        e.recycle();    // 回收 entry

        return true;
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as failure using the given {@link Throwable}
     * and return {@code true}. If no   flushed message exists at the time this method is called it will return
     * {@code false} to signal that no more messages are ready to be handled.
     */
    public boolean remove(Throwable cause) {
        return remove0(cause, true);
    }
    // 获取 flushedEntry 单链表的第一个元素，不为空的情况下，将其从 flushedEntry 单链表移除，还需要确保它的状态是 cancelled
    private boolean remove0(Throwable cause, boolean notifyWritability) {
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();  // 如果没有 flushed 的 entry，将线程本地的 ByteBuffer 数组全部置为 null，重置 nioBufferCount 值
            return false;
        }
        Object msg = e.msg;
        // 获取第一个 flushedEntry 项的一些相关值
        ChannelPromise promise = e.promise;
        int size = e.pendingSize;
        // 从 flushedEntry 单链表中移除 e，修改对应的指针指向
        removeEntry(e);
        // 因为是 close 或者 inactive 触发的事件，所以这里要确保 entry 是取消状态
        if (!e.cancelled) {
            // only release message, fail and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);    // 释放 msg

            safeFail(promise, cause);   // 触发异常事件
            decrementPendingOutboundBytes(size, false, notifyWritability);  // 修改待发送字节数的值
        }

        // recycle the entry
        e.recycle();

        return true;
    }
    // 从 flushedEntry 单链表中移除 e，修改对应的指针指向
    private void removeEntry(Entry e) {
        if (-- flushed == 0) {  // 修改 flushed 值
            // processed everything
            flushedEntry = null;    // 没有待处理的 entry 了，flushedEntry 指向 null
            if (e == tailEntry) {
                tailEntry = null;
                unflushedEntry = null;
            }
        } else {    // flushedEntry 指向下一个 entry
            flushedEntry = e.next;
        }
    }

    /**
     * Removes the fully written entries and update the reader index of the partially written entry.
     * This operation assumes all messages in this buffer is {@link ByteBuf}.
     */ // 这里就是根据实际写和可读数据来更新相关的进度信息，同时将线程本地的 ByteBuffer 数组全部置为 null，重置 nioBufferCount 值
    public void removeBytes(long writtenBytes) {
        for (;;) {
            Object msg = current(); // 就是获取第一个 flushedEntry 的 msg
            if (!(msg instanceof ByteBuf)) {
                assert writtenBytes == 0;
                break;
            }

            final ByteBuf buf = (ByteBuf) msg;
            final int readerIndex = buf.readerIndex();
            final int readableBytes = buf.writerIndex() - readerIndex;

            if (readableBytes <= writtenBytes) {    // 这里成立，可读的数据已经全部写入完成
                if (writtenBytes != 0) {
                    progress(readableBytes);    // 这里是更新写进度，也就是更新 progress 值，然后将 progress 和 total 信息进行更新
                    writtenBytes -= readableBytes;
                }
                remove();   // 将第一个 flushedEntry 从列表中移除，设置相关的 promise，释放资源，回收这个 entry
            } else { // readableBytes > writtenBytes
                if (writtenBytes != 0) {    // 这里说明还没有写完，实际写的少于可读的
                    buf.readerIndex(readerIndex + (int) writtenBytes);  // 修正 reader index 位置
                    progress(writtenBytes); // 更新进度消息
                }
                break;
            }
        }   // 将线程本地的 ByteBuffer 数组全部置为 null，重置 nioBufferCount 值
        clearNioBuffers();
    }

    // Clear all ByteBuffer from the array so these can be GC'ed.
    // See https://github.com/netty/netty/issues/3837
    private void clearNioBuffers() {    // 将线程本地的 ByteBuffer 数组全部置为 null，重置 nioBufferCount 值
        int count = nioBufferCount;
        if (count > 0) {
            nioBufferCount = 0; // 将线程本地的 ByteBuffer 数组全部置为 null
            Arrays.fill(NIO_BUFFERS.get(), 0, count, null);
        }
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     * @param maxCount The maximum amount of buffers that will be added to the return value.
     * @param maxBytes A hint toward the maximum number of bytes to include as part of the return value. Note that this
     *                 value maybe exceeded because we make a best effort to include at least 1 {@link ByteBuffer}
     *                 in the return value to ensure write progress is made.
     */ // 这个过程就是依次从 flushedEntry 单链表中提取出对应的 byte buffer，过程中记录了实际将要写的字节数，
    public ByteBuffer[] nioBuffers(int maxCount, long maxBytes) {
        assert maxCount > 0;
        assert maxBytes > 0;
        long nioBufferSize = 0;
        int nioBufferCount = 0;
        final InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get(); // 根据线程类型的不同，获取对应的 InternalThreadLocalMap
        ByteBuffer[] nioBuffers = NIO_BUFFERS.get(threadLocalMap);  // 尝试从 threadLocalMap 获取当前 FastThreadLocal 的 index 值对应的元素，没有的话就进行初始化，返回初始化的值
        Entry entry = flushedEntry; // 获取待写数据的指针
        while (isFlushedEntry(entry) && entry.msg instanceof ByteBuf) { // 只对非空的 flushedEntry，且 msg 为 ByteBuf 的 entry 进行处理
            if (!entry.cancelled) { // 也不能是已取消状态
                ByteBuf buf = (ByteBuf) entry.msg;
                final int readerIndex = buf.readerIndex();  // 得到 msg 的 read index
                final int readableBytes = buf.writerIndex() - readerIndex;  // 可读字节数

                if (readableBytes > 0) {
                    if (maxBytes - readableBytes < nioBufferSize && nioBufferCount != 0) {  // 写的数据超过上界且
                        // If the nioBufferSize + readableBytes will overflow maxBytes, and there is at least one entry
                        // we stop populate the ByteBuffer array. This is done for 2 reasons:
                        // 1. bsd/osx don't allow to write more bytes then Integer.MAX_VALUE with one writev(...) call
                        // and so will return 'EINVAL', which will raise an IOException. On Linux it may work depending
                        // on the architecture and kernel but to be safe we also enforce the limit here.
                        // 2. There is no sense in putting more data in the array than is likely to be accepted by the
                        // OS.
                        //
                        // See also:
                        // - https://www.freebsd.org/cgi/man.cgi?query=write&sektion=2
                        // - http://linux.die.net/man/2/writev
                        break;
                    }
                    nioBufferSize += readableBytes; // 统计写的数据
                    int count = entry.count;
                    if (count == -1) {
                        //noinspection ConstantValueVariableUse
                        entry.count = count = buf.nioBufferCount(); // 获取 buf 中 nio buffer 的个数
                    }   // 计算至少需要的空间数，不超过上界的情况下，为先前统计的 byte buffer 数目加上当前 entry 需要的 byte buffer 数
                    int neededSpace = min(maxCount, nioBufferCount + count);
                    if (neededSpace > nioBuffers.length) {  // 需要的 byte buffer 个数超过了获取的 byte buffer 数组的长度
                        nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount); // 扩容 ByteBuffer 数组的长度直到满足需求
                        NIO_BUFFERS.set(threadLocalMap, nioBuffers);    // 缓存到 thread local 中
                    }
                    if (count == 1) {   // 如果对于 byte buffer 的需求就只只有一个
                        ByteBuffer nioBuf = entry.buf;  // 直接拿到 entry 中持有的那个 byte buffer
                        if (nioBuf == null) {
                            // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a
                            // derived buffer   // 就是创建的一个 ByteBuffer，但是它共用了 memory 的存储区域
                            entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes);
                        }
                        nioBuffers[nioBufferCount++] = nioBuf;  // nioBuffers 指向该 nioBuf
                    } else {
                        // The code exists in an extra method to ensure the method is not too big to inline as this
                        // branch is not very likely to get hit very frequently.    // 这里是 entry 或者 buf 持有的 byte buffer 超过一的处理，就是将这里 byte buffer 用 nioBuffers 来引用这些 byte buffer
                        nioBufferCount = nioBuffers(entry, buf, nioBuffers, nioBufferCount, maxCount);
                    }
                    if (nioBufferCount == maxCount) {
                        break;
                    }
                }
            }
            entry = entry.next; // 继续处理下一个 entry
        }
        this.nioBufferCount = nioBufferCount;   // 记录下实际提取出来的 byte buffer 数目
        this.nioBufferSize = nioBufferSize; // 记录下实际要写的 byte 数目

        return nioBuffers;
    }
    // 这里是 entry 或者 buf 持有的 byte buffer 超过一的处理，就是将这里 byte buffer 用 nioBuffers 来引用这些 byte buffer
    private static int nioBuffers(Entry entry, ByteBuf buf, ByteBuffer[] nioBuffers, int nioBufferCount, int maxCount) {
        ByteBuffer[] nioBufs = entry.bufs;  // 这里说明 entry 内部持有了不止一个 ByteBuffer
        if (nioBufs == null) {
            // cached ByteBuffers as they may be expensive to create in terms
            // of Object allocation
            entry.bufs = nioBufs = buf.nioBuffers();    // entry 中没有获取到 nioBufs，就从 buf 获取
        }
        for (int i = 0; i < nioBufs.length && nioBufferCount < maxCount; ++i) { // 这里就是将这些 nioBufs，用 nioBuffers 引用起来，返回被引用的个数
            ByteBuffer nioBuf = nioBufs[i];
            if (nioBuf == null) {
                break;
            } else if (!nioBuf.hasRemaining()) {
                continue;
            }
            nioBuffers[nioBufferCount++] = nioBuf;  // 引用这些 byte buffer
        }
        return nioBufferCount;
    }
    // 扩容 ByteBuffer 数组的长度直到满足需求
    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) {
        int newCapacity = array.length;
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;  // 翻倍

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);    // 不断翻倍，直到空间满足需求

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];    // 构建新长度的 ByteBuffer 数组
        System.arraycopy(array, 0, newArray, 0, size);  // 原数据内容的拷贝

        return newArray;
    }

    /**
     * Returns the number of {@link ByteBuffer} that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public int nioBufferCount() {
        return nioBufferCount;
    }

    /**
     * Returns the number of bytes that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public long nioBufferSize() {
        return nioBufferSize;
    }

    /**
     * Returns {@code true} if and only if {@linkplain #totalPendingWriteBytes() the total number of pending bytes} did
     * not exceed the write watermark of the {@link Channel} and
     * no {@linkplain #setUserDefinedWritability(int, boolean) user-defined writability flag} has been set to
     * {@code false}.
     */
    public boolean isWritable() {
        return unwritable == 0;
    }

    /**
     * Returns {@code true} if and only if the user-defined writability flag at the specified index is set to
     * {@code true}.
     */
    public boolean getUserDefinedWritability(int index) {
        return (unwritable & writabilityMask(index)) == 0;
    }

    /**
     * Sets a user-defined writability flag at the specified index.
     */
    public void setUserDefinedWritability(int index, boolean writable) {
        if (writable) {
            setUserDefinedWritability(index);
        } else {
            clearUserDefinedWritability(index);
        }
    }

    private void setUserDefinedWritability(int index) {
        final int mask = ~writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private void clearUserDefinedWritability(int index) {
        final int mask = writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private static int writabilityMask(int index) {
        if (index < 1 || index > 31) {
            throw new IllegalArgumentException("index: " + index + " (expected: 1~31)");
        }
        return 1 << index;
    }

    private void setWritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & ~1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }
    // 设置 unwritable 字段的值，如果前后值发生变化，触发 channel 可写状态变化事件
    private void setUnwritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | 1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {   // 设置 unwritable 字段的值
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(invokeLater); // 触发 channel 可写状态变化事件
                }
                break;
            }
        }
    }
    // 根据参数触发 channel 可写状态变化事件
    private void fireChannelWritabilityChanged(boolean invokeLater) {
        final ChannelPipeline pipeline = channel.pipeline();
        if (invokeLater) {  // 异步触发 channel 可写状态变化事件
            Runnable task = fireChannelWritabilityChangedTask;
            if (task == null) {
                fireChannelWritabilityChangedTask = task = new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelWritabilityChanged();
                    }
                };
            }
            channel.eventLoop().execute(task);
        } else {    // 同步触发 channel 可写状态变化事件
            pipeline.fireChannelWritabilityChanged();
        }
    }

    /**
     * Returns the number of flushed messages in this {@link ChannelOutboundBuffer}.
     */
    public int size() {
        return flushed;
    }

    /**
     * Returns {@code true} if there are flushed messages in this {@link ChannelOutboundBuffer} or {@code false}
     * otherwise.
     */ // 就是查看 flushed 计数值是否为 0
    public boolean isEmpty() {
        return flushed == 0;
    }
    // 获取 flushedEntry 单链表的第一个元素，不为空的情况下，将其从 flushedEntry 单链表移除，还需要确保它的状态是 cancelled
    void failFlushed(Throwable cause, boolean notify) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {   // 状态标志，避免重复操作
            return;
        }

        try {
            inFail = true;  // 设置状态标志，避免重复操作
            for (;;) {  // 获取 flushedEntry 单链表的第一个元素，不为空的情况下，将其从 flushedEntry 单链表移除，还需要确保它的状态是 cancelled
                if (!remove0(cause, notify)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }
    // 依次更新待发送字节数，释放对应的 entry 项
    void close(final Throwable cause, final boolean allowChannelOpen) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause, allowChannelOpen);
                }
            });
            return;
        }

        inFail = true;

        if (!allowChannelOpen && channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        try {
            Entry e = unflushedEntry;
            while (e != null) {
                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);  // 更新待发送字节数值

                if (!e.cancelled) { // 如果
                    ReferenceCountUtil.safeRelease(e.msg);  // 释放 msg，即 byte buf
                    safeFail(e.promise, cause); // 仅仅是记录了异常日志
                }
                e = e.recycleAndGetNext();  // 回收当前的 entry，拿到下一个
            }
        } finally {
            inFail = false;
        }   // 将线程本地的 ByteBuffer 数组全部置为 null，重置 nioBufferCount 值
        clearNioBuffers();
    }

    void close(ClosedChannelException cause) {
        close(cause, false);
    }

    private static void safeSuccess(ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as trySuccess(...) is expected to return
        // false.
        PromiseNotificationUtil.trySuccess(promise, null, promise instanceof VoidChannelPromise ? null : logger);
    }
    // 仅仅是记录了异常日志
    private static void safeFail(ChannelPromise promise, Throwable cause) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.   // 仅仅是记录了异常日志
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Deprecated
    public void recycle() {
        // NOOP
    }

    public long totalPendingWriteBytes() {
        return totalPendingSize;
    }

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     */
    public long bytesBeforeUnwritable() {
        long bytes = channel.config().getWriteBufferHighWaterMark() - totalPendingSize;
        // If bytes is negative we know we are not writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? bytes : 0;
        }
        return 0;
    }

    /**
     * Get how many bytes must be drained from the underlying buffer until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     */
    public long bytesBeforeWritable() {
        long bytes = totalPendingSize - channel.config().getWriteBufferLowWaterMark();
        // If bytes is negative we know we are writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? 0 : bytes;
        }
        return 0;
    }

    /**
     * Call {@link MessageProcessor#processMessage(Object)} for each flushed message
     * in this {@link ChannelOutboundBuffer} until {@link MessageProcessor#processMessage(Object)}
     * returns {@code false} or there are no more flushed messages to process.
     */
    public void forEachFlushedMessage(MessageProcessor processor) throws Exception {
        if (processor == null) {
            throw new NullPointerException("processor");
        }

        Entry entry = flushedEntry;
        if (entry == null) {
            return;
        }

        do {
            if (!entry.cancelled) {
                if (!processor.processMessage(entry.msg)) {
                    return;
                }
            }
            entry = entry.next;
        } while (isFlushedEntry(entry));
    }

    private boolean isFlushedEntry(Entry e) {
        return e != null && e != unflushedEntry;
    }

    public interface MessageProcessor {
        /**
         * Will be called for each flushed message until it either there are no more flushed messages or this
         * method returns {@code false}.
         */
        boolean processMessage(Object msg) throws Exception;
    }

    static final class Entry {
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        };

        private final Handle<Entry> handle;
        Entry next;
        Object msg;
        ByteBuffer[] bufs;
        ByteBuffer buf;
        ChannelPromise promise;
        long progress;
        long total;
        int pendingSize;
        int count = -1;
        boolean cancelled;

        private Entry(Handle<Entry> handle) {
            this.handle = handle;
        }
        // 尝试从 RECYCLER 中获取 Entry，然后加参数设置给 Entry
        static Entry newInstance(Object msg, int size, long total, ChannelPromise promise) {
            Entry entry = RECYCLER.get();   // 从回收器中获取 Entry
            entry.msg = msg;
            entry.pendingSize = size + CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD;
            entry.total = total;
            entry.promise = promise;
            return entry;
        }
        // 取消 entry，就是对相关的参数值进行归零，返回 pendingSize
        int cancel() {
            if (!cancelled) {
                cancelled = true;
                int pSize = pendingSize;

                // release message and replace with an empty buffer
                ReferenceCountUtil.safeRelease(msg);
                msg = Unpooled.EMPTY_BUFFER;

                pendingSize = 0;
                total = 0;
                progress = 0;
                bufs = null;
                buf = null;
                return pSize;
            }
            return 0;
        }

        void recycle() {
            next = null;
            bufs = null;
            buf = null;
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
            cancelled = false;
            handle.recycle(this);
        }

        Entry recycleAndGetNext() {
            Entry next = this.next;
            recycle();
            return next;
        }
    }
}
