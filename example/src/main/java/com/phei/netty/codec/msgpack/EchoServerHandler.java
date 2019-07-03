package com.phei.netty.codec.msgpack;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;

public class EchoServerHandler extends ChannelHandlerAdapter {
	int count;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		System.out.println("server receive the msgpack message : " + msg + "");
		// 原路返回给客户端
//		ByteBuf buf = Unpooled.copiedBuffer("the response from server".getBytes());
		ctx.writeAndFlush(msg);
		/*
		 * 在EchoClientHandler中向服务端发送一个pojo对象，经过MessagePack编解码后，
		 * 在EchoServerHandler中的channelRead方法中打印的msg为pojo对象的toString方法内容，
		 * 不可以直接将msg转换为User,如果采用如下代码运行不成功
		 */

//		System.out.println("-------");
//		User user = (User) msg;
//		System.out.println("+++++++");
//		System.out.println("This is" + ++count + " times server receive client request." + user.getName());
//		ctx.write(user);
//		ctx.flush();

	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		ctx.close();
	}
}
