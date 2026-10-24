package com.classaudio.hook;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

/** Serves frames to one client at a time on 127.0.0.1:port. Frames are dropped while nobody is connected. */
final class Streamer {
    private final int port;
    private final ArrayBlockingQueue<byte[]> queue;
    private volatile boolean clientConnected;
    private boolean started;

    Streamer(int port, int capacity) {
        this.port = port;
        this.queue = new ArrayBlockingQueue<byte[]>(capacity);
    }

    boolean connected() { return clientConnected; }

    void offer(byte[] frame) { queue.offer(frame); }

    synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() { public void run() { serve(); } }, "ClassAudio-" + port);
        t.setDaemon(true);
        t.start();
    }

    private void serve() {
        while (true) {
            try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
                while (true) {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    queue.clear();
                    clientConnected = true;
                    try {
                        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 8192));
                        while (true) {
                            byte[] frame = queue.take();
                            out.write(frame);
                            if (queue.isEmpty()) out.flush();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        clientConnected = false;
                        try { s.close(); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable t) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
    }
}
