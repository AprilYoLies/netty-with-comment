package com.phei.netty.codec.msgpack;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;

public class EchoClientHandler extends ChannelHandlerAdapter {
	private int count;

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		/*
		 * User user = getUser(); ctx.writeAndFlush(user);
		 */
		User[] users = getUsers();
		for (User u : users) {
			ctx.write(u);
		}
		ctx.flush();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		System.out.println("client receive msg[  " + count++ + "  ]times:[" + msg + "]");
//		if (count < 5) { // 控制运行次数，因为不加这个控制直接调用下面代码的话，客户端和服务端会形成闭环循环，一直运行
//			ctx.write(msg);
//		}

//		ByteBuf buf = (ByteBuf) msg;
//		byte[] req = new byte[buf.readableBytes()];
//		buf.readBytes(req);
//		String body = new String(req, "UTF-8");
//		System.out.println(body);
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		ctx.close();
	}

	private User[] getUsers() {
		User[] users = new User[1];
		for (int i = 0; i < 1; i++) {
			User user = new User();
			user.setId(String.valueOf(i));
			user.setAge(18 + i);
			user.setName("张元" + i);
			user.setSex("男" + String.valueOf(i * 2));
			users[i] = user;
		}
		return users;
	}

}