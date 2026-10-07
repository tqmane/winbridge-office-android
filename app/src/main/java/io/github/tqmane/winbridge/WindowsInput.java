package io.github.tqmane.winbridge;

import static android.view.KeyEvent.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.termux.x11.LorieView;
import com.termux.x11.input.InputStub;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Uses Win32 input APIs directly; the embedded X server only supplies the picture. */
final class WindowsInput implements InputStub {
    private record Event(int type, int a, int b, int c, String text) { }
    private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(512);
    private final Context context;
    private volatile Socket socket;
    private volatile boolean active;
    private long lastError;
    private int touchId = -1;
    private boolean penDown;

    WindowsInput(Context context) {
        this.context = context;
        Thread worker = new Thread(this::pump, "direct-windows-input");
        worker.setDaemon(true);
        worker.start();
    }

    void resume() { active = true; }

    void pause() {
        active = false;
        events.clear();
        touchId = -1;
        penDown = false;
        disconnect(); // The helper releases all held keys/buttons on EOF.
    }

    private void disconnect() {
        Socket current = socket;
        if (current != null) try { current.close(); } catch (java.io.IOException ignored) { }
    }

    private void error() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastError < 5000) return;
        lastError = now;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,
            "Direct input disconnected. Reopen the Windows app and retry the input.", Toast.LENGTH_LONG).show());
    }

    private void enqueue(Event event) {
        if (!active) return;
        LorieView.markUserActivity();
        if (!events.offer(event)) {
            events.clear();
            disconnect();
            error();
        }
    }

    private void send(int type, int a, int b, int c) { enqueue(new Event(type, a, b, c, null)); }

    private void pump() {
        for (;;) {
            try {
                Event first = events.take();
                if (!active) continue;
                File runtime = new File(context.getFilesDir(), "runtime");
                byte[] key = Files.readAllBytes(new File(runtime, ".input-key").toPath());
                if (key.length != 32) throw new java.io.IOException("Invalid key file");
                int port = Integer.parseInt(new String(Files.readAllBytes(new File(runtime, ".input-port").toPath()),
                    StandardCharsets.US_ASCII).trim());
                if (port < 1024 || port > 65535) throw new java.io.IOException("Invalid port");
                try (Socket connection = new Socket()) {
                    socket = connection;
                    if (!active) continue;
                    connection.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                    connection.setSoTimeout(3000);
                    connection.setTcpNoDelay(true);
                    DataInputStream input = new DataInputStream(connection.getInputStream());
                    DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                    byte[] challenge = new byte[32];
                    input.readFully(challenge);
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(key, "HmacSHA256"));
                    int sequence = 1;
                    packet(output, mac, challenge, sequence++, new Event(0, 0, 0, 0, null));
                    if (input.read() != 1) throw new java.io.IOException("Authentication failed");
                    Event next = first;
                    while (active) {
                        if (next == null) next = new Event(0, 0, 0, 0, null);
                        if (next.text() == null) packet(output, mac, challenge, sequence++, next);
                        else for (int i = 0; i < next.text().length(); i++)
                            packet(output, mac, challenge, sequence++, new Event(2, next.text().charAt(i), 0, 0, null));
                        next = events.poll(1, TimeUnit.SECONDS);
                    }
                } finally { socket = null; }
            } catch (Exception failure) {
                events.clear();
                if (active) error();
            }
        }
    }

    private static void packet(DataOutputStream output, Mac mac, byte[] challenge, int sequence, Event event) throws Exception {
        byte[] body = ByteBuffer.allocate(20).putInt(event.type()).putInt(event.a()).putInt(event.b())
            .putInt(event.c()).putInt(sequence).array();
        mac.update(challenge);
        byte[] signature = mac.doFinal(body);
        output.write(body);
        output.write(signature);
        output.flush();
    }

    @Override public boolean sendKeyEvent(int scanCode, int keyCode, boolean down) {
        int vk = virtualKey(keyCode);
        if (vk == 0) return false;
        send(1, vk, down ? 1 : 0, 0);
        return true;
    }

    @Override public void sendTextEvent(byte[] utf8) {
        if (utf8.length > 1_048_576) { error(); return; }
        enqueue(new Event(2, 0, 0, 0, new String(utf8, StandardCharsets.UTF_8)));
    }

    @Override public void sendMouseEvent(float x, float y, int button, boolean down, boolean relative) {
        if (button == BUTTON_SCROLL) { sendMouseWheelEvent(x, y); return; }
        send(3, Math.round(x), Math.round(y), button | (down ? 256 : 0) | (relative ? 512 : 0));
    }

    @Override public void sendMouseWheelEvent(float x, float y) {
        // The Android adapter expresses one wheel notch as -100; Windows uses +120.
        send(4, Math.round(Math.max(-12000, Math.min(12000, -x * 1.2f))),
            Math.round(Math.max(-12000, Math.min(12000, -y * 1.2f))), 0);
    }

    @Override public void sendTouchEvent(int action, int id, int x, int y) {
        // ponytail: single-contact pointer; add Win32 touch injection when multi-touch is validated.
        if (action == 18 && touchId == -1) { touchId = id; sendMouseEvent(x, y, BUTTON_LEFT, true, false); }
        else if (id == touchId && action == 19) sendMouseEvent(x, y, BUTTON_UNDEFINED, false, false);
        else if (id == touchId && action == 20) { sendMouseEvent(x, y, BUTTON_LEFT, false, false); touchId = -1; }
    }

    @Override public void sendStylusEvent(float x, float y, int pressure, int tiltX, int tiltY,
            int orientation, int buttons, boolean eraser, boolean mouseMode) {
        // ponytail: pen currently acts as a pointer; pressure/tilt require a tested WM_POINTER backend.
        boolean down = pressure > 0;
        sendMouseEvent(x, y, penDown == down ? BUTTON_UNDEFINED : BUTTON_LEFT, down, false);
        penDown = down;
    }

    @Override public void sendLockKeysState(int state) {
        send(5, 0x14, state & 1, 0);
        send(5, 0x90, (state >> 1) & 1, 0);
        send(5, 0x91, (state >> 2) & 1, 0);
    }

    static int virtualKey(int key) {
        if (key >= KEYCODE_A && key <= KEYCODE_Z) return 0x41 + key - KEYCODE_A;
        if (key >= KEYCODE_0 && key <= KEYCODE_9) return 0x30 + key - KEYCODE_0;
        if (key >= KEYCODE_F1 && key <= KEYCODE_F12) return 0x70 + key - KEYCODE_F1;
        if (key >= KEYCODE_NUMPAD_0 && key <= KEYCODE_NUMPAD_9) return 0x60 + key - KEYCODE_NUMPAD_0;
        return switch (key) {
            case KEYCODE_DEL -> 0x08; case KEYCODE_TAB -> 0x09;
            case KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER -> 0x0d; case KEYCODE_ESCAPE -> 0x1b;
            case KEYCODE_SPACE -> 0x20; case KEYCODE_PAGE_UP -> 0x21; case KEYCODE_PAGE_DOWN -> 0x22;
            case KEYCODE_MOVE_END -> 0x23; case KEYCODE_MOVE_HOME -> 0x24;
            case KEYCODE_DPAD_LEFT -> 0x25; case KEYCODE_DPAD_UP -> 0x26;
            case KEYCODE_DPAD_RIGHT -> 0x27; case KEYCODE_DPAD_DOWN -> 0x28;
            case KEYCODE_SYSRQ -> 0x2c; case KEYCODE_INSERT -> 0x2d; case KEYCODE_FORWARD_DEL -> 0x2e;
            case KEYCODE_SHIFT_LEFT -> 0xa0; case KEYCODE_SHIFT_RIGHT -> 0xa1;
            case KEYCODE_CTRL_LEFT -> 0xa2; case KEYCODE_CTRL_RIGHT -> 0xa3;
            case KEYCODE_ALT_LEFT -> 0xa4; case KEYCODE_ALT_RIGHT -> 0xa5;
            case KEYCODE_META_LEFT -> 0x5b; case KEYCODE_META_RIGHT -> 0x5c; case KEYCODE_MENU -> 0x5d;
            case KEYCODE_CAPS_LOCK -> 0x14; case KEYCODE_NUM_LOCK -> 0x90; case KEYCODE_SCROLL_LOCK -> 0x91;
            case KEYCODE_BREAK -> 0x13; case KEYCODE_NUMPAD_MULTIPLY -> 0x6a; case KEYCODE_NUMPAD_ADD -> 0x6b;
            case KEYCODE_NUMPAD_SUBTRACT -> 0x6d; case KEYCODE_NUMPAD_DOT -> 0x6e; case KEYCODE_NUMPAD_DIVIDE -> 0x6f;
            case KEYCODE_SEMICOLON -> 0xba; case KEYCODE_EQUALS -> 0xbb; case KEYCODE_COMMA -> 0xbc;
            case KEYCODE_MINUS -> 0xbd; case KEYCODE_PERIOD -> 0xbe; case KEYCODE_SLASH -> 0xbf;
            case KEYCODE_GRAVE -> 0xc0; case KEYCODE_LEFT_BRACKET -> 0xdb; case KEYCODE_BACKSLASH -> 0xdc;
            case KEYCODE_RIGHT_BRACKET -> 0xdd; case KEYCODE_APOSTROPHE -> 0xde;
            default -> 0;
        };
    }
}
