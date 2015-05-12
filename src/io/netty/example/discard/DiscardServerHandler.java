package io.netty.example.discard;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class DiscardServerHandler extends ChannelInboundHandlerAdapter {

	@Override
	public void channelRead( ChannelHandlerContext ctx, Object msg ) {
		((ByteBuf)msg).release();
	}
	
	@Override
	public void exceptionCaught( ChannelHandlerContext ctx, Throwable cause ) {
		cause.printStackTrace();
		ctx.close();
	}
}