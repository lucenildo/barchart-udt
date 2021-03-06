/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.udt.echo.rendevous;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.UdtChannel;
import io.netty.channel.socket.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioUdtProvider;
import io.netty.example.udt.util.ThreadFactoryUDT;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.logging.InternalLoggerFactory;
import io.netty.logging.Slf4JLoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UDT Message Flow Peer
 * <p>
 * Sends one message when a connection is open and echoes back any received data
 * to the other peer.
 */
public abstract class MsgEchoPeerBase {

    protected static final Logger log = LoggerFactory
            .getLogger(MsgEchoPeerBase.class);

    /**
     * use slf4j provider for io.netty.logging.InternalLogger
     */
    static {
        final InternalLoggerFactory defaultFactory = new Slf4JLoggerFactory();
        InternalLoggerFactory.setDefaultFactory(defaultFactory);
        log.info("InternalLoggerFactory={}", InternalLoggerFactory
                .getDefaultFactory().getClass().getName());
    }

    protected final int messageSize;
    protected final InetSocketAddress self;
    protected final InetSocketAddress peer;

    public MsgEchoPeerBase(final InetSocketAddress self,
            final InetSocketAddress peer, final int messageSize) {
        this.messageSize = messageSize;
        this.self = self;
        this.peer = peer;
    }

    public void run() throws Exception {
        // Configure the peer.
        final Bootstrap boot = new Bootstrap();
        final ThreadFactory connectFactory = new ThreadFactoryUDT("rendezvous");
        final NioEventLoopGroup connectGroup = new NioEventLoopGroup(1,
                connectFactory, NioUdtProvider.MESSAGE_PROVIDER);
        try {
            boot.group(connectGroup)
                    .channelFactory(NioUdtProvider.MESSAGE_RENDEZVOUS)
                    .localAddress(self).remoteAddress(peer)
                    .handler(new ChannelInitializer<UdtChannel>() {
                        @Override
                        public void initChannel(final UdtChannel ch)
                                throws Exception {
                            ch.pipeline().addLast(
                                    new LoggingHandler(LogLevel.INFO),
                                    new MsgEchoPeerHandler(messageSize));
                        }
                    });
            // Start the peer.
            final ChannelFuture f = boot.connect().sync();
            // Wait until the connection is closed.
            f.channel().closeFuture().sync();
        } finally {
            // Shut down the event loop to terminate all threads.
            boot.shutdown();
        }
    }

}
