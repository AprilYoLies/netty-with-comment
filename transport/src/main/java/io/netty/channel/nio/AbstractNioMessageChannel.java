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

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override // 这个方法实际就是注册了对读事件感兴趣
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }   // 这个方法实际就是注册了对读事件感兴趣
        super.doBeginRead();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        private final List<Object> readBuf = new ArrayList<Object>();
        // 对于 NioServerSocketChannel 而言，该方法就是 nio 原生 channel accept 得到 SocketChannel，封装为 NioSocketChannel，然后 pipeline 触发 channelRead 和 channelReadComplete 事件
        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            final ChannelConfig config = config();  // config
            final ChannelPipeline pipeline = pipeline();    // pipeline
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();  // 获取 recvHandle，如果没有则进行创建
            allocHandle.reset(config);  // 缓存 config，恢复 maxMessagePerRead，totalMessages，totalBytesRead 参数

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {    // 差不多就是得到 SocketChannel ，封装成 NioSocketChannel 返回
                        int localRead = doReadMessages(readBuf);
                        if (localRead == 0) {
                            break;
                        }
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }
                        // 增加计数 totalMessages
                        allocHandle.incMessagesRead(localRead);
                    } while (allocHandle.continueReading());
                } catch (Throwable t) {
                    exception = t;
                }

                int size = readBuf.size();
                for (int i = 0; i < size; i ++) {
                    readPending = false;    // 对于读取的每一个 Object，都触发一次 channel read 事件
                    pipeline.fireChannelRead(readBuf.get(i));   // 如果是 NioServerSocketChannel 对应的 pipeline，那么其中的 ServerBootstrapAcceptor 会对
                }   // 新 NioSocketChannel 进行注册及相关 option 的设置
                readBuf.clear();
                allocHandle.readComplete(); // 和 bytebuf 相关的一些操作，暂不做深入了解
                pipeline.fireChannelReadComplete(); // 触发 ChannelReadComplete 事件，没做其他事情，就是出发了 channel read，又让 NioServerSocketChannel 感兴趣了 read 事件

                if (exception != null) {    // 处理发生了异常的情况
                    closed = closeOnReadError(exception);   // 判断是否是由于 channel 关闭导致的异常

                    pipeline.fireExceptionCaught(exception);    // 触发捕获到异常的事件
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) { // channel 是打开状态
                        close(voidPromise());   // 设置 close 的结果到 promise 中
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp(); // 移除 selectionKey 的 read 感兴趣事件，就是 selectionKey 的位运算
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }
    // 判断是否是由于 channel 关闭导致的异常
    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {  // 关闭的情况是符合条件的
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;   // 端口不可达是不符合的
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);    // 当前实例不是 ServerChannel 也是符合的
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
