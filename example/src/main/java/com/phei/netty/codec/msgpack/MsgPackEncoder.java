package com.phei.netty.codec.msgpack;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.msgpack.MessagePack;

/**
 * 注意这里的类型参数，如果服务器端配置了编码器，则应该修改为Object.
 * 
 * @author eva
 *
 */
public class MsgPackEncoder extends MessageToByteEncoder<Object> {
	@Override
	protected void encode(ChannelHandlerContext channelHandlerContext, Object o, ByteBuf byteBuf) throws Exception {
		MessagePack msgPack = new MessagePack();
//      编码，然后转为ButyBuf传递
		byte[] bytes = msgPack.write(o);
		byteBuf.writeBytes(bytes);
	}
}
