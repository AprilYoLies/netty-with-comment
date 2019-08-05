package top.aprilyolies.example;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.Test;

/**
 * @Author EvaJohnson
 * @Date 2019-08-04
 * @Email g863821569@gmail.com
 */
public class CommonTest {
    @Test
    public void pooledByteBufAllocatorTest() {
        int pageSize = 8192;
        PooledByteBufAllocator allocator = new PooledByteBufAllocator();
        ByteBuf byteBuf = allocator.heapBuffer();
    }
}
