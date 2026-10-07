"""Stop and relaunch Wine in the dedicated development AVD (save documents first)."""
from pathlib import Path
import re
import subprocess
import sys
import time
import avd

avd.verify_target()
helper = str(Path(__file__).with_name("avd.py"))


def control(*args):
    subprocess.run([sys.executable, helper, *args], check=True)


# Two independent Wine launch sessions exposed a PRoot SIGTERM-ignore bug.
for _ in range(2):
    avd.shell("am", "start", "-W", "-n", "io.github.tqmane.winbridge/.MainActivity")
    control("click", "Wine Notepad")
deadline = time.monotonic() + 120
while True:
    windows = subprocess.run([sys.executable, helper, "linux", "xdotool", "search", "--name", "^Untitled - Notepad$"], capture_output=True)
    if len(windows.stdout.splitlines()) >= 2:
        break
    if time.monotonic() > deadline:
        raise AssertionError("Two Notepad sessions did not appear")
    time.sleep(.5)
avd.shell("am", "start", "-W", "-n", "io.github.tqmane.winbridge/.MainActivity")
control("click", "Stop Wine")
control("click", "Continue")
deadline = time.monotonic() + 30
while True:
    processes = avd.shell("ps", "-A", "-o", "NAME", capture_output=True).stdout
    if not re.search(rb"(?:notepad\.exe|windows-input\.exe|wineserver)\r?$", processes, re.M):
        break
    if time.monotonic() > deadline:
        raise AssertionError("Wine processes survived Stop Wine")
    time.sleep(.5)
print("PASS: Wine processes terminated", flush=True)
started = time.monotonic()
control("click", "Wine Notepad")
deadline = time.monotonic() + 120
while True:
    window = subprocess.run([sys.executable, helper, "linux", "xdotool", "search", "--name", "^Untitled - Notepad$"], capture_output=True)
    if window.returncode == 0 and window.stdout.strip():
        break
    if time.monotonic() > deadline:
        raise AssertionError("Notepad did not reappear after stopping Wine")
    time.sleep(1)
print(f"PASS: Notepad relaunched in {time.monotonic() - started:.1f}s", flush=True)
subprocess.run([sys.executable, str(Path(__file__).with_name("check_notepad.py"))], check=True)
