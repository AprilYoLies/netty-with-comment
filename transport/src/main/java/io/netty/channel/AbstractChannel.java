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
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    private static final ClosedChannelException ENSURE_OPEN_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ExtendedClosedChannelException(null), AbstractUnsafe.class, "ensureOpen(...)");
    private static final ClosedChannelException CLOSE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractUnsafe.class, "close(...)");
    private static final ClosedChannelException WRITE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ExtendedClosedChannelException(null), AbstractUnsafe.class, "write(...)");
    private static final ClosedChannelException FLUSH0_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ExtendedClosedChannelException(null), AbstractUnsafe.class, "flush0()");
    private static final NotYetConnectedException FLUSH0_NOT_YET_CONNECTED_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new NotYetConnectedException(), AbstractUnsafe.class, "flush0()");

    private final Channel parent;
    private final ChannelId id;
    private final Unsafe unsafe;
    private final DefaultChannelPipeline pipeline;
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false); // 持有了当前 NioSocketChannel 信息
    private final CloseFuture closeFuture = new CloseFuture(this);  // close 状态 promise

    private volatile SocketAddress localAddress;
    private volatile SocketAddress remoteAddress;
    private volatile EventLoop eventLoop;
    private volatile boolean registered;
    private boolean closeInitiated;
    private Throwable initialCloseCause;

    /**
     * Cache for the string representation of this channel
     */
    private boolean strValActive;
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param parent the parent of this channel. {@code null} if there's no parent.
     */ // 缓存了父 channel 信息，构建了 id，unsafe，pipeline 字段
    protected AbstractChannel(Channel parent) {
        this.parent = parent;   // 缓存了 NioSErverSocketChannel，也就是当前 NioSocketChannel 是由谁所接收构建的
        id = newId();   // id 信息
        unsafe = newUnsafe();   // 构建的是 NioByteUnsafe 实现类
        pipeline = newChannelPipeline();    // 构建 DefaultChannelPipeline，持有了 channel
    }

    /**
     * Creates a new instance.
     *
     * @param parent the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent, ChannelId id) {
        this.parent = parent;
        this.id = id;
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    @Override
    public final ChannelId id() {
        return id;
    }

    /**
     * Returns a new {@link DefaultChannelId} instance. Subclasses may override this method to assign custom
     * {@link ChannelId}s to {@link Channel}s that use the {@link AbstractChannel#AbstractChannel(Channel)} constructor.
     */
    protected ChannelId newId() {
        return DefaultChannelId.newInstance();
    }

    /**
     * Returns a new {@link DefaultChannelPipeline} instance.
     */ // 构建 DefaultChannelPipeline，持有了 channel
    protected DefaultChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this); // 这里说明 pipeline 持有了 channel
    }

    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    @Override
    public long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        EventLoop eventLoop = this.eventLoop;
        if (eventLoop == null) {
            throw new IllegalStateException("channel not registered to an event loop");
        }
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }

    @Override // channel 的 bind 操作也是从 pipeline 开始的
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        pipeline.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return pipeline.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return pipeline.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return pipeline.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline.newFailedFuture(cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Create a new {@link AbstractUnsafe} instance which will be used for the life-time of the {@link Channel}
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode() ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                    .append("[id: 0x")
                    .append(id.asShortText())
                    .append(", L:")
                    .append(localAddr)
                    .append(active ? " - " : " ! ")
                    .append("R:")
                    .append(remoteAddr)
                    .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                    .append("[id: 0x")
                    .append(id.asShortText())
                    .append(", L:")
                    .append(localAddr)
                    .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                    .append("[id: 0x")
                    .append(id.asShortText())
                    .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return pipeline.voidPromise();
    }

    /**
     * {@link Unsafe} implementation which sub-classes must extend and use.
     */
    protected abstract class AbstractUnsafe implements Unsafe {

        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        private RecvByteBufAllocator.Handle recvHandle;
        private boolean inFlush0;
        /**
         * true if the channel has never been registered, false otherwise
         */
        private boolean neverRegistered = true;

        private void assertEventLoop() {
            assert !registered || eventLoop.inEventLoop();
        }

        @Override // 获取 recvHandle，如果没有则进行创建
        public RecvByteBufAllocator.Handle recvBufAllocHandle() {
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator().newHandle();
            }
            return recvHandle;
        }

        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        @Override // 当前 channel 持有的 nio 原生 channel 向 selector 进行注册，触发当前 channel 对应的 pipeline 的 PendingHandlerCallback 链，完成 channelInit 方法的调用，然后触发 channel registry 事件
        public final void register(EventLoop eventLoop, final ChannelPromise promise) {
            if (eventLoop == null) {
                throw new NullPointerException("eventLoop");
            }
            if (isRegistered()) {
                promise.setFailure(new IllegalStateException("registered to an event loop already"));
                return;
            }
            if (!isCompatible(eventLoop)) {
                promise.setFailure(
                        new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName()));
                return;
            }
            // channel 的 unsafe 记录了注册的 event loop
            AbstractChannel.this.eventLoop = eventLoop;
            // 判断当前线程是否是 event loop 所代表的线程
            if (eventLoop.inEventLoop()) {
                register0(promise); // 当前 channel 持有的 nio 原生 channel 向 selector 进行注册，触发当前 channel 对应的 pipeline 的 PendingHandlerCallback 链，完成 channelInit 方法的调用，然后触发 channel registry 事件
            } else {
                try {
                    eventLoop.execute(new Runnable() {
                        @Override
                        public void run() {  // 当前 channel 持有的 nio 原生 channel 向 selector 进行注册，触发当前 channel 对应的 pipeline
                            register0(promise); // 的 PendingHandlerCallback 链，完成 channelInit 方法的调用，然后设置 result，同时完成对于 listener
                        }   // 的通知操作，最后触发 channel registry 事件
                    });
                } catch (Throwable t) {
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    closeForcibly();
                    closeFuture.setClosed();
                    safeSetFailure(promise, t);
                }
            }
        }
        // 当前 channel 持有的 nio 原生 channel 向 selector 进行注册，触发当前 channel 对应的 pipeline 的 PendingHandlerCallback 链，完成 channelInit 方法的调用，然后触发 channel registry 事件
        private void register0(ChannelPromise promise) {
            try {
                // check if the channel is still open as it could be closed in the mean time when the register
                // call was outside of the eventLoop
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return; // 如果 promise 的 result 是已完成状态（结果已被设置）或者已取消状态（结果是 CauseHolder）再或者是 channel 已关闭状态就直接返回
                }
                boolean firstRegistration = neverRegistered;
                doRegister();   // 这里就是 java 原生 channel 向 selector 注册的代码，0 代表对任意事件都不感兴趣，附件就是当前 NioServerSocketChannel
                neverRegistered = false;
                registered = true;

                // Ensure we call handlerAdded(...) before we actually notify the promise. This is needed as the
                // user may already fire events through the pipeline in the ChannelFutureListener.
                pipeline.invokeHandlerAddedIfNeeded();  // 根据 firstRegistration 状态来决定是否调用 PendingHandlerCallback 链的 execute 方法
                // 这里可以看出 channelRegistered 事件触发的流程 pipeline.fireChannelRegistered -> AbstractChannelHandlerContext.invokeChannelRegistered(head) -> head.invokeChannelRegistered() -> handler.channelRegistered(this) -> head.fireChannelRegistered()（下一个 handler context）
                safeSetSuccess(promise); // 通过 field updater 设置 result 字段，完成后通知注册的监听器
                pipeline.fireChannelRegistered();   // pipeline 触发 channel registered 事件，这里可以看出来，所有的事件消息都是沿着 pipeline 进行传播的
                // Only fire a channelActive if the channel has never been registered. This prevents firing
                // multiple channel actives if the channel is deregistered and re-registered.
                if (isActive()) { // 判断 nio 原生 channel 是否是激活状态
                    if (firstRegistration) {
                        pipeline.fireChannelActive();   // 触发 channel 激活事件，只有第一次注册时触发，否则调用读取方法
                    } else if (config().isAutoRead()) {
                        // This channel was registered before and autoRead() is set. This means we need to begin read
                        // again so that we process inbound data.
                        //
                        // See https://github.com/netty/netty/issues/4805
                        beginRead();    // 非第一次 registry 时调用
                    }
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                closeForcibly();
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }
        // 进行了一些验证，比如是否是 event loop 线程，promise 的状态是否正确，channel 是否激活态，然后进行绑定，最后还会提交一个 channel 激活的任务
        @Override // 该任务触发 channel active 事件，同时向 nio 原生 channel 注册了 read 事件感兴趣
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            assertEventLoop();  // 断言当前线程一定是 event loop 线程

            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return; // 这里是 promise 和 channel 的一些状态信息的判断
            }

            // See: https://github.com/netty/netty/issues/576
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                    localAddress instanceof InetSocketAddress && // channel 被设置为 SO_BROADCAST，但不是绑定的通配地址，同时是 linux 平台却不是超级用户
                    !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                    !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
                // Warn a user about the fact that a non-root user can't receive a
                // broadcast packet on *nix if the socket is bound on non-wildcard address.
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                                "is not bound to a wildcard address; binding to a non-wildcard " +
                                "address (" + localAddress + ") anyway as requested.");
            }

            boolean wasActive = isActive(); // 验证当前 channel 为激活状态
            try { // 调用 nio 的原生 channel 进行绑定
                doBind(localAddress);
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (!wasActive && isActive()) {
                invokeLater(new Runnable() {    // 提交一个 channel 激活的任务，所以这里是 netty 的第二个被触发的事件，第一个是 registry 事件
                    @Override
                    public void run() { // 主要是出发了 channel active 事件，同时设置了 nio 原生 channel 对 read 事件感兴趣
                        pipeline.fireChannelActive(); // 此事件会传递给 ServerBootstrapAcceptor 处理
                    }
                });
            }
            // 设置 promise 的结果状态
            safeSetSuccess(promise);
        }

        @Override
        public final void disconnect(final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable()) {
                return;
            }

            boolean wasActive = isActive();
            try {
                doDisconnect();
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (wasActive && !isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }

            safeSetSuccess(promise);
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        @Override
        public final void close(final ChannelPromise promise) {
            assertEventLoop();
            // 如果是初次调用，就设置一些 promise 的状态关闭 outboundBuffer，同时通过 pipeline 传播一些事件，否则就是直接或者间接（添加监听器）设置处理结果
            close(promise, CLOSE_CLOSED_CHANNEL_EXCEPTION, CLOSE_CLOSED_CHANNEL_EXCEPTION, false);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         */
        @UnstableApi
        public final void shutdownOutput(final ChannelPromise promise) {
            assertEventLoop();
            shutdownOutput(promise, null);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         *
         * @param cause The cause which may provide rational for the shutdown.
         */ // 同步或者异步的 执行关闭任务，设置处理的结果，最后移除 flushedEntry 的第一项，依次更新待发送字节数，释放对应的 entry 项，触发 ChannelOutputShutdownEvent
        private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
            if (!promise.setUncancellable()) {  // promise 还没被设置过结果
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                promise.setFailure(CLOSE_CLOSED_CHANNEL_EXCEPTION);
                return; // outboundBuffer 为空的处理，就是设置异常信息到 promise
            }
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

            final Throwable shutdownCause = cause == null ?
                    new ChannelOutputShutdownException("Channel output shutdown") :
                    new ChannelOutputShutdownException("Channel output shutdown", cause);
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the shutdown.
                            doShutdownOutput(); // 执行关闭任务
                            promise.setSuccess();   // 设置处理的结果
                        } catch (Throwable err) {
                            promise.setFailure(err);
                        } finally {
                            // Dispatch to the EventLoop
                            eventLoop().execute(new Runnable() {
                                @Override
                                public void run() {  // 移除 flushedEntry 的第一项，依次更新待发送字节数，释放对应的 entry 项，触发 ChannelOutputShutdownEvent
                                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    // Execute the shutdown.
                    doShutdownOutput(); // 同步的关闭处理
                    promise.setSuccess();
                } catch (Throwable err) {
                    promise.setFailure(err);
                } finally {  // 移除 flushedEntry 的第一项，依次更新待发送字节数，释放对应的 entry 项，触发 ChannelOutputShutdownEvent
                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                }
            }
        }
        // 移除 flushedEntry 的第一项，依次更新待发送字节数，释放对应的 entry 项，触发 ChannelOutputShutdownEvent
        private void closeOutboundBufferForShutdown(
                ChannelPipeline pipeline, ChannelOutboundBuffer buffer, Throwable cause) {
            buffer.failFlushed(cause, false);   // 获取 flushedEntry 单链表的第一个元素，不为空的情况下，将其从 flushedEntry 单链表移除，还需要确保它的状态是 cancelled
            buffer.close(cause, true);  // 依次更新待发送字节数，释放对应的 entry 项
            pipeline.fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE);   // 触发 ChannelOutputShutdownEvent
        }
        // 如果是初次调用，就设置一些 promise 的状态关闭 outboundBuffer，同时通过 pipeline 传播一些事件，否则就是直接或者间接（添加监听器）设置处理结果
        private void close(final ChannelPromise promise, final Throwable cause,
                           final ClosedChannelException closeCause, final boolean notify) {
            if (!promise.setUncancellable()) {
                return; // 执行到这里，说明 promise 的状态已经是被设置
            }

            if (closeInitiated) {   // 看 close 过程是否已经初始化过了
                if (closeFuture.isDone()) { // 如果 closeFuture 已经被设置，就修改 promise 状态
                    // Closed already.
                    safeSetSuccess(promise); // 设置 close 的过程已经完成
                } else if (!(promise instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise.
                    // This means close() was called before so we just register a listener and return
                    closeFuture.addListener(new ChannelFutureListener() {   // 这里就是向 closeFuture 添加监听器，来监听完成事件，以此来设置 promise 状态
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            promise.setSuccess();
                        }
                    });
                }
                return;
            }

            closeInitiated = true;

            final boolean wasActive = isActive();   // channel 的激活状态
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the close.
                            doClose0(promise);  // 主要就是对一些 promise 的状态的设置
                        } finally {
                            // Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                            invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    if (outboundBuffer != null) {
                                        // Fail all the queued messages
                                        outboundBuffer.failFlushed(cause, notify);  // 关闭 outboundBuffer（后边再细看）
                                        outboundBuffer.close(closeCause);
                                    }
                                    fireChannelInactiveAndDeregister(wasActive);    // 触发 channel 失效取消注册事件
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    // Close the channel and fail the queued messages in all cases.
                    doClose0(promise);  // 主要就是对一些 promise 的状态的设置
                } finally {
                    if (outboundBuffer != null) {
                        // Fail all the queued messages.
                        outboundBuffer.failFlushed(cause, notify);  // outboundBuffer 的处理
                        outboundBuffer.close(closeCause);
                    }
                }
                if (inFlush0) {
                    invokeLater(new Runnable() {
                        @Override
                        public void run() { // 注销事件，主要是修改状态，取消 selection key，pipeline 传播两种事件
                            fireChannelInactiveAndDeregister(wasActive);
                        }
                    });
                } else {    // 注销事件，主要是修改状态，取消 selection key，pipeline 传播两种事件
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }
        // 主要就是对一些 promise 的状态的设置
        private void doClose0(ChannelPromise promise) {
            try {
                doClose();  // 子类实现就是对一些 promise 状态的设置
                closeFuture.setClosed();    // 修改 closeFuture 状态
                safeSetSuccess(promise); // 设置 promise 的状态
            } catch (Throwable t) {
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }
        // 注销事件，主要是修改状态，取消 selection key，pipeline 传播两种事件
        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            deregister(voidPromise(), wasActive && !isActive());
        }

        @Override   // 设置相关状态，关闭原生 channel
        public final void closeForcibly() {
            assertEventLoop();

            try {   // 设置相关状态，关闭原生 channel
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        @Override   // 注销事件，主要是修改状态，取消 selection key，pipeline 传播两种事件
        public final void deregister(final ChannelPromise promise) {
            assertEventLoop();
            // 注销事件，主要是修改状态，取消 selection key，pipeline 传播两种事件
            deregister(promise, false);
        }
        // 注销事件，主要是修改状态，取消 selection key，pipeline 传播两种事件
        private void deregister(final ChannelPromise promise, final boolean fireChannelInactive) {
            if (!promise.setUncancellable()) {  // promise 还没被设置过
                return;
            }

            if (!registered) {  // 如果 channel 本来就没注册过，直接设置结果为成功
                safeSetSuccess(promise);
                return;
            }

            // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
            // we need to ensure we do the actual deregister operation later. This is needed as for example,
            // we may be in the ByteToMessageDecoder.callDecode(...) method and so still try to do processing in
            // the old EventLoop while the user already registered the Channel to a new EventLoop. Without delay,
            // the deregister operation this could lead to have a handler invoked by different EventLoop and so
            // threads.
            //
            // See:
            // https://github.com/netty/netty/issues/4435
            invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        doDeregister(); // 子类实现就是取消 selection key
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                    } finally {
                        if (fireChannelInactive) {
                            pipeline.fireChannelInactive(); // pipeline 传播对应的事件
                        }
                        // Some transports like local and AIO does not allow the deregistration of
                        // an open channel.  Their doDeregister() calls close(). Consequently,
                        // close() calls deregister() again - no need to fire channelUnregistered, so check
                        // if it was registered.
                        if (registered) {
                            registered = false; // 修改 channel 的状态
                            pipeline.fireChannelUnregistered(); // pipeline 再传播对应的事件
                        }
                        safeSetSuccess(promise);    // 设置处理的结果
                    }
                }
            });
        }

        @Override // 这个方法主要是 nio 原生 channel 注册了对读事件感兴趣
        public final void beginRead() {
            assertEventLoop(); // 断言当前线程就是 event loop 线程

            if (!isActive()) {
                return;
            }

            try {   // 这个方法实际就是注册了对读事件感兴趣
                doBeginRead();
            } catch (final Exception e) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() { // 如果这个过程出现异常，就通过 pipeline 触发一个异常事件在 pipeline 中进行传播
                        pipeline.fireExceptionCaught(e);
                    }
                });
                close(voidPromise());
            }
        }

        @Override   // 检查 outboundBuffer 的状态，对 msg 进行过滤，用 Entry 承载待发送 msg，然后将相关的变量指向创建的 entry，最后更新将要数据字段的值
        public final void write(Object msg, ChannelPromise promise) {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {   // 如果 head context 的 byte buffer 为空
                // If the outboundBuffer is null we know the channel was closed and so
                // need to fail the future right away. If it is not null the handling of the rest
                // will be done in flush0()
                // See https://github.com/netty/netty/issues/2362
                safeSetFailure(promise, newWriteException(initialCloseCause));  // 设置 promise 的状态为失败
                // release message now to prevent resource-leak
                ReferenceCountUtil.release(msg);    // 释放 byte buf
                return;
            }

            int size;
            try {   // 对 msg 进行过滤处理，byte buf 就需要保证它是 direct 的，如果也不是 FileRegion，抛出异常
                msg = filterOutboundMessage(msg);
                size = pipeline.estimatorHandle().size(msg);// 根据 msg 的类型，确定分配空间的大小值，借用到 channel 持有的 estimatorHandle
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                ReferenceCountUtil.release(msg);
                return;
            }
            // 就是用 Entry 承载待发送 msg，然后将相关的变量指向创建的 entry，最后更新将要数据字段的值
            outboundBuffer.addMessage(msg, size, promise);
        }
        // 检查 outboundBuffer 的状态，将 entry 从 unflushedEntry 单链表移到 flushedEntry 单链表,检查 outboundBuffer 的状态，判断 ChannelOutboundBuffer 是有 flushedEntry，如果有就从中提取出 byte buffer，然后将这些 byte buffer 通过 nio 原生 channel 写出去
        @Override
        public final void flush() {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {   // outboundBuffer 不能为空
                return;
            }
            // 这里差不多就是一个将 entry 从 unflushedEntry 单链表移到 flushedEntry 单链表的过程（只是修改了头的指向）
            outboundBuffer.addFlush();
            flush0();   // 检查 outboundBuffer 的状态，判断 ChannelOutboundBuffer 是有 flushedEntry，如果有就从中提取出 byte buffer，然后将这些 byte buffer 通过 nio 原生 channel 写出去
        }
        // 检查 outboundBuffer 的状态，判断 ChannelOutboundBuffer 是有 flushedEntry，如果有就从中提取出 byte buffer，然后将这些 byte buffer 通过 nio 原生 channel 写出去
        @SuppressWarnings("deprecation")
        protected void flush0() {
            if (inFlush0) { // 避免同时刷新
                // Avoid re-entrance
                return;
            }
            // 检查 outboundBuffer 的状态
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                return;
            }
            // 设置 flush 的状态，避免同时刷新
            inFlush0 = true;

            // Mark all pending write requests as failure if the channel is inactive.
            if (!isActive()) {  // 确保 channel 是激活的
                try {
                    if (isOpen()) { // 打开非激活的异常触发方式
                        outboundBuffer.failFlushed(FLUSH0_NOT_YET_CONNECTED_EXCEPTION, true); // 获取 flushedEntry 单链表的第一个元素，不为空的情况下，将其从 flushedEntry 单链表移除，还需要确保它的状态是 cancelled
                    } else {    // 关闭非激活的异常处理方式
                        // Do not trigger channelWritabilityChanged because the channel is closed already.
                        outboundBuffer.failFlushed(newFlush0Exception(initialCloseCause), false);
                    }   // 获取 flushedEntry 单链表的第一个元素，不为空的情况下，将其从 flushedEntry 单链表移除，还需要确保它的状态是 cancelled
                } finally {
                    inFlush0 = false;
                }
                return;
            }

            try {   // 这里就是判断 ChannelOutboundBuffer 是有 flushedEntry，如果有就从中提取出 byte buffer，然后将这些 byte buffer 通过 nio 原生 channel 写出去
                doWrite(outboundBuffer);
            } catch (Throwable t) {
                if (t instanceof IOException && config().isAutoClose()) {   // 捕获到 IO 异常，且配置了自动关闭
                    /**
                     * Just call {@link #close(ChannelPromise, Throwable, boolean)} here which will take care of
                     * failing all flushed messages and also ensure the actual close of the underlying transport
                     * will happen before the promises are notified.
                     *
                     * This is needed as otherwise {@link #isActive()} , {@link #isOpen()} and {@link #isWritable()}
                     * may still return {@code true} even if the channel should be closed as result of the exception.
                     */
                    initialCloseCause = t;  // 如果是初次调用，就设置一些 promise 的状态关闭 outboundBuffer，同时通过 pipeline 传播一些事件，否则就是直接或者间接（添加监听器）设置处理结果
                    close(voidPromise(), t, newFlush0Exception(t), false);
                } else {
                    try {   // 同步或者异步的 执行关闭任务，设置处理的结果，最后移除 flushedEntry 的第一项，依次更新待发送字节数，释放对应的 entry 项，触发 ChannelOutputShutdownEvent
                        shutdownOutput(voidPromise(), t);
                    } catch (Throwable t2) {
                        initialCloseCause = t;  // 如果是初次调用，就设置一些 promise 的状态关闭 outboundBuffer，同时通过 pipeline 传播一些事件，否则就是直接或者间接（添加监听器）设置处理结果
                        close(voidPromise(), t2, newFlush0Exception(t), false);
                    }
                }
            } finally {
                inFlush0 = false;
            }
        }

        private ClosedChannelException newWriteException(Throwable cause) {
            if (cause == null) {
                return WRITE_CLOSED_CHANNEL_EXCEPTION;
            }
            return ThrowableUtil.unknownStackTrace(
                    new ExtendedClosedChannelException(cause), AbstractUnsafe.class, "write(...)");
        }

        private ClosedChannelException newFlush0Exception(Throwable cause) {
            if (cause == null) {
                return FLUSH0_CLOSED_CHANNEL_EXCEPTION;
            }
            return ThrowableUtil.unknownStackTrace(
                    new ExtendedClosedChannelException(cause), AbstractUnsafe.class, "flush0()");
        }

        private ClosedChannelException newEnsureOpenException(Throwable cause) {
            if (cause == null) {
                return ENSURE_OPEN_CLOSED_CHANNEL_EXCEPTION;
            }
            return ThrowableUtil.unknownStackTrace(
                    new ExtendedClosedChannelException(cause), AbstractUnsafe.class, "ensureOpen(...)");
        }

        @Override
        public final ChannelPromise voidPromise() {
            assertEventLoop();

            return unsafeVoidPromise;
        }

        // 判断 channel 是否是打开状态，否则设置 promise 状态
        protected final boolean ensureOpen(ChannelPromise promise) {
            if (isOpen()) {
                return true;
            }

            safeSetFailure(promise, newEnsureOpenException(initialCloseCause));
            return false;
        }

        /**
         * Marks the specified {@code promise} as success.  If the {@code promise} is done already, log a message.
         */ // promise.trySuccess 方法通过 field updater 设置 result 字段，完成后通知注册的监听器
        protected final void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        /**
         * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
         */ // 设置 promise 的状态为失败
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }
        // 如果 channel 的状态不是 open，那么需要
        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }
        // 这里就是将 task 提交到 eventLoop 中去执行，也就是当前线程
        private void invokeLater(Runnable task) {
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //      -> channel.unsafe.close()
                //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        /**
         * Appends the remote address to the message of the exceptions caused by connection attempt failure.
         */
        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
            }
            if (cause instanceof NoRouteToHostException) {
                return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
            }
            if (cause instanceof SocketException) {
                return new AnnotatedSocketException((SocketException) cause, remoteAddress);
            }

            return cause;
        }

        /**
         * Prepares to close the {@link Channel}. If this method returns an {@link Executor}, the
         * caller must call the {@link Executor#execute(Runnable)} method with a task that calls
         * {@link #doClose()} on the returned {@link Executor}. If this method returns {@code null},
         * {@link #doClose()} must be called from the caller thread. (i.e. {@link EventLoop})
         */
        protected Executor prepareToClose() {
            return null;
        }
    }

    /**
     * Return {@code true} if the given {@link EventLoop} is compatible with this instance.
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Is called after the {@link Channel} is registered with its {@link EventLoop} as part of the register process.
     * <p>
     * Sub-classes may override this method
     */
    protected void doRegister() throws Exception {
        // NOOP
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Called when conditions justify shutting down the output portion of the channel. This may happen if a write
     * operation throws an exception.
     */
    @UnstableApi
    protected void doShutdownOutput() throws Exception {
        doClose();
    }

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     * <p>
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * Schedule a read operation.
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    protected void validateFileRegion(DefaultFileRegion region, long position) throws IOException {
        DefaultFileRegion.validate(region, position);
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
            setStackTrace(exception.getStackTrace());
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
            setStackTrace(exception.getStackTrace());
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
            setStackTrace(exception.getStackTrace());
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
