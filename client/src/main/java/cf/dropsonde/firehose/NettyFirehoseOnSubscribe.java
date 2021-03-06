/*
 *   Copyright (c) 2015 Intellectual Reserve, Inc.  All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package cf.dropsonde.firehose;

import org.cloudfoundry.dropsonde.events.Envelope;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.CharsetUtil;
import rx.Subscriber;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.io.Closeable;
import java.net.URI;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

/**
 * @author Mike Heath
 */
class NettyFirehoseOnSubscribe implements rx.Observable.OnSubscribe<Envelope>, Closeable {

	public static final String HANDLER_NAME = "handler";
	private Channel channel;

	// Set this EventLoopGroup if an EventLoopGroup was not provided. This groups needs to be shutdown when the
	// firehose client shuts down.
	private final EventLoopGroup eventLoopGroup;
	private final Bootstrap bootstrap;

	public NettyFirehoseOnSubscribe(URI uri, String token, String subscriptionId, boolean skipTlsValidation, EventLoopGroup eventLoopGroup, Class<? extends SocketChannel> channelClass) {
		try {
			final String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
			final String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();
			final int port = getPort(scheme, uri.getPort());
			final URI fullUri = uri.resolve("/firehose/" + subscriptionId);

			final SslContext sslContext;
			if ("wss".equalsIgnoreCase(scheme)) {
				final SslContextBuilder sslContextBuilder = SslContextBuilder.forClient();
				if (skipTlsValidation) {
					sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
				} else {
					TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
					trustManagerFactory.init((KeyStore)null);
					sslContextBuilder.trustManager(trustManagerFactory);
				}
				sslContext = sslContextBuilder.build();
			} else {
				sslContext = null;
			}

			bootstrap = new Bootstrap();
			if (eventLoopGroup == null) {
				this.eventLoopGroup = new NioEventLoopGroup();
				bootstrap.group(this.eventLoopGroup);
			} else {
				this.eventLoopGroup = null;
				bootstrap.group(eventLoopGroup);
			}
			bootstrap
					.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
					.channel(channelClass == null ? NioSocketChannel.class : channelClass)
					.remoteAddress(host, port)
					.handler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel c) throws Exception {
							final HttpHeaders headers = new DefaultHttpHeaders();
							headers.add(HttpHeaders.Names.AUTHORIZATION, token);
							final WebSocketClientHandler handler =
									new WebSocketClientHandler(
											WebSocketClientHandshakerFactory.newHandshaker(
													fullUri, WebSocketVersion.V13, null, false, headers));
							final ChannelPipeline pipeline = c.pipeline();
							if (sslContext != null) {
								pipeline.addLast(sslContext.newHandler(c.alloc(), host, port));
							}
							pipeline.addLast(new ReadTimeoutHandler(30));
							pipeline.addLast(
									new HttpClientCodec(),
									new HttpObjectAggregator(8192));
							pipeline.addLast(HANDLER_NAME, handler);

							channel = c;
						}
					});
		} catch (NoSuchAlgorithmException | SSLException | KeyStoreException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void call(Subscriber<? super Envelope> subscriber) {
		bootstrap.connect().addListener((ChannelFutureListener)future -> {
			if (future.isSuccess()) {
				future.channel().pipeline().get(WebSocketClientHandler.class).setSubscriber(subscriber);
			} else {
				subscriber.onError(future.cause());
			}
		});
	}

	@Override
	public void close() {
		if (channel != null) {
			channel.close();
		}
		if (eventLoopGroup != null) {
			eventLoopGroup.shutdownGracefully();
		}
	}

	private int getPort(String scheme, int port) {
		if (port == -1) {
			if ("ws".equalsIgnoreCase(scheme)) {
				return 80;
			} else if ("wss".equalsIgnoreCase(scheme)) {
				return 443;
			} else {
				return -1;
			}
		} else {
			return port;
		}
	}

	public boolean isConnected() {
		return channel != null && channel.isActive();
	}

	private static class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {

		private final WebSocketClientHandshaker handshaker;

		private Subscriber<? super Envelope> subscriber;

		public WebSocketClientHandler(WebSocketClientHandshaker handshaker) {
			this.handshaker = handshaker;
		}

		@Override
		public void channelActive(ChannelHandlerContext context) throws Exception {
			handshaker.handshake(context.channel());
		}

		@Override
		public void channelInactive(ChannelHandlerContext context) throws Exception {
			subscriber.onCompleted();
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
			context.close();
			subscriber.onError(cause);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext context, Object message) throws Exception {
			final Channel channel = context.channel();
			if (!handshaker.isHandshakeComplete()) {
				handshaker.finishHandshake(channel, (FullHttpResponse) message);
				channel.pipeline().addBefore(HANDLER_NAME, "websocket-frame-aggregator", new WebSocketFrameAggregator(64 * 1024));
				subscriber.onStart();
				return;
			}

			if (message instanceof FullHttpResponse) {
				final FullHttpResponse response = (FullHttpResponse) message;
				throw new IllegalStateException(
						"Unexpected FullHttpResponse (getStatus=" + response.getStatus() +
								", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
			}

			final WebSocketFrame frame = (WebSocketFrame) message;
			if (frame instanceof PingWebSocketFrame) {
				context.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame)frame).retain().content()));
			} else if (frame instanceof BinaryWebSocketFrame) {
				final ByteBufInputStream input = new ByteBufInputStream(((BinaryWebSocketFrame)message).content());
				final Envelope envelope = Envelope.ADAPTER.decode(input);
				subscriber.onNext(envelope);
			}
		}

		public void setSubscriber(Subscriber<? super Envelope> subscriber) {
			this.subscriber = subscriber;
		}
	}

}
