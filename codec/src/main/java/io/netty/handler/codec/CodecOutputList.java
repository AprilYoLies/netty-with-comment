/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.MathUtil;

import java.util.AbstractList;
import java.util.RandomAccess;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Special {@link AbstractList} implementation which is used within our codec base classes.
 */
final class CodecOutputList extends AbstractList<Object> implements RandomAccess {

    private static final CodecOutputListRecycler NOOP_RECYCLER = new CodecOutputListRecycler() {
        @Override
        public void recycle(CodecOutputList object) {
            // drop on the floor and let the GC handle it.
        }
    };

    private static final FastThreadLocal<CodecOutputLists> CODEC_OUTPUT_LISTS_POOL =
            new FastThreadLocal<CodecOutputLists>() {
                @Override
                protected CodecOutputLists initialValue() throws Exception {
                    // 16 CodecOutputList per Thread are cached.
                    return new CodecOutputLists(16);
                }
            };

    private interface CodecOutputListRecycler {
        void recycle(CodecOutputList codecOutputList);
    }

    private static final class CodecOutputLists implements CodecOutputListRecycler {
        private final CodecOutputList[] elements;
        private final int mask;

        private int currentIdx;
        private int count;

        CodecOutputLists(int numElements) { // 构建 CodecOutputList 数组，大小为大于等于 numElements 的最小的 2 的幂级数
            elements = new CodecOutputList[MathUtil.safeFindNextPositivePowerOfTwo(numElements)];
            for (int i = 0; i < elements.length; ++i) {
                // Size of 16 should be good enough for the majority of all users as an initial capacity.
                elements[i] = new CodecOutputList(this, 16);    // 创建 CodecOutputList，它持有了 CodecOutputListRecycler，容量为 16
            }
            count = elements.length;
            currentIdx = elements.length;
            mask = elements.length - 1;
        }
        // 从预创建的 16 个 CodecOutputList 中获取一个，或者创建不会被回收的容量为 4 的 CodecOutputList(NOOP_RECYCLER, 4)
        public CodecOutputList getOrCreate() {
            if (count == 0) {
                // Return a new CodecOutputList which will not be cached. We use a size of 4 to keep the overhead
                // low. // 如果预创建的 16 个 CodecOutputList 分配完了，接下来就是新建 CodecOutputList(NOOP_RECYCLER, 4)，容量为 4，不会缓存，不会被回收
                return new CodecOutputList(NOOP_RECYCLER, 4);
            }
            --count;

            int idx = (currentIdx - 1) & mask;
            CodecOutputList list = elements[idx];
            currentIdx = idx;
            return list;
        }

        @Override
        public void recycle(CodecOutputList codecOutputList) {
            int idx = currentIdx;
            elements[idx] = codecOutputList;
            currentIdx = (idx + 1) & mask;
            ++count;
            assert count <= elements.length;
        }
    }
    // 从线程本地变量中获取 CodecOutputLists，然后从中获取 CodecOutputList
    static CodecOutputList newInstance() { // get 方法从线程本地变量中获取 CodecOutputLists
        return CODEC_OUTPUT_LISTS_POOL.get().getOrCreate(); // getOrCreate 从预创建的 16 个 CodecOutputList 中获取一个，或者创建不会被回收的容量为 4 的 CodecOutputList(NOOP_RECYCLER, 4)
    }

    private final CodecOutputListRecycler recycler;
    private int size;
    private Object[] array;
    private boolean insertSinceRecycled;

    private CodecOutputList(CodecOutputListRecycler recycler, int size) {
        this.recycler = recycler;
        array = new Object[size];
    }

    @Override
    public Object get(int index) {
        checkIndex(index);
        return array[index];
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean add(Object element) {
        checkNotNull(element, "element");
        try {
            insert(size, element);
        } catch (IndexOutOfBoundsException ignore) {
            // This should happen very infrequently so we just catch the exception and try again.
            expandArray();
            insert(size, element);
        }
        ++ size;
        return true;
    }

    @Override
    public Object set(int index, Object element) {
        checkNotNull(element, "element");
        checkIndex(index);

        Object old = array[index];
        insert(index, element);
        return old;
    }

    @Override
    public void add(int index, Object element) {
        checkNotNull(element, "element");
        checkIndex(index);

        if (size == array.length) {
            expandArray();
        }

        if (index != size) {
            System.arraycopy(array, index, array, index + 1, size - index);
        }

        insert(index, element);
        ++ size;
    }

    @Override
    public Object remove(int index) {
        checkIndex(index);
        Object old = array[index];

        int len = size - index - 1;
        if (len > 0) {
            System.arraycopy(array, index + 1, array, index, len);
        }
        array[-- size] = null;

        return old;
    }

    @Override
    public void clear() {
        // We only set the size to 0 and not null out the array. Null out the array will explicit requested by
        // calling recycle()
        size = 0;
    }

    /**
     * Returns {@code true} if any elements where added or set. This will be reset once {@link #recycle()} was called.
     */
    boolean insertSinceRecycled() {
        return insertSinceRecycled;
    }

    /**
     * Recycle the array which will clear it and null out all entries in the internal storage.
     */
    void recycle() {
        for (int i = 0 ; i < size; i ++) {
            array[i] = null;
        }
        size = 0;
        insertSinceRecycled = false;

        recycler.recycle(this);
    }

    /**
     * Returns the element on the given index. This operation will not do any range-checks and so is considered unsafe.
     */
    Object getUnsafe(int index) {
        return array[index];
    }

    private void checkIndex(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void insert(int index, Object element) {
        array[index] = element;
        insertSinceRecycled = true;
    }

    private void expandArray() {
        // double capacity
        int newCapacity = array.length << 1;

        if (newCapacity < 0) {
            throw new OutOfMemoryError();
        }

        Object[] newArray = new Object[newCapacity];
        System.arraycopy(array, 0, newArray, 0, array.length);

        array = newArray;
    }
}
