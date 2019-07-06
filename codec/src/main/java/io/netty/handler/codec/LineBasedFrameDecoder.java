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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.  */
    private final int maxLength;
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    private final boolean failFast;
    private final boolean stripDelimiter;

    /** True if we're discarding input because we're already over maxLength.  */
    private boolean discarding;
    private int discardedBytes;

    /** Last scan position. */
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override   // 实现真正的解码逻辑，结果添加到 out 中
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);   // 真正的解码逻辑
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */ // 行解码过程，如果当前 buffer 够解析出一个数据包，就将这个数据包返回，否则看当前接收内容是否超出了最大包长度，如果超出了就进入丢弃模式，丢弃模式下也要看是否获
    // 取到行分隔符，如果获取到了，就以分隔符之后的内容作为新的起点，否则，直接丢弃这部分数据内容
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        final int eol = findEndOfLine(buffer);  // 从 byte buf 中找到第一个行分隔符的位置，可能是 \n 或者 \r\n 的情况
        if (!discarding) {  // 看是否是出于抛弃模式
            if (eol >= 0) { // 这里成立，说明找到了一行的分隔符
                final ByteBuf frame;
                final int length = eol - buffer.readerIndex();  // 这里可以确定分隔符之前的长度
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1; // 确定是 \r 还是 \r\n 的情况

                if (length > maxLength) {   // 看是否一行的长度超出了最大长度
                    buffer.readerIndex(eol + delimLength);  // 超出了，那么就直接将 read index 置于分隔符之后
                    fail(ctx, length);  // 触发 TooLongFrameException
                    return null;
                }

                if (stripDelimiter) {   // 看是否需要抛弃分隔符
                    frame = buffer.readRetainedSlice(length);   // 从当前 byte buf 读取出一个长度为 length 的 byte buf，修正当前 byte buf 的 reader index
                    buffer.skipBytes(delimLength);  // 跳过分隔符部分
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength);
                }
                return frame;   // frame 就是分隔符之前的部分
            } else {    // 这里说明没有找到一行的分隔符
                final int length = buffer.readableBytes();  // 可读长度
                if (length > maxLength) {   // 这里成立说明可读长度超过了长度限制，但是没有找到分隔符
                    discardedBytes = length;    // 那么就认定 buffer 中的内容都是要抛弃的
                    buffer.readerIndex(buffer.writerIndex());   // 设置 reader index
                    discarding = true;  // 启动抛弃模式
                    offset = 0;
                    if (failFast) { // 如果是设置了快速失败模式，那么这里就直接触发异常事件
                        fail(ctx, "over " + discardedBytes);    // 触发异常信息
                    }
                }
                return null;
            }
        } else {    // 执行到这里，说明处于抛弃模式
            if (eol >= 0) { // 若是在抛弃模式下找到了行分隔符
                final int length = discardedBytes + eol - buffer.readerIndex(); // 计算一共得抛弃多少字节
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1; // 分隔符长度
                buffer.readerIndex(eol + delimLength);  // 设置新的 reader index 位置为分隔符之后
                discardedBytes = 0; // discardedBytes 归零
                discarding = false; // 结束抛弃状态
                if (!failFast) {    // 触发异常事件
                    fail(ctx, length);
                }
            } else {
                discardedBytes += buffer.readableBytes();   // 在抛弃模式下还没找到分隔符，那么就继续累加要抛弃的字符数
                buffer.readerIndex(buffer.writerIndex());   // 直接将 read index 设置到 writer index 位置
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null;
        }
    }
    // 触发 TooLongFrameException
    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(    // 触发异常信息，就是说解码过程中发现数据包长度超出最大长度了
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */ // 从 byte buf 中找到第一个行分隔符的位置，可能是 \n 或者 \r\n 的情况
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();   // 可读长度
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {
            offset = 0; // 找到了，恢复 offset
            if (i > 0 && buffer.getByte(i - 1) == '\r') {   // 这里说明是 \r\n 的情况
                i--;
            }
        } else {
            offset = totalLength;   // 当前 byte buf 不能找到一行的结尾字符，所以先记录下偏移量，下次再过来 byte buf 时，就可以跳过已查找的部分
        }
        return i;   // 返回找到的 end of line 符号的位置
    }
}
