package top.aprilyolies.example.bufalloc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;

/**
 * @Author EvaJohnson
 * @Date 2019-08-28
 * @Email g863821569@gmail.com
 */
public class BufAllocDemo {
    public static void main(String[] args) {
        PooledByteBufAllocator pooled = new PooledByteBufAllocator();
        pooled.heapBuffer();
        UnpooledByteBufAllocator unPooled = new UnpooledByteBufAllocator(true);
        unPooled.heapBuffer();
    }
}
