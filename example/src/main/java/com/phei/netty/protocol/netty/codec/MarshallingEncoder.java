/*
 * Copyright 2013-2018 Lilinfeng.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.phei.netty.protocol.netty.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;

import java.io.IOException;

import org.jboss.marshalling.Marshaller;

/**
 * @author Lilinfeng
 * @date 2014年3月14日
 * @version 1.0
 */
@Sharable
public class MarshallingEncoder {

	private static final byte[] LENGTH_PLACEHOLDER = new byte[4];
	Marshaller marshaller;

	public MarshallingEncoder() throws IOException {
		marshaller = MarshallingCodecFactory.buildMarshalling();
	}

	/**
	 * 这里就是给ByteBuf写入对象的长度和对象经过Marshelling后的字节，先获取当前写入索引，然后用一个LENGTH_PLACEHOLDER占据长度位置，
	 * 再将对象写入，这时ByteBuf.writerIndex() - lengthPos - 4就得到了对象Marshelling后的长度，将此结果写入LENGTH_PLACEHOLDER的位置即可。
	 * 
	 * @param msg
	 *            待写入的对象
	 * @param out
	 *            目标ByteBuf
	 * @throws Exception
	 */
	protected void encode(Object msg, ByteBuf out) throws Exception {
		try {
			int lengthPos = out.writerIndex();
			out.writeBytes(LENGTH_PLACEHOLDER);
			ChannelBufferByteOutput output = new ChannelBufferByteOutput(out);
			marshaller.start(output);
			marshaller.writeObject(msg);
			marshaller.finish();
			out.setInt(lengthPos, out.writerIndex() - lengthPos - 4);
		} finally {
			marshaller.close();
		}
	}
}
