package com.elyastech.lightcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Bluetooth Classic RFCOMM/SPP to ESP32 (not BLE). No network permission used. */
@SuppressWarnings("MissingPermission")
public final class BluetoothClient {
    public interface Listener {
        void onConnecting(String label);
        void onConnected(String label);
        void onDisconnected(String reason);
        void onLine(String line);
    }

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final Object socketLock = new Object();
    private volatile BluetoothSocket socket;
    private volatile OutputStream output;
    private volatile boolean connected;

    public BluetoothClient(Listener listener) { this.listener = listener; }
    public boolean isConnected() { return connected; }

    public void connect(BluetoothDevice device, String pin) {
        final int token = generation.incrementAndGet();
        closeSocket();
        final String name;
        String safeName;
        try { safeName = device.getName(); } catch (SecurityException e) { safeName = null; }
        name = safeName == null ? device.getAddress() : safeName;
        post(() -> listener.onConnecting(name));
        new Thread(() -> {
            BluetoothSocket pending = null;
            String error = "انقطع الاتصال";
            try {
                pending = device.createRfcommSocketToServiceRecord(SPP_UUID);
                synchronized (socketLock) {
                    if (token != generation.get()) { pending.close(); return; }
                    socket = pending; // closeSocket() can cancel a pending socket.connect().
                }
                pending.connect();
                if (token != generation.get()) return;
                InputStream in = pending.getInputStream();
                OutputStream out = pending.getOutputStream();
                synchronized (socketLock) {
                    if (token != generation.get()) return;
                    output = out;
                    connected = true;
                }
                post(() -> listener.onConnected(name));
                // First authorize; the firmware processes newline-delimited commands in order.
                writeDirect(out, "AUTH " + pin);
                writeDirect(out, "STATUS");

                byte[] bytes = new byte[256];
                StringBuilder line = new StringBuilder();
                while (token == generation.get()) {
                    int count = in.read(bytes);
                    if (count < 0) break;
                    for (int i = 0; i < count; ++i) {
                        int c = bytes[i] & 0xFF;
                        if (c == '\r' || c == '\n') {
                            if (line.length() > 0) {
                                String msg = line.toString();
                                line.setLength(0);
                                post(() -> { if (token == generation.get()) listener.onLine(msg); });
                            }
                        } else if (c >= 32 && c <= 126 && line.length() < 256) {
                            line.append((char)c);
                        }
                    }
                }
            } catch (IOException | SecurityException e) {
                error = (e.getMessage() == null) ? "تعذر اتصال Bluetooth" : e.getMessage();
            } finally {
                if (token == generation.get()) {
                    closeSocket();
                    String failure = error;
                    post(() -> { if (token == generation.get()) listener.onDisconnected(failure); });
                } else if (pending != null) {
                    try { pending.close(); } catch (IOException ignored) {}
                }
            }
        }, "elyas-bluetooth-read").start();
    }

    public void send(String line) {
        if (!connected || output == null) {
            post(() -> listener.onDisconnected("الهاتف غير متصل بالشاشة"));
            return;
        }
        final int token = generation.get();
        writer.execute(() -> {
            try {
                OutputStream out = output;
                if (out != null && token == generation.get()) writeDirect(out, line);
            } catch (IOException e) {
                if (token == generation.get()) {
                    closeSocket();
                    post(() -> listener.onDisconnected("فشل إرسال الأمر"));
                }
            }
        });
    }

    private static void writeDirect(OutputStream out, String line) throws IOException {
        synchronized (out) {
            out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    public void disconnect() {
        generation.incrementAndGet();
        closeSocket();
        post(() -> listener.onDisconnected("تم فصل الاتصال"));
    }

    private void closeSocket() {
        synchronized (socketLock) {
            connected = false;
            output = null;
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) {}
                socket = null;
            }
        }
    }
    private void post(Runnable callback) { main.post(callback); }
}
