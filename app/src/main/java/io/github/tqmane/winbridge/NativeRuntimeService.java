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
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;

/** App-owned PRoot host. No companion package, shell UID or root privilege. */
public final class NativeRuntimeService extends Service {
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("runtime", "Windows runtime", NotificationManager.IMPORTANCE_LOW));
        var pending = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(11, new Notification.Builder(this, "runtime").setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("WinBridge runtime").setContentText("Initializing app-owned Linux environment")
            .setContentIntent(pending).setOngoing(true).build());
        worker.execute(() -> {
            try {
                initializeRootfs();
                if ("init".equals(intent.getAction())) {
                    status("Installing Wine and its Linux dependencies. Logs are stored privately in WinBridge.");
                    File setup = new File(directory("runtime"), "linux-setup.sh");
                    try (var input = getAssets().open("linux-setup.sh")) {
                        Files.copy(input, setup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    status(linux("/bin/bash", "/winbridge/linux-setup.sh"));
                } else {
                    String result = linux("/bin/sh", "-c", "uname -m; /usr/bin/ldd --version; cat /etc/os-release; id");
                    if (!result.contains("x86_64") || !result.contains("Ubuntu")) throw new IllegalStateException("Linux smoke check failed: " + result);
                    status("Integrated runtime passed (app UID, no Termux service):\n" + result);
                }
            } catch (Exception error) { status("Integrated runtime failed: " + error); }
            finally { stopSelf(startId); }
        });
        return START_NOT_STICKY;
    }

    private void status(String message) {
        sendBroadcast(new Intent(this, RuntimeResult.class).putExtra("message", message));
    }

    private File directory(String name) throws Exception {
        File path = new File(getFilesDir(), name);
        if (!path.isDirectory() && !path.mkdirs()) throw new IllegalStateException("Cannot create " + name);
        return path;
    }

    private void initializeRootfs() throws Exception {
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
        return execute(builder);
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
        File log = new File(directory("logs"), "command-" + System.currentTimeMillis() + ".log");
        Process process = builder.redirectErrorStream(true).redirectOutput(log).start();
        int exit = process.waitFor();
        String text;
        try (var input = new java.io.RandomAccessFile(log, "r")) {
            byte[] tail = new byte[(int) Math.min(input.length(), 65536)];
            input.seek(input.length() - tail.length);
            input.readFully(tail);
            text = new String(tail, StandardCharsets.UTF_8);
        }
        if (exit != 0) throw new IllegalStateException("Exit " + exit + ": " + text);
        return text;
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
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
