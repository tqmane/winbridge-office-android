"""Exercise Android keyboard -> selected input backend -> Wine -> saved bytes.

Requires an installed, initialized runtime and a blank Notepad in the foreground.
Uses X11 automation only to select and inspect window state, never to inject input.
"""
from pathlib import Path
import subprocess
import sys
import time
import uuid
import avd

avd.verify_target()
helper = str(Path(__file__).with_name("avd.py"))


def linux(*args, check=True):
    return subprocess.run([sys.executable, helper, "linux", *args], check=check, capture_output=True)


def wait_for(predicate, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.5)
    raise AssertionError("Timed out waiting for the Windows UI")


disabled = avd.shell("pm", "list", "packages", "-d", capture_output=True).stdout.splitlines()
assert b"package:com.termux" in disabled and b"package:com.termux.x11" in disabled
size = avd.shell("wm", "size", capture_output=True).stdout
assert b"2560x1600" in size, size
window = wait_for(lambda: linux("xdotool", "search", "--name", "^Untitled - Notepad$", check=False).stdout.strip()).splitlines()[-1].decode()
linux("xdotool", "windowactivate", "--sync", window)
token = uuid.uuid4().hex[:10]
payload = "winbridge android keyboard " + token
name = "input-proof-" + token + ".txt"
avd.shell("input", "keyboard", "text", payload.replace(" ", "%s"))
avd.shell("input", "keyboard", "keycombination", "113", "47")  # Ctrl+S
wait_for(lambda: linux("xdotool", "search", "--name", "^Save As$", check=False).returncode == 0)
avd.shell("input", "keyboard", "text", "Z:\\winbridge\\" + name)
avd.shell("input", "keyboard", "keyevent", "KEYCODE_ENTER")


def saved_bytes():
    # shell v2 preserves the exit status; exec-out merges errors into a successful raw stream.
    result = subprocess.run([*avd.ADB, "shell", "-T", "run-as", "io.github.tqmane.winbridge",
                             "cat", "files/runtime/" + name], capture_output=True)
    return result.stdout if result.returncode == 0 else None


contents = wait_for(saved_bytes)
assert contents == payload.encode(), (contents, payload)
wait_for(lambda: name.encode() in linux("xdotool", "getwindowname", window).stdout)
unauthorized = linux("env", "XAUTHORITY=/tmp/nonexistent", "xdpyinfo", check=False)
assert unauthorized.returncode != 0 and b"Authorization required" in unauthorized.stderr
time.sleep(.5)
output = Path(__file__).resolve().parents[1] / "artifacts/notepad-input-pass.png"
output.write_bytes(avd.adb("exec-out", "screencap", "-p", capture_output=True).stdout)
print("PASS: tablet display, disabled companions, Android typing/Ctrl+S/Enter, saved bytes, X11 authentication")
print("Saved fixture:", name)
