package com.imooc.netty.ch9;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author
 */
public class BizHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //...
        System.out.println("channelRead in BizHandler...");
        User user = new User(19, "zhangsan");

        ctx.channel().writeAndFlush(user);
    }
}
