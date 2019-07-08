/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    private static final ClosedChannelException DO_CLOSE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractNioChannel.class, "doClose()");

    private final SelectableChannel ch;
    protected final int readInterestOp;
    volatile SelectionKey selectionKey;
    boolean readPending;
    private final Runnable clearReadPendingRunnable = new Runnable() {
        @Override
        public void run() {
            clearReadPending0();
        }
    };

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     */
    private ChannelPromise connectPromise;
    private ScheduledFuture<?> connectTimeoutFuture;
    private SocketAddress requestedRemoteAddress;

    /**
     * Create a new instance
     *
     * @param parent         the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch             the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp the ops to set to receive data from the {@link SelectableChannel}
     */ // 缓存了父 channel 信息，构建了 id，unsafe，pipeline，ch，readInterestOp 字段,同时设置 ch 为非阻塞的
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent);  // 缓存了父 channel 信息，构建了 id，unsafe，pipeline 字段
        this.ch = ch; // 缓存原生 channel
        this.readInterestOp = readInterestOp; // 指定感兴趣的事件
        try {
            ch.configureBlocking(false); // 根据 nio 的使用方式，需要将 ch 设置为非阻塞的
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException e2) {
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            "Failed to close a partially initialized socket.", e2);
                }
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * @deprecated No longer supported.
     * No longer supported.
     */
    @Deprecated
    protected boolean isReadPending() {
        return readPending;
    }

    /**
     * @deprecated Use {@link #clearReadPending()} if appropriate instead.
     * No longer supported.
     */
    @Deprecated
    protected void setReadPending(final boolean readPending) {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                setReadPending0(readPending);
            } else {
                eventLoop.execute(new Runnable() {
                    @Override
                    public void run() {
                        setReadPending0(readPending);
                    }
                });
            }
        } else {
            // Best effort if we are not registered yet clear readPending.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            this.readPending = readPending;
        }
    }

    /**
     * Set read pending to {@code false}.
     */ // 清除 readPending 标志
    protected final void clearReadPending() {
        if (isRegistered()) {   // 根据是否注册的状态不同，清除 readPending 标志的方式也不同
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                clearReadPending0();
            } else {
                eventLoop.execute(clearReadPendingRunnable);
            }
        } else {
            // Best effort if we are not registered yet clear readPending. This happens during channel initialization.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            readPending = false;
        }
    }

    private void setReadPending0(boolean readPending) {
        this.readPending = readPending;
        if (!readPending) {
            ((AbstractNioUnsafe) unsafe()).removeReadOp();
        }
    }

    private void clearReadPending0() {
        readPending = false;
        ((AbstractNioUnsafe) unsafe()).removeReadOp();
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch();

        /**
         * Finish connect
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read();

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {
        // 移除 selectionKey 的 read 感兴趣事件
        protected final void removeReadOp() {
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            if (!key.isValid()) {   // 失效的 SelectionKey 不进行处理
                return;
            }
            int interestOps = key.interestOps();
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed // 移除 selectionKey 的 read 感兴趣事件
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        @Override
        public final SelectableChannel ch() {
            return javaChannel();
        }
        // 根据条件决定是否绑定本地 address，然后进行 nio 原生 channel 连接远端地址，如果连接的结果是进行中，那么就对和 connect 相关的 promise 进行缓存设置，添加监听器
        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {  // 第一个条件保证 promise 还没被处理过，第二个条件判断 channel 是否是打开状态，否则设置 promise 状态
                return;
            }

            try {
                if (connectPromise != null) {   // 这里说明已有其他任务进行连接了，避免重复操作，抛出异常
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }
                // 确保 nio channel 是打开且连接状态
                boolean wasActive = isActive();
                if (doConnect(remoteAddress, localAddress)) {   // 根据条件决定是否绑定本地 address，然后进行 nio 原生 channel 连接远端地址的过程
                    fulfillConnectPromise(promise, wasActive);  // 根据 channel 的状态，对相关的 promise 进行信息的设置，然后根据条件触发 ChannelActive 事件
                } else {
                    connectPromise = promise;   // 记录 promise，也可通过这个字段知道已经有任务进行了连接操作
                    requestedRemoteAddress = remoteAddress; // 记录远端地址

                    // Schedule connect timeout.
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();  // 获取连接超时时间
                    if (connectTimeoutMillis > 0) {
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                ConnectTimeoutException cause =
                                        new ConnectTimeoutException("connection timed out: " + remoteAddress);
                                if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }
                    // 可以看到这里就是监听器监听 connectPromise 的结果，然后根据此结果设置 connectTimeoutFuture 的状态（用于取消这个任务）
                    promise.addListener(new ChannelFutureListener() {   // 为 channel promise 设置监听器，用于监听连接的结果
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (future.isCancelled()) { // 如果连接的结果是被取消，那么就取消 connectTimeoutFuture 任务
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false); // 设置 connectTimeoutFuture 的状态
                                }
                                connectPromise = null;
                                close(voidPromise());   // 如果是初次调用，就设置一些 promise 的状态关闭 outboundBuffer，同时通过 pipeline 传播一些事件，否则就是直接或者间接（添加监听器）设置处理结果
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }
        // 根据 channel 的状态，对相关的 promise 进行信息的设置，然后根据条件触发 ChannelActive 事件
        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive();    // 查看 nio 原生 channel 是否是激活状态

            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess();  // 通过 field updater 设置 result 字段，完成后通知注册的监听器

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) {
                pipeline().fireChannelActive(); // 如果状态从未激活变为了激活太，触发 ChannelActive 事件，还注册了对 read 事件感兴趣
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {  // promise trySuccess 返回 false 情况下成立（即设置结果失败的情况）
                close(voidPromise());   // 如果是初次调用，就设置一些 promise 的状态关闭 outboundBuffer，同时通过 pipeline 传播一些事件，否则就是直接或者间接（添加监听器）设置处理结果
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(cause);
            closeIfClosed();
        }

        @Override   // 主要就是验证连接完成性，完成 promise 的状态设置，触发 ChannelActive 事件，设置当前 connectTimeoutFuture 的状态，从 queue 中移除 node 对应的节点
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.

            assert eventLoop().inEventLoop();

            try {
                boolean wasActive = isActive();
                doFinishConnect();  // 验证 nio channel 确实是完成连接了，当且仅当 channel 完成连接返回 true
                fulfillConnectPromise(connectPromise, wasActive);   // 根据 channel 的状态，对相关的 promise 进行信息的设置，然后根据条件触发 ChannelActive 事件
            } catch (Throwable t) {
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                if (connectTimeoutFuture != null) { // 因为完成 connect 了，所以取消掉 connectTimeoutFuture
                    connectTimeoutFuture.cancel(false); // 设置当前 future 的状态，从 queue 中移除 node 对应的节点
                }
                connectPromise = null;
            }
        }

        @Override   // 检查 outboundBuffer 的状态，判断 ChannelOutboundBuffer 是有 flushedEntry，如果有就从中提取出 byte buffer，然后将这些 byte buffer 通过 nio 原生 channel 写出去
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            if (!isFlushPending()) {    // 检查 selectionKey 有效，且对 OP_WRITE 感兴趣
                super.flush0(); // 检查 outboundBuffer 的状态，判断 ChannelOutboundBuffer 是有 flushedEntry，如果有就从中提取出 byte buffer，然后将这些 byte buffer 通过 nio 原生 channel 写出去
            }
        }

        @Override
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }
        // 检查 selectionKey 有效，且对 OP_WRITE 感兴趣
        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    @Override
    protected void doRegister() throws Exception {
        boolean selected = false;
        for (; ; ) {
            try {   // 这里就是 java 原生 channel 向 selector 注册的代码，0 代表对任意事件都不感兴趣，附件就是当前 NioServerSocketChannel
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
                return;
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    eventLoop().selectNow(); // 这里如果注册过程捕获到 CancelledKeyException，就需要调用一次 select 方法来移除已经取消的 selection key
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e; // 这里获取时 jdk bug，我们也不知道该如何处理，所以直接抛出，终止程序
                }
            }
        }
    }

    @Override
    protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey());
    }

    @Override   // 所以这个方法实际就是注册了对读事件感兴趣
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
        final SelectionKey selectionKey = this.selectionKey; // 此 selectionKey 就是 nio 远程 channel 注册 selector 返回的
        if (!selectionKey.isValid()) {
            return;
        }

        readPending = true;

        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {  // 注册对 read 事件感兴趣
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     */ // 主要是为了将 buf 的内容填充到直接内存区域的 byte buf 中
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes();  // 可读大小
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            return Unpooled.EMPTY_BUFFER;   // 长度为 0，直接返回 EMPTY_BUFFER
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) { // 如果 alloc 是池化的直接内存区 buffer
            ByteBuf directBuf = alloc.directBuffer(readableBytes);  // 直接分配指定长度的 byte buf
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);    // 将内容填充进去
            ReferenceCountUtil.safeRelease(buf);    // 释放原 byte buf
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();    // 如果 channel 持有的 alloc 不是直接内存区相关的，尝试从线程本地中获取
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);    // 将数据填充后返回
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    @Override   // 针对 connectPromise 和 connectTimeoutFuture 的状态的一些设置
    protected void doClose() throws Exception {
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(DO_CLOSE_CLOSED_CHANNEL_EXCEPTION);  // 设置 promise 的状态为失败
            connectPromise = null;
        }

        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
