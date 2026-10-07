/* SPDX-License-Identifier: GPL-3.0-or-later
 * Android input -> authenticated loopback stream -> Win32 SendInput.
 * This program never captures input and never accepts commands or filenames.
 */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0a00
#include <winsock2.h>
#include <windows.h>
#include <bcrypt.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>

static BCRYPT_ALG_HANDLE algorithm;
static unsigned char key[32], held[256];
static unsigned buttons;

static int transfer(SOCKET s, void *buffer, int length, int sending)
{
    char *p = buffer;
    while (length) {
        int n = sending ? send(s, p, length, 0) : recv(s, p, length, 0);
        if (n <= 0) return 0;
        p += n;
        length -= n;
    }
    return 1;
}

static int mac(const unsigned char *data, ULONG length, unsigned char result[32])
{
    return BCryptHash(algorithm, key, sizeof(key), (PUCHAR)data, length, result, 32) == 0;
}

static int same(const unsigned char *a, const unsigned char *b)
{
    unsigned difference = 0;
    for (int i = 0; i < 32; i++) difference |= a[i] ^ b[i];
    return difference == 0;
}

static int decode(const unsigned char packet[20], INPUT input[2])
{
    uint32_t words[5];
    memcpy(words, packet, sizeof(words));
    for (int i = 0; i < 5; i++) words[i] = ntohl(words[i]);
    int32_t a = words[1], b = words[2], c = words[3];
    memset(input, 0, sizeof(INPUT) * 2);
    switch (words[0]) {
    case 0: return 0; /* heartbeat */
    case 1: /* Windows virtual key */
        if (a < 1 || a > 254 || (b != 0 && b != 1)) return -1;
        input[0].type = INPUT_KEYBOARD;
        input[0].ki.wVk = (WORD)a;
        input[0].ki.wScan = (WORD)MapVirtualKeyW(a, MAPVK_VK_TO_VSC);
        input[0].ki.dwFlags = b ? 0 : KEYEVENTF_KEYUP;
        if (a == VK_RCONTROL || a == VK_RMENU || a == VK_LWIN || a == VK_RWIN ||
            (a >= VK_PRIOR && a <= VK_DOWN) || a == VK_INSERT || a == VK_DELETE)
            input[0].ki.dwFlags |= KEYEVENTF_EXTENDEDKEY;
        return 1;
    case 2: /* UTF-16 code unit, including surrogate pairs */
        if (a < 0 || a > 65535) return -1;
        input[0].type = INPUT_KEYBOARD;
        input[0].ki.wScan = (WORD)a;
        input[0].ki.dwFlags = KEYEVENTF_UNICODE;
        input[1] = input[0];
        input[1].ki.dwFlags |= KEYEVENTF_KEYUP;
        return 2;
    case 3: /* position plus optional button: low byte=button, bit 8=down, bit 9=relative */
        if ((c & ~0x303) || (c & 255) > 3 || a < -65535 || a > 65535 || b < -65535 || b > 65535) return -1;
        input[0].type = INPUT_MOUSE;
        input[0].mi.dx = a;
        input[0].mi.dy = b;
        input[0].mi.dwFlags = MOUSEEVENTF_MOVE;
        if (!(c & 512)) {
            int w = GetSystemMetrics(SM_CXSCREEN), h = GetSystemMetrics(SM_CYSCREEN);
            if (w < 2 || h < 2) return -1;
            input[0].mi.dx = (LONG)((int64_t)max(0, min(a, w - 1)) * 65535 / (w - 1));
            input[0].mi.dy = (LONG)((int64_t)max(0, min(b, h - 1)) * 65535 / (h - 1));
            input[0].mi.dwFlags |= MOUSEEVENTF_ABSOLUTE;
        }
        if ((c & 255) == 1) input[0].mi.dwFlags |= c & 256 ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
        if ((c & 255) == 2) input[0].mi.dwFlags |= c & 256 ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
        if ((c & 255) == 3) input[0].mi.dwFlags |= c & 256 ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
        return 1;
    case 4: /* wheel deltas in Windows WHEEL_DELTA units */
        if (a < -12000 || a > 12000 || b < -12000 || b > 12000) return -1;
        input[0].type = input[1].type = INPUT_MOUSE;
        input[0].mi.dwFlags = MOUSEEVENTF_HWHEEL;
        input[0].mi.mouseData = (DWORD)a;
        input[1].mi.dwFlags = MOUSEEVENTF_WHEEL;
        input[1].mi.mouseData = (DWORD)b;
        return 2;
    case 5: /* mirror Android lock-key toggles */
        if ((a != VK_CAPITAL && a != VK_NUMLOCK && a != VK_SCROLL) || (b != 0 && b != 1)) return -1;
        if ((GetKeyState(a) & 1) == b) return 0;
        input[0].type = INPUT_KEYBOARD;
        input[0].ki.wVk = (WORD)a;
        input[1] = input[0];
        input[1].ki.dwFlags = KEYEVENTF_KEYUP;
        return 2;
    default: return -1;
    }
}

static void release_all(void)
{
    INPUT input = {0};
    input.type = INPUT_KEYBOARD;
    input.ki.dwFlags = KEYEVENTF_KEYUP;
    for (int vk = 1; vk < 255; vk++) if (held[vk]) {
        input.ki.wVk = (WORD)vk;
        SendInput(1, &input, sizeof(input));
    }
    memset(held, 0, sizeof(held));
    memset(&input, 0, sizeof(input));
    input.type = INPUT_MOUSE;
    if (buttons & 2) input.mi.dwFlags |= MOUSEEVENTF_LEFTUP;
    if (buttons & 4) input.mi.dwFlags |= MOUSEEVENTF_MIDDLEUP;
    if (buttons & 8) input.mi.dwFlags |= MOUSEEVENTF_RIGHTUP;
    if (input.mi.dwFlags) SendInput(1, &input, sizeof(input));
    buttons = 0;
}

static void session(SOCKET client)
{
    unsigned char data[52], packet[52], digest[32];
    uint32_t sequence = 0;
    unsigned events = 0;
    DWORD timeout = 3000;
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout));
    setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, (char *)&timeout, sizeof(timeout));
    if (BCryptGenRandom(NULL, data, 32, BCRYPT_USE_SYSTEM_PREFERRED_RNG)) return;
    if (!transfer(client, data, 32, 1)) return;
    while (transfer(client, packet, sizeof(packet), 0)) {
        uint32_t received;
        memcpy(&received, packet + 16, sizeof(received));
        if (ntohl(received) != ++sequence || !sequence) break;
        memcpy(data + 32, packet, 20);
        if (!mac(data, sizeof(data), digest) || !same(digest, packet + 20)) break;
        INPUT input[2];
        int count = decode(packet, input);
        if (count < 0 || (count && SendInput(count, input, sizeof(INPUT)) != (UINT)count)) break;
        if (count == 1 && input[0].type == INPUT_KEYBOARD)
            held[input[0].ki.wVk] = !(input[0].ki.dwFlags & KEYEVENTF_KEYUP);
        if (count == 1 && input[0].type == INPUT_MOUSE) {
            DWORD flags = input[0].mi.dwFlags;
            const DWORD down[] = {MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_RIGHTDOWN};
            const DWORD up[] = {MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_RIGHTUP};
            for (int i = 0; i < 3; i++) {
                if (flags & down[i]) buttons |= 2u << i;
                if (flags & up[i]) buttons &= ~(2u << i);
            }
        }
        if (sequence == 1) { unsigned char ok = 1; if (!transfer(client, &ok, 1, 1)) break; }
        events += count;
    }
    release_all();
    printf("Direct input session ended: %u Win32 input events\n", events);
    fflush(stdout);
}

static int self_test(void)
{
    /* RFC 4231 test case 1, padded HMAC key is equivalent to its 20-byte form. */
    static const unsigned char expected[32] = {0xb0,0x34,0x4c,0x61,0xd8,0xdb,0x38,0x53,0x5c,0xa8,0xaf,0xce,0xaf,0x0b,0xf1,0x2b,0x88,0x1d,0xc2,0x00,0xc9,0x83,0x3d,0xa7,0x26,0xe9,0x37,0x6c,0x2e,0x32,0xcf,0xf7};
    unsigned char digest[32];
    memset(key, 0x0b, 20);
    assert(mac((const unsigned char *)"Hi There", 8, digest) && same(digest, expected));
    digest[31] ^= 1;
    assert(!same(digest, expected));
    INPUT input[2];
    uint32_t packet[5] = {htonl(1), htonl(VK_RCONTROL), htonl(1), 0, htonl(1)};
    assert(decode((unsigned char *)packet, input) == 1 && input[0].ki.dwFlags == KEYEVENTF_EXTENDEDKEY);
    packet[1] = htonl(256);
    assert(decode((unsigned char *)packet, input) == -1);
    packet[0] = htonl(2); packet[1] = htonl(0xd83d);
    assert(decode((unsigned char *)packet, input) == 2 && input[1].ki.wScan == 0xd83d);
    packet[0] = htonl(3); packet[3] = htonl(4);
    assert(decode((unsigned char *)packet, input) == -1);
    puts("PASS: HMAC authentication, tampering, key range, extended modifier, Unicode, invalid pointer packet");
    return 0;
}

int main(int argc, char **argv)
{
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, NULL, BCRYPT_ALG_HANDLE_HMAC_FLAG)) return 1;
    if (argc == 2 && !strcmp(argv[1], "--self-test")) return self_test();
    /* Fail closed outside the explicitly configured app runtime. */
    FILE *file = fopen("Z:\\winbridge\\.input-key", "rb");
    if (!file) return 2;
    size_t length = fread(key, 1, sizeof(key), file);
    fclose(file);
    if (length != sizeof(key)) return 2;
    HANDLE mutex = CreateMutexW(NULL, TRUE, L"WinBridgeDirectInput");
    if (!mutex || GetLastError() == ERROR_ALREADY_EXISTS) return 0;
    WSADATA ws;
    if (WSAStartup(MAKEWORD(2,2), &ws)) return 3;
    SetProcessDPIAware();
    SOCKET listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (listener == INVALID_SOCKET || bind(listener, (struct sockaddr *)&address, sizeof(address)) || listen(listener, 2)) return 3;
    int size = sizeof(address);
    if (getsockname(listener, (struct sockaddr *)&address, &size)) return 3;
    file = fopen("Z:\\winbridge\\.input-port.new", "wb");
    if (!file) return 4;
    fprintf(file, "%u\n", ntohs(address.sin_port));
    if (fclose(file) || !MoveFileExW(L"Z:\\winbridge\\.input-port.new", L"Z:\\winbridge\\.input-port", MOVEFILE_REPLACE_EXISTING)) return 4;
    puts("Direct Windows input ready (authenticated loopback, no X11 input forwarding)");
    fflush(stdout);
    for (;;) {
        SOCKET client = accept(listener, NULL, NULL);
        if (client == INVALID_SOCKET) return 5;
        session(client);
        closesocket(client);
    }
}
