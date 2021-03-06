/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket.nio;

import static java.nio.channels.SelectionKey.*;
import io.netty.buffer.BufType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.MessageBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.socket.DefaultUdtChannelConfig;
import io.netty.channel.socket.UdtChannel;
import io.netty.channel.socket.UdtChannelConfig;
import io.netty.channel.socket.UdtMessage;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;

import com.barchart.udt.TypeUDT;
import com.barchart.udt.nio.SocketChannelUDT;

/**
 * Metty Message Connector for UDT Datagrams
 */
public class NioUdtMessageConnectorChannel extends AbstractNioMessageChannel
        implements UdtChannel {

    protected static final InternalLogger logger = InternalLoggerFactory
            .getInstance(NioUdtMessageConnectorChannel.class);

    protected static final ChannelMetadata METADATA = new ChannelMetadata(
            BufType.MESSAGE, false);

    private final UdtChannelConfig config;

    protected NioUdtMessageConnectorChannel() {
        this(TypeUDT.DATAGRAM);
    }

    protected NioUdtMessageConnectorChannel(final Channel parent,
            final Integer id, final SocketChannelUDT channelUDT) {
        super(parent, id, channelUDT, OP_READ);
        try {
            channelUDT.configureBlocking(false);
            config = new DefaultUdtChannelConfig();
            switch (channelUDT.socketUDT().status()) {
            case INIT:
            case OPENED:
                config.apply(channelUDT);
            }
        } catch (final IOException e) {
            try {
                channelUDT.close();
            } catch (final IOException e2) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to close channel.", e2);
                }
            }
            throw new ChannelException("Failed to configure channel.", e);
        }
    }

    protected NioUdtMessageConnectorChannel(final SocketChannelUDT channelUDT) {
        this(null, channelUDT.socketUDT().id(), channelUDT);
    }

    protected NioUdtMessageConnectorChannel(final TypeUDT type) {
        this(NioUdtProvider.newConnectorChannelUDT(type));
    }

    @Override
    public UdtChannelConfig config() {
        return config;
    }

    @Override
    protected void doBind(final SocketAddress localAddress) throws Exception {
        javaChannel().bind(localAddress);
    }

    @Override
    protected void doClose() throws Exception {
        javaChannel().close();
    }

    @Override
    protected boolean doConnect(final SocketAddress remoteAddress,
            final SocketAddress localAddress) throws Exception {
        doBind(localAddress);
        boolean success = false;
        try {
            final boolean connected = javaChannel().connect(remoteAddress);
            if (connected) {
                selectionKey().interestOps(OP_READ);
            } else {
                selectionKey().interestOps(OP_CONNECT);
            }
            success = true;
            return connected;
        } finally {
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doFinishConnect() throws Exception {
        if (!javaChannel().finishConnect()) {
            throw new Error(
                    "Provider error: failed to finish connect. Provider library should be upgraded.");
        }
        selectionKey().interestOps(OP_READ);
    }

    @Override
    protected int doReadMessages(final MessageBuf<Object> buf) throws Exception {

        final int maximumMessageSize = config.getReceiveBufferSize();

        final ByteBuf byteBuf = config.getAllocator().directBuffer(
                maximumMessageSize);

        final int receivedMessageSize = byteBuf.writeBytes(javaChannel(),
                maximumMessageSize);

        if (receivedMessageSize <= 0) {
            byteBuf.free();
            return 0;
        }

        if (receivedMessageSize >= maximumMessageSize) {
            javaChannel().close();
            throw new ChannelException(
                    "Invalid config : increase receive buffer size to avoid message truncation");
        }

        buf.add(new UdtMessage(byteBuf));

        return 1;
    }

    @Override
    protected int doWriteMessages(final MessageBuf<Object> messageQueue,
            final boolean lastSpin) throws Exception {

        final UdtMessage message = (UdtMessage) messageQueue.peek();

        final ByteBuf byteBuf = message.data();

        final int messageSize = byteBuf.readableBytes();

        final long writtenBytes;
        if (byteBuf.nioBufferCount() == 1) {
            writtenBytes = javaChannel().write(byteBuf.nioBuffer());
        } else {
            writtenBytes = javaChannel().write(byteBuf.nioBuffers());
        }

        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        // did not write the message
        if (writtenBytes <= 0 && messageSize > 0) {
            if (lastSpin) {
                if ((interestOps & OP_WRITE) == 0) {
                    key.interestOps(interestOps | OP_WRITE);
                }
            }
            return 0;
        }

        // wrote message completely
        if (writtenBytes != messageSize) {
            throw new Error(
                    "Provider error: failed to write message. Provider library should be upgraded.");
        }

        // wrote the message queue completely - clear OP_WRITE.
        if (messageQueue.isEmpty()) {
            if ((interestOps & OP_WRITE) != 0) {
                key.interestOps(interestOps & ~OP_WRITE);
            }
        }

        message.free();

        messageQueue.remove();

        return 1;
    }

    @Override
    public boolean isActive() {
        final SocketChannelUDT channelUDT = javaChannel();
        return channelUDT.isOpen() && channelUDT.isConnectFinished();
    }

    @Override
    protected SocketChannelUDT javaChannel() {
        return (SocketChannelUDT) super.javaChannel();
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

}
