"""ADB helper restricted to this project's dedicated test AVD. Uses no root.

Usage: python scripts/avd.py shell <command...>
       python scripts/avd.py termux <command...>
       python scripts/avd.py screenshot <output.png>
       python scripts/avd.py prepare-termux
"""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import re
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("WINBRIDGE_SERIAL", "emulator-5580")
ADB = ["adb", "-s", SERIAL]


def adb(*args, **kwargs):
    return subprocess.run([*ADB, *args], check=True, **kwargs)


def shell(*args, **kwargs):
    return adb("shell", "-T", shlex.join(args), **kwargs)


def verify_target():
    if not re.fullmatch(r"emulator-\d+", SERIAL):
        raise SystemExit("Physical devices are forbidden for this project")
    name = adb("emu", "avd", "name", capture_output=True, text=True).stdout.splitlines()[0]
    if name != "WinBridge_Pixel9_API36":
        raise SystemExit(f"Refusing to operate on another AVD: {name!r}")


def main():
    verify_target()
    command, *args = sys.argv[1:]
    if command == "shell":
        shell(*args)
    elif command == "termux":
        shell("run-as", "com.termux", "sh", "-c",
              "export HOME=/data/data/com.termux/files/home PREFIX=/data/data/com.termux/files/usr "
              "TMPDIR=/data/data/com.termux/files/usr/tmp PATH=/data/data/com.termux/files/usr/bin:/system/bin; "
              + shlex.join(args))
    elif command == "linux":
        package = "io.github.tqmane.winbridge"
        apk = shell("pm", "path", package, capture_output=True, text=True).stdout.strip().removeprefix("package:")
        native = apk.rsplit("/", 1)[0] + "/lib/x86_64"
        files = "/data/user/0/" + package + "/files"
        environment = ["LD_LIBRARY_PATH=" + native, "PROOT_TMP_DIR=" + files + "/run",
                       "PROOT_LOADER=" + native + "/libproot-loader.so", "PROOT_LOADER_32=" + native + "/libproot-loader32.so"]
        process = [native + "/libproot.so", "-0", "-r", files + "/linux", "-w", "/root",
                   "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", files + "/run:/tmp",
                   "-b", files + "/runtime:/winbridge", "/usr/bin/env", "-i", "HOME=/root",
                   "PATH=/opt/soda/bin:/usr/bin:/bin", "DISPLAY=:1", "XAUTHORITY=/winbridge/.Xauthority", *args]
        shell("run-as", package, "sh", "-c", "export " + shlex.join(environment) + "; exec " + shlex.join(process))
    elif command == "screenshot":
        Path(args[0]).write_bytes(adb("exec-out", "screencap", "-p", capture_output=True).stdout)
    elif command == "click":
        shell("uiautomator", "dump", "/data/local/tmp/winbridge-ui.xml", capture_output=True)
        xml = shell("cat", "/data/local/tmp/winbridge-ui.xml", capture_output=True).stdout
        nodes = [n for n in ET.fromstring(xml).iter("node") if n.get("text", "").lower() == args[0].lower()]
        if len(nodes) != 1:
            raise SystemExit(f"Expected one control named {args[0]!r}, found {len(nodes)}")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
        shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    elif command == "prepare-termux":
        shell("run-as", "com.termux", "sh", "-c",
              "mkdir -p files/home/.termux; "
              "printf '\nallow-external-apps = true\n' >> files/home/.termux/termux.properties")
        shell("pm", "grant", "io.github.tqmane.winbridge", "com.termux.permission.RUN_COMMAND")
        for package in ("com.termux", "com.termux.x11"):
            shell("pm", "grant", package, "android.permission.POST_NOTIFICATIONS")
    else:
        raise SystemExit("Unknown command")


if __name__ == "__main__":
    main()
