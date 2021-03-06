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
package io.netty.bench.udt.xfer;

import static io.netty.bench.udt.util.UnitHelp.*;
import io.netty.example.udt.util.ConsoleReporterUDT;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.udt.SocketUDT;
import com.barchart.udt.StatusUDT;
import com.barchart.udt.TypeUDT;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;

/**
 * perform two way raw send/recv
 */
public final class BenchXferRaw {

    private BenchXferRaw() {
    }

    static final Logger log = LoggerFactory.getLogger(BenchXferRaw.class);

    /** benchmark duration */
    static final int time = 60 * 1000;

    /** transfer chunk size */
    static final int size = 64 * 1024;

    static final Counter benchTime = Metrics.newCounter(BenchXferRaw.class,
            "bench time");

    static final Counter benchSize = Metrics.newCounter(BenchXferRaw.class,
            "bench size");

    static {
        benchTime.inc(time);
        benchSize.inc(size);
    }

    static final Meter sendRate = Metrics.newMeter(BenchXferRaw.class,
            "send rate", "bytes", TimeUnit.SECONDS);

    static final Timer sendTime = Metrics.newTimer(BenchXferRaw.class,
            "send time", TimeUnit.MILLISECONDS, TimeUnit.SECONDS);

    public static void main(final String[] args) throws Exception {

        log.info("init");

        final InetSocketAddress addr1 = localSocketAddress();
        final InetSocketAddress addr2 = localSocketAddress();

        final SocketUDT peer1 = new SocketUDT(TypeUDT.DATAGRAM);
        final SocketUDT peer2 = new SocketUDT(TypeUDT.DATAGRAM);

        peer1.setBlocking(false);
        peer2.setBlocking(false);

        peer1.setRendezvous(true);
        peer2.setRendezvous(true);

        peer1.bind(addr1);
        peer2.bind(addr2);

        socketAwait(peer1, StatusUDT.OPENED);
        socketAwait(peer2, StatusUDT.OPENED);

        peer1.connect(addr2);
        peer2.connect(addr1);

        socketAwait(peer1, StatusUDT.CONNECTED);
        socketAwait(peer2, StatusUDT.CONNECTED);

        peer1.setBlocking(true);
        peer2.setBlocking(true);

        final AtomicBoolean isOn = new AtomicBoolean(true);

        final Runnable sendPeer1 = new Runnable() {

            @Override
            public void run() {
                try {
                    while (isOn.get()) {
                        runCore();
                    }
                } catch (final Exception e) {
                    log.error("", e);
                }
            }

            final ByteBuffer buffer = ByteBuffer.allocateDirect(size);

            long sequence;

            void runCore() throws Exception {

                buffer.rewind();
                buffer.putLong(0, sequence++);

                final TimerContext timer = sendTime.time();

                final int count = peer1.send(buffer);

                timer.stop();

                if (count != size) {
                    throw new Exception("count");
                }

                sendRate.mark(count);
            }
        };

        final Runnable sendPeer2 = new Runnable() {

            @Override
            public void run() {
                try {
                    while (isOn.get()) {
                        runCore();
                    }
                } catch (final Exception e) {
                    log.error("", e);
                }
            }

            final ByteBuffer buffer = ByteBuffer.allocateDirect(size);

            long sequence;

            void runCore() throws Exception {

                buffer.rewind();
                buffer.putLong(0, sequence++);

                final int count = peer2.send(buffer);

                if (count != size) {
                    throw new Exception("count");
                }

            }
        };

        final Runnable recvPeer1 = new Runnable() {

            @Override
            public void run() {
                try {
                    while (isOn.get()) {
                        runCore();
                    }
                } catch (final Exception e) {
                    log.error("", e);
                }
            }

            final ByteBuffer buffer = ByteBuffer.allocateDirect(size);

            long sequence;

            void runCore() throws Exception {

                buffer.rewind();

                final int count = peer1.receive(buffer);

                if (count != size) {
                    throw new Exception("count");
                }

                if (this.sequence++ != buffer.getLong(0)) {
                    throw new Exception("sequence");
                }
            }
        };

        final Runnable recvPeer2 = new Runnable() {

            @Override
            public void run() {
                try {
                    while (isOn.get()) {
                        runCore();
                    }
                } catch (final Exception e) {
                    log.error("", e);
                }
            }

            final ByteBuffer buffer = ByteBuffer.allocateDirect(size);

            long sequence;

            void runCore() throws Exception {

                buffer.rewind();

                final int count = peer2.receive(buffer);

                if (count != size) {
                    throw new Exception("count");
                }

                if (this.sequence++ != buffer.getLong(0)) {
                    throw new Exception("sequence");
                }
            }
        };

        final ExecutorService executor = Executors.newFixedThreadPool(4);

        executor.submit(recvPeer1);
        executor.submit(recvPeer2);
        executor.submit(sendPeer1);
        executor.submit(sendPeer2);

        ConsoleReporterUDT.enable(3, TimeUnit.SECONDS);

        Thread.sleep(time);

        isOn.set(false);

        Thread.sleep(1 * 1000);

        executor.shutdownNow();

        Metrics.defaultRegistry().shutdown();

        peer1.close();
        peer2.close();

        log.info("done");
    }

}
