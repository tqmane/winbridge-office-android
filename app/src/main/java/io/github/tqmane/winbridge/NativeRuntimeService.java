package io.github.tqmane.winbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.IBinder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** App-owned PRoot host. No companion package, shell UID or root privilege. */
public final class NativeRuntimeService extends Service {
    private final java.util.concurrent.ExecutorService worker = Executors.newCachedThreadPool();
    private final AtomicInteger active = new AtomicInteger();
    private static boolean displayStarted;
    private boolean installing;
    private volatile boolean stopping;
    private final java.util.Set<Process> sessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null || intent.getAction() == null ? "status" : intent.getAction();
        if (stopping) {
            status("Runtime is stopping. Wait for completion before launching again.");
            return START_NOT_STICKY;
        }
        if (!List.of("init", "native-smoke", "status", "logs", "stop", "reset", "notepad", "winecfg", "word", "excel", "powerpoint", "install-office").contains(action)) {
            status("This runtime action is not implemented yet: " + action);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("runtime", "Windows runtime", NotificationManager.IMPORTANCE_LOW));
        var pending = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(11, new Notification.Builder(this, "runtime").setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("WinBridge runtime").setContentText("Windows runtime: " + action)
            .setContentIntent(pending).setOngoing(true).build());
        active.incrementAndGet();
        if (action.equals("stop") || action.equals("reset")) stopping = true;
        worker.execute(() -> {
            try {
                if ("logs".equals(action)) {
                    File[] logs = directory("logs").listFiles();
                    if (logs != null && logs.length > 0) {
                        java.util.Arrays.sort(logs, java.util.Comparator.comparingLong(File::lastModified));
                        status(readTail(logs[logs.length - 1]));
                    } else status("No command logs yet.");
                    return;
                }
                if ("init".equals(action) || "native-smoke".equals(action)) initializeRootfs();
                if ("init".equals(action)) {
                    synchronized (this) {
                        if (installing) { status("Runtime installation is already running."); return; }
                        installing = true;
                    }
                    status("Installing Wine and its Linux dependencies. Logs are stored privately in WinBridge.");
                    try {
                        copyScript("linux-setup.sh");
                        status(linux("/bin/bash", "/winbridge/linux-setup.sh"));
                    } finally { synchronized (this) { installing = false; } }
                } else if ("native-smoke".equals(action)) {
                    String result = linux("/bin/sh", "-c", "uname -m; /usr/bin/ldd --version; cat /etc/os-release; id");
                    if (!result.contains("x86_64") || !result.contains("Ubuntu")) throw new IllegalStateException("Linux smoke check failed: " + result);
                    status("Integrated runtime passed (app UID, no Termux service):\n" + result);
                } else {
                    if (!new File(getFilesDir(), "runtime/.runtime-ready").isFile()) {
                        status("Wine runtime is not initialized. Select Initialize runtime.");
                        return;
                    }
                    copyScript("linux-launch.sh");
                    if (action.equals("stop") || action.equals("reset")) {
                        linux("/bin/bash", "/winbridge/linux-launch.sh", "stop");
                        List<Process> running;
                        synchronized (sessions) { running = List.copyOf(sessions); }
                        for (Process session : running) {
                            session.destroy();
                            if (!session.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) session.destroyForcibly();
                        }
                        if (action.equals("stop")) { status("All Wine sessions stopped."); return; }
                    }
                    if (!List.of("status", "stop", "reset").contains(action)) {
                        copyScript("windows-input.exe");
                        File inputKey = new File(directory("runtime"), ".input-key");
                        synchronized (this) {
                            if (!inputKey.isFile()) {
                                byte[] key = new byte[32];
                                new java.security.SecureRandom().nextBytes(key);
                                Files.write(inputKey.toPath(), key);
                                android.system.Os.chmod(inputKey.getAbsolutePath(), 0600);
                            }
                        }
                        startDisplay();
                    }
                    status(linux("/bin/bash", "/winbridge/linux-launch.sh", action));
                }
            } catch (Exception error) { if (!stopping) status("Integrated runtime failed: " + error); }
            finally {
                if (action.equals("stop") || action.equals("reset")) stopping = false;
                active.decrementAndGet();
                new android.os.Handler(getMainLooper()).post(() -> {
                    if (active.get() == 0) stopSelf();
                });
            }
        });
        return START_NOT_STICKY;
    }

    private synchronized void copyScript(String name) throws Exception {
        File destination = new File(directory("runtime"), name);
        File partial = new File(destination + ".new");
        try (var input = getAssets().open(name)) {
            Files.copy(input, partial.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private synchronized void startDisplay() throws Exception {
        if (!displayStarted) {
            android.system.Os.setenv("TMPDIR", directory("run").getAbsolutePath(), true);
            android.system.Os.setenv("XKB_CONFIG_ROOT", new File(getFilesDir(), "linux/usr/share/X11/xkb").getAbsolutePath(), true);
            File authority = new File(directory("runtime"), ".Xauthority");
            byte[] cookie = new byte[16];
            new java.security.SecureRandom().nextBytes(cookie);
            // Xau counted strings use network byte order; FamilyWild matches the guest hostname.
            try (var out = new java.io.DataOutputStream(new FileOutputStream(authority))) {
                out.writeShort(65535);
                for (byte[] field : List.of(new byte[0], "1".getBytes(StandardCharsets.US_ASCII),
                        "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII), cookie)) {
                    out.writeShort(field.length);
                    out.write(field);
                }
            }
            android.system.Os.chmod(authority.getAbsolutePath(), 0600);
            com.termux.x11.CmdEntryPoint.startInApp(this, new String[]{":1", "-auth", authority.getAbsolutePath(),
                "-legacy-drawing", "-nolisten", "tcp"});
            displayStarted = true;
        }
        File socket = new File(getFilesDir(), "run/.X11-unix/X1");
        for (int attempt = 0; !socket.exists() && attempt < 100; attempt++) Thread.sleep(50);
        if (!socket.exists()) throw new IllegalStateException("X11 server did not create its private socket");
    }

    private void status(String message) {
        sendBroadcast(new Intent(this, RuntimeResult.class).putExtra("message", message));
    }

    private File directory(String name) throws Exception {
        File path = new File(getFilesDir(), name);
        if (!path.isDirectory() && !path.mkdirs()) throw new IllegalStateException("Cannot create " + name);
        return path;
    }

    private synchronized void initializeRootfs() throws Exception {
        if (!android.os.Build.SUPPORTED_ABIS[0].equals("x86_64")) throw new IllegalStateException("x86_64 required for this PoC");
        File root = directory("linux");
        directory("run");
        directory("run/shm");
        directory("runtime");
        if (!new File(root, ".winbridge-rootfs").isFile()) {
            status("Downloading verified Ubuntu Base into WinBridge private storage…");
            File archive = new File(getCacheDir(), "ubuntu-base.tar.gz");
            download("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-amd64.tar.gz",
                "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86", archive);
            status("Extracting app-owned Linux rootfs…");
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            ProcessBuilder unpack = new ProcessBuilder(nativeDir + "/libproot.so", "--link2symlink", "-r", root.getAbsolutePath(),
                "-w", "/", "-b", "/system", "-b", "/apex", "-b", "/linkerconfig",
                "-b", archive.getAbsolutePath() + ":/bootstrap.tar.gz",
                "/system/bin/tar", "-xzf", "/bootstrap.tar.gz", "-o", "-C", "/");
            nativeEnvironment(unpack);
            execute(unpack);
            Files.write(new File(root, ".winbridge-rootfs").toPath(), "Ubuntu Base 24.04.5 amd64\n".getBytes(StandardCharsets.UTF_8));
        }
        ConnectivityManager network = getSystemService(ConnectivityManager.class);
        var links = network.getLinkProperties(network.getActiveNetwork());
        if (links != null) {
            StringBuilder dns = new StringBuilder();
            for (var server : links.getDnsServers()) dns.append("nameserver ").append(server.getHostAddress()).append('\n');
            // Remove only the rootfs resolver symlink; never follow it to a host path.
            File resolver = new File(root, "etc/resolv.conf");
            if (Files.isSymbolicLink(resolver.toPath())) Files.delete(resolver.toPath());
            Files.write(resolver.toPath(), dns.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private String linux(String... command) throws Exception {
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        String root = new File(getFilesDir(), "linux").getAbsolutePath();
        String tmp = new File(getFilesDir(), "run").getAbsolutePath();
        List<String> arguments = new ArrayList<>(List.of(nativeDir + "/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
            "-0", "-r", root, "-w", "/root", "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", tmp + ":/tmp", "-b", tmp + "/shm:/dev/shm",
            "-b", new File(getFilesDir(), "runtime").getAbsolutePath() + ":/winbridge",
            "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/bin:/bin", "LANG=C.UTF-8", "TMPDIR=/tmp"));
        arguments.addAll(List.of(command));
        ProcessBuilder builder = new ProcessBuilder(arguments).directory(getFilesDir());
        nativeEnvironment(builder);
        boolean session = command.length > 2 && command[1].equals("/winbridge/linux-launch.sh")
            && !List.of("status", "stop", "reset").contains(command[2]);
        return execute(builder, session);
    }

    private void nativeEnvironment(ProcessBuilder builder) {
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        var env = builder.environment();
        env.clear();
        env.put("PATH", "/system/bin");
        env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("PROOT_TMP_DIR", new File(getFilesDir(), "run").getAbsolutePath());
        env.put("PROOT_LOADER", nativeDir + "/libproot-loader.so");
        env.put("PROOT_LOADER_32", nativeDir + "/libproot-loader32.so");
        env.put("HOME", getFilesDir().getAbsolutePath());
    }

    private String execute(ProcessBuilder builder) throws Exception {
        return execute(builder, false);
    }

    private String execute(ProcessBuilder builder, boolean session) throws Exception {
        File log = new File(directory("logs"), "command-" + System.currentTimeMillis() + ".log");
        builder.redirectErrorStream(true).redirectOutput(log);
        Process process;
        if (session) {
            synchronized (sessions) {
                if (stopping) throw new java.util.concurrent.CancellationException("Runtime is stopping");
                process = builder.start();
                sessions.add(process);
            }
        } else process = builder.start();
        int exit;
        try { exit = process.waitFor(); }
        finally { sessions.remove(process); }
        String text = readTail(log);
        if (exit != 0) throw new IllegalStateException("Exit " + exit + ": " + text);
        return text;
    }

    private String readTail(File log) throws Exception {
        try (var input = new java.io.RandomAccessFile(log, "r")) {
            byte[] tail = new byte[(int) Math.min(input.length(), 65536)];
            input.seek(input.length() - tail.length);
            input.readFully(tail);
            return new String(tail, StandardCharsets.UTF_8);
        }
    }

    private void download(String address, String expected, File destination) throws Exception {
        if (destination.isFile() && sha256(destination).equals(expected)) return;
        File partial = new File(destination + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        try (var input = connection.getInputStream(); var output = new FileOutputStream(partial)) {
            input.transferTo(output);
        } finally { connection.disconnect(); }
        if (!sha256(partial).equals(expected)) throw new SecurityException("Rootfs checksum mismatch");
        Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private String sha256(File file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int length; (length = input.read(buffer)) != -1;) digest.update(buffer, 0, length);
        }
        return String.format(java.util.Locale.ROOT, "%064x", new java.math.BigInteger(1, digest.digest()));
    }

    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
