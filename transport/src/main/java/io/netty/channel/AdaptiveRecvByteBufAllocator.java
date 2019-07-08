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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    static final int DEFAULT_INITIAL = 1024;
    static final int DEFAULT_MAXIMUM = 65536;

    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1;

    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated // 根据 minimum 和 maximum 缓存对应的 SIZE_TABLE 索引，另外缓存 initial
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();
    // SIZE_TABLE 从 16 到 512 每次增加 16 逐次递增，之后翻倍增加，这里就是一个从 SIZE_TABLE 获取满足 size 需求，空间最小的 SIZE_TABLE 的索引
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {   // low 大于 high 返回 low
                return low;
            }
            if (high == low) {  // low 等于 high 返回 high
                return high;
            }

            int mid = low + high >>> 1; // 这是求平均数
            int a = SIZE_TABLE[mid];    // 获取中位数对应的 SIZE_TABLE 值
            int b = SIZE_TABLE[mid + 1];    // 比中位数大一的 SIZE_TABLE 值
            if (size > b) { // 情况一
                low = mid + 1;  // 取较大索引
            } else if (size < a) {  // 情况二
                high = mid - 1; // 取较小索引
            } else if (size == a) { // 等于中位，取中
                return mid;
            } else {    // 处在中间取较大索引
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;  // 即 initial 大小对应的 SIZE_TABLE 索引值
        private boolean decreaseNow;
        // 缓存了 int minIndex, int maxIndex，同时根据 initial 大小求得对应的 SIZE_TABLE 索引值并缓存
        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;
            // SIZE_TABLE 从 16 到 512 每次增加 16 逐次递增，之后翻倍增加，这里就是一个从 SIZE_TABLE 获取满足 size 需求，空间最小的 SIZE_TABLE 的索引
            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override   // 获取 handler 实例的 nextReceiveBufferSize 的值
        public int guess() {
            return nextReceiveBufferSize;
        }

        private void record(int actualReadBytes) {
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT - 1)]) {
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */ // 根据 minimum 和 maximum 缓存对应的 SIZE_TABLE 索引，另外缓存 initial
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);    // 根据 minimum 和 maximum 缓存对应的 SIZE_TABLE 索引，另外缓存 initial
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */ // 根据 minimum 和 maximum 缓存对应的 SIZE_TABLE 索引，另外缓存 initial
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");  // 检查参数为正值
        if (initial < minimum) {    // 初始值必须大于最小值
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {    // 初始值必须小于等于最大值
            throw new IllegalArgumentException("maximum: " + maximum);
        }
        // SIZE_TABLE 从 16 到 512 每次增加 16 逐次递增，之后翻倍增加，这里就是一个从 SIZE_TABLE 获取满足 size 需求，空间最小的 SIZE_TABLE 的索引
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {   // 确定 minIndex 的值
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;   // 确定 minIndex 的值
        }
        // SIZE_TABLE 从 16 到 512 每次增加 16 逐次递增，之后翻倍增加，这里就是一个从 SIZE_TABLE 获取满足 size 需求，空间最小的 SIZE_TABLE 的索引
        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;   // 确定 maxIndex 的值
        } else {
            this.maxIndex = maxIndex;   // 确定 maxIndex 的值
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override   // 根据 minIndex, maxIndex, initial 构建 HandleImpl（缓存了 int minIndex, int maxIndex，同时根据 initial 大小求得对应的 SIZE_TABLE 索引值并缓存）
    public Handle newHandle() { // 缓存了 int minIndex, int maxIndex，同时根据 initial 大小求得对应的 SIZE_TABLE 索引值并缓存
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
