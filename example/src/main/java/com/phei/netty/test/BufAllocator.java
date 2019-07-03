package com.phei.netty.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

public class BufAllocator {
	public static void main(String[] args) {
		PooledByteBufAllocator alct = new PooledByteBufAllocator();
		ByteBuf buf = alct.directBuffer();
	}
}
