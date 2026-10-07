"""Check the opt-in Android -> Win32 input path on the dedicated AVD.

Requires a blank Notepad and Direct Windows input enabled. Only heartbeat
packets are used in authentication checks; actual input comes from Android.
"""
import hashlib
import hmac
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import avd

avd.verify_target()
package = "io.github.tqmane.winbridge"
prefs = avd.shell("run-as", package, "cat", "shared_prefs/settings.xml", capture_output=True).stdout
assert b'name="direct_input" value="true"' in prefs, "Enable Direct Windows input first"
key = avd.shell("run-as", package, "cat", "files/runtime/.input-key", capture_output=True).stdout
assert len(key) == 32
port = int(avd.shell("run-as", package, "cat", "files/runtime/.input-port", capture_output=True).stdout)
local = int(avd.adb("forward", "tcp:0", "tcp:" + str(port), capture_output=True).stdout)


def read_exact(connection, length):
    result = b""
    while len(result) < length:
        part = connection.recv(length - len(result))
        if not part:
            raise AssertionError("Unexpected connection EOF")
        result += part
    return result


def packet(challenge, secret):
    body = struct.pack("!5I", 0, 0, 0, 0, 1)
    return body + hmac.digest(secret, challenge + body, hashlib.sha256)


try:
    with socket.create_connection(("127.0.0.1", local), timeout=5) as connection:
        challenge = read_exact(connection, 32)
        connection.sendall(packet(challenge, bytes(32)))
        assert connection.recv(1) == b"", "Unauthenticated peer was accepted"
    with socket.create_connection(("127.0.0.1", local), timeout=5) as connection:
        challenge = read_exact(connection, 32)
        message = packet(challenge, key)
        connection.sendall(message)
        assert read_exact(connection, 1) == b"\x01"
        connection.sendall(message)
        assert connection.recv(1) == b"", "Replayed sequence was accepted"
finally:
    avd.adb("forward", "--remove", "tcp:" + str(local))

print("PASS: direct input rejects invalid authentication and replay", flush=True)
before = avd.shell("run-as", package, "cat", "files/runtime/logs/direct-input.log", capture_output=True).stdout
subprocess.run([sys.executable, str(Path(__file__).with_name("check_notepad.py"))], check=True)
# Pause the Android desktop to close the connection and release held inputs.
avd.shell("am", "start", "-W", "-n", package + "/.MainActivity", capture_output=True)
log = avd.shell("run-as", package, "cat", "files/runtime/logs/direct-input.log", capture_output=True).stdout
assert log.startswith(before), "Input log changed unexpectedly during the check"
counts = [int(n) for n in re.findall(rb"Direct input session ended: (\d+) Win32 input events", log[len(before):])]
assert counts and max(counts) >= 40, "No matching Win32 injection evidence"
print("PASS: Android keys/text and saved bytes through the direct Win32 backend")
