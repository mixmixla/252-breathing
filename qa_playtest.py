# -*- coding: utf-8 -*-
# QA 2026-09-13：自动化试玩代理 v2（修复 GetDIBits BITMAPINFO）
import ctypes, ctypes.wintypes, os, struct, subprocess, sys, time

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32
GAME_HWND = 0
OUT = os.path.join('..', '..', 'diagnostics', 'playtest')
os.makedirs(OUT, exist_ok=True)

class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [('biSize', ctypes.c_uint32), ('biWidth', ctypes.c_int32), ('biHeight', ctypes.c_int32),
                ('biPlanes', ctypes.c_uint16), ('biBitCount', ctypes.c_uint16), ('biCompression', ctypes.c_uint32),
                ('biSizeImage', ctypes.c_uint32), ('biXPelsPerMeter', ctypes.c_int32),
                ('biYPelsPerMeter', ctypes.c_int32), ('biClrUsed', ctypes.c_uint32), ('biClrImportant', ctypes.c_uint32)]

class BITMAPINFO(ctypes.Structure):
    _fields_ = [('bmiHeader', BITMAPINFOHEADER), ('bmiColors', ctypes.c_uint32 * 3)]

def find_game_window():
    result = []
    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.wintypes.HWND, ctypes.wintypes.LPARAM)
    def cb(hwnd, lp):
        buf = ctypes.create_unicode_buffer(256)
        user32.GetWindowTextW(hwnd, buf, 256)
        if 'Breathing World' in buf.value:
            result.append((hwnd, buf.value))
        return True
    user32.EnumWindows(cb, 0)
    return result[0] if result else (None, None)

def focus_window(hwnd):
    user32.ShowWindow(hwnd, 9)
    user32.SetForegroundWindow(hwnd)
    time.sleep(0.4)

def click_center(hwnd):
    r = ctypes.wintypes.RECT()
    user32.GetClientRect(hwnd, ctypes.byref(r))
    pt = ctypes.wintypes.POINT(0, 0)
    user32.ClientToScreen(hwnd, ctypes.byref(pt))
    user32.SetCursorPos(pt.x + r.right // 2, pt.y + r.bottom // 2)
    user32.mouse_event(2, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(4, 0, 0, 0, 0)

VK = {'W': 0x57, 'A': 0x41, 'S': 0x53, 'D': 0x44, 'SPACE': 0x20, 'H': 0x48,
      'F3': 0x72, 'F7': 0x76, 'ESC': 0x1B}
def key_down(vk): user32.keybd_event(vk, 0, 0, 0)
def key_up(vk): user32.keybd_event(vk, 0, 2, 0)
def tap(vk, hold=0.08):
    key_down(vk); time.sleep(hold); key_up(vk)

def shot(name):
    w = user32.GetSystemMetrics(0) // 2
    h = user32.GetSystemMetrics(1) // 2
    hdc = user32.GetDC(0)
    mem = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, w, h)
    gdi32.SelectObject(mem, bmp)
    gdi32.BitBlt(mem, 0, 0, w, h, hdc, 0, 0, 0x00CC0020)
    row = (w * 3 + 3) // 4 * 4
    data = ctypes.create_string_buffer(row * h)
    bmi = BITMAPINFO()
    bmi.bmiHeader.biSize = 40
    bmi.bmiHeader.biWidth = w
    bmi.bmiHeader.biHeight = -h          # 顶向下
    bmi.bmiHeader.biPlanes = 1
    bmi.bmiHeader.biBitCount = 24
    gdi32.GetDIBits(mem, bmp, 0, h, data, ctypes.byref(bmi), 0)
    hdr = b'BM' + struct.pack('<I', 54 + row * h) + b'\x00\x00\x00\x00' + struct.pack('<I', 54)
    bi = struct.pack('<IiiHHIIiiII', 40, w, -h, 1, 24, 0, row * h, 0, 0, 0, 0)
    with open(os.path.join(OUT, name + '.bmp'), 'wb') as f:
        f.write(hdr + bi + data.raw)
    gdi32.DeleteObject(bmp); gdi32.DeleteDC(mem); user32.ReleaseDC(0, hdc)
    # PrintWindow 抓窗口内容版（即使被遮挡）
    try:
        r2 = ctypes.wintypes.RECT(); user32.GetClientRect(GAME_HWND, ctypes.byref(r2))
        w2, h2 = r2.right, r2.bottom
        hdc2 = user32.GetDC(GAME_HWND)
        mem2 = gdi32.CreateCompatibleDC(hdc2)
        bmp2 = gdi32.CreateCompatibleBitmap(hdc2, w2, h2)
        gdi32.SelectObject(mem2, bmp2)
        user32.PrintWindow(GAME_HWND, mem2, 1)
        row2 = (w2 * 3 + 3) // 4 * 4
        data2 = ctypes.create_string_buffer(row2 * h2)
        bmi2 = BITMAPINFO()
        bmi2.bmiHeader.biSize = 40; bmi2.bmiHeader.biWidth = w2; bmi2.bmiHeader.biHeight = -h2
        bmi2.bmiHeader.biPlanes = 1; bmi2.bmiHeader.biBitCount = 24
        gdi32.GetDIBits(mem2, bmp2, 0, h2, data2, ctypes.byref(bmi2), 0)
        hdr2 = b'BM' + struct.pack('<I', 54 + row2 * h2) + struct.pack('<HH', 0, 0) + struct.pack('<I', 54)
        bi2 = struct.pack('<IiiHHIIiiII', 40, w2, -h2, 1, 24, 0, row2 * h2, 0, 0, 0, 0)
        with open(os.path.join(OUT, name + '_win.bmp'), 'wb') as f:
            f.write(hdr2 + bi2 + data2.raw)
        gdi32.DeleteObject(bmp2); gdi32.DeleteDC(mem2); user32.ReleaseDC(GAME_HWND, hdc2)
    except Exception as ex:
        print('printwindow fail:', ex, flush=True)
    print('shot', name, flush=True)

print('=== launch ===', flush=True)

# ---- IME 切换：测试前切英文键盘（字母键不被输入法拦截），测试后恢复 ----
orig_hkl = user32.GetKeyboardLayout(0)
print('orig hkl=', hex(orig_hkl), flush=True)
EN_HKL = user32.LoadKeyboardLayoutW('00000409', 1)   # KLF_ACTIVATE：美式英文
print('switched to en hkl=', hex(EN_HKL), flush=True)

subprocess.Popen(['cmd', '/c', 'run-game.bat'], cwd=os.path.dirname(os.path.abspath(__file__)),
                 creationflags=subprocess.CREATE_NEW_CONSOLE)
print('wait 30s...', flush=True)
time.sleep(30)
hwnd, title = find_game_window()
GAME_HWND = hwnd
print('window:', title, 'hwnd=', hwnd, flush=True)
if not hwnd:
    print('FAIL: no window'); sys.exit(1)
def wait_foreground(hwnd, tries=15):
    for i in range(tries):
        user32.SetForegroundWindow(hwnd)
        time.sleep(0.4)
        if user32.GetForegroundWindow() == hwnd:
            print('foreground CONFIRMED after', i + 1, 'tries', flush=True)
            return True
    print("FOREGROUND FAILED - abort (no blind keys)", flush=True)
    return False

if not wait_foreground(hwnd):
    user32.PostMessageW(hwnd, 0x0010, 0, 0)
    sys.exit(1)
time.sleep(1)
user32.ShowWindow(hwnd, 3)   # SW_MAXIMIZE：铺满屏幕，截图必可见
print("maximized", flush=True)
time.sleep(1.5)

shot('A_startup')
click_center(hwnd); time.sleep(0.5)

key_down(VK['W']); time.sleep(2.0); key_up(VK['W'])
shot('B_after_move')

tap(VK['SPACE'], 0.1); time.sleep(0.8)
shot('C_after_jump')

tap(VK['H']); time.sleep(0.8); shot('D_hud_off')
tap(VK['H']); time.sleep(0.8); shot('E_hud_on')
tap(VK['F3']); time.sleep(0.8); shot('F_f3_panel')
tap(VK['F7']); time.sleep(1.5); shot('G_after_f7')
tap(VK['ESC']); time.sleep(0.8); shot('H_menu')
tap(VK['ESC']); time.sleep(0.5)
click_center(hwnd); time.sleep(0.3)
key_down(VK['D']); time.sleep(1.5); key_up(VK['D'])
shot('I_final')

print('=== closing ===', flush=True)
user32.PostMessageW(hwnd, 0x0010, 0, 0)
time.sleep(3)
user32.LoadKeyboardLayoutW('00000409', 0x100)   # KLF_NOTELLSHELL + 恢复测试前布局
user32.ActivateKeyboardLayout(orig_hkl, 0)
print('layout restored to', hex(orig_hkl), flush=True)
print('=== playtest done ===', flush=True)
