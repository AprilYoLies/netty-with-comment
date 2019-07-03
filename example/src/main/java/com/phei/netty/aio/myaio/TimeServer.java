package com.phei.netty.aio.myaio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Date;
import java.util.concurrent.CountDownLatch;

public class TimeServer {
	public static void main(String[] args) {
		int port = 8080;
		final CountDownLatch cdl = new CountDownLatch(1);
		AsynchronousServerSocketChannel assc = null;
		try {
			assc = AsynchronousServerSocketChannel.open();
			assc.bind(new InetSocketAddress(port));
			assc.accept(assc, new CompletionHandler<AsynchronousSocketChannel, AsynchronousServerSocketChannel>() {

				@Override
				public void completed(AsynchronousSocketChannel result, AsynchronousServerSocketChannel attachment) {
					attachment.accept(attachment, this);
					ByteBuffer buf = ByteBuffer.allocate(1024);
					final AsynchronousSocketChannel asc = result;
					result.read(buf, buf, new CompletionHandler<Integer, ByteBuffer>() {
						@Override
						public void completed(Integer result, ByteBuffer attachment) {
							byte[] bytes = new Date().toString().getBytes();
							ByteBuffer buf = ByteBuffer.allocate(1024);
							buf.put(bytes);
							buf.flip();
							asc.write(buf, buf, new CompletionHandler<Integer, ByteBuffer>() {

								@Override
								public void completed(Integer result, ByteBuffer attachment) {
									if (attachment.hasRemaining()) {
										asc.write(attachment, attachment, this);
									}
								}

								@Override
								public void failed(Throwable exc, ByteBuffer attachment) {
									try {
										if (asc != null)
											asc.close();
									} catch (IOException e) {
										e.printStackTrace();
									}
								}
							});
						}

						@Override
						public void failed(Throwable exc, ByteBuffer attachment) {
							try {
								if (asc != null)
									asc.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					});
				}

				@Override
				public void failed(Throwable exc, AsynchronousServerSocketChannel attachment) {
					try {
						if (attachment != null)
							attachment.close();
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						cdl.countDown();
					}

				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			cdl.await();
		} catch (InterruptedException e) {

		} finally {
			if (assc != null) {
				try {
					assc.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
