package dev.novab.autismtv.client;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import org.lwjgl.glfw.GLFW;

final class WindowsInputBridge {
    private static final User32 USER32 = User32.INSTANCE;
    private static final User32Ext USER32_EXT = User32Ext.INSTANCE;
    private static final int MK_LBUTTON = 0x0001;
    private static final int MK_RBUTTON = 0x0002;
    private static final int WM_MOUSEMOVE = 0x0200;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int WM_RBUTTONDOWN = 0x0204;
    private static final int WM_RBUTTONUP = 0x0205;

    private WindowsInputBridge() {
    }

    static boolean isSupported() {
        return Platform.isWindows();
    }

    static TargetWindow resolveWindowAt(int screenX, int screenY) {
        if (!isSupported()) {
            return null;
        }

        POINT.ByValue point = new POINT.ByValue();
        point.x = screenX;
        point.y = screenY;
        HWND hwnd = USER32_EXT.WindowFromPoint(point);

        if (hwnd == null) {
            return null;
        }

        return new TargetWindow(hwnd);
    }

    static boolean sendMouseButton(TargetWindow targetWindow, int screenX, int screenY, int button, int action) {
        if (!isSupported() || targetWindow == null) {
            return false;
        }

        int downMessage;
        int upMessage;
        int buttonMask;

        switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                downMessage = WM_LBUTTONDOWN;
                upMessage = WM_LBUTTONUP;
                buttonMask = MK_LBUTTON;
            }
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                downMessage = WM_RBUTTONDOWN;
                upMessage = WM_RBUTTONUP;
                buttonMask = MK_RBUTTON;
            }
            default -> {
                return false;
            }
        }

        POINT clientPoint = toClientPoint(targetWindow.hwnd, screenX, screenY);

        if (clientPoint == null) {
            return false;
        }

        LPARAM lParam = toMouseLParam(clientPoint.x, clientPoint.y);
        USER32.PostMessage(targetWindow.hwnd, WM_MOUSEMOVE, new WPARAM(0), lParam);

        if (action == GLFW.GLFW_PRESS) {
            USER32.PostMessage(targetWindow.hwnd, downMessage, new WPARAM(buttonMask), lParam);
            return true;
        }

        if (action == GLFW.GLFW_RELEASE) {
            USER32.PostMessage(targetWindow.hwnd, upMessage, new WPARAM(0), lParam);
            return true;
        }

        return false;
    }

    static boolean sendKey(TargetWindow targetWindow, int glfwKey, int action) {
        if (!isSupported() || targetWindow == null) {
            return false;
        }

        int virtualKey = mapGlfwKeyToWin32(glfwKey);

        if (virtualKey == 0) {
            return false;
        }

        int scanCode = USER32.MapVirtualKeyEx(virtualKey, 0, USER32.GetKeyboardLayout(0));
        long lParamValue = 1L | ((long) scanCode << 16);

        if (action == GLFW.GLFW_RELEASE) {
            lParamValue |= 1L << 30;
            lParamValue |= 1L << 31;
            USER32.PostMessage(targetWindow.hwnd, WinUser.WM_KEYUP, new WPARAM(virtualKey), new LPARAM(lParamValue));
            return true;
        }

        if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
            USER32.PostMessage(targetWindow.hwnd, WinUser.WM_KEYDOWN, new WPARAM(virtualKey), new LPARAM(lParamValue));
            return true;
        }

        return false;
    }

    static boolean sendChar(TargetWindow targetWindow, int codepoint) {
        if (!isSupported() || targetWindow == null) {
            return false;
        }

        USER32.PostMessage(targetWindow.hwnd, WinUser.WM_CHAR, new WPARAM(codepoint), new LPARAM(1));
        return true;
    }

    private static POINT toClientPoint(HWND hwnd, int screenX, int screenY) {
        POINT point = new POINT();
        point.x = screenX;
        point.y = screenY;

        if (!USER32_EXT.ScreenToClient(hwnd, point)) {
            return null;
        }

        return point;
    }

    private static LPARAM toMouseLParam(int x, int y) {
        return new LPARAM((x & 0xFFFFL) | ((y & 0xFFFFL) << 16));
    }

    private static int mapGlfwKeyToWin32(int glfwKey) {
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_A -> 0x41;
            case GLFW.GLFW_KEY_B -> 0x42;
            case GLFW.GLFW_KEY_C -> 0x43;
            case GLFW.GLFW_KEY_D -> 0x44;
            case GLFW.GLFW_KEY_E -> 0x45;
            case GLFW.GLFW_KEY_F -> 0x46;
            case GLFW.GLFW_KEY_G -> 0x47;
            case GLFW.GLFW_KEY_H -> 0x48;
            case GLFW.GLFW_KEY_I -> 0x49;
            case GLFW.GLFW_KEY_J -> 0x4A;
            case GLFW.GLFW_KEY_K -> 0x4B;
            case GLFW.GLFW_KEY_L -> 0x4C;
            case GLFW.GLFW_KEY_M -> 0x4D;
            case GLFW.GLFW_KEY_N -> 0x4E;
            case GLFW.GLFW_KEY_O -> 0x4F;
            case GLFW.GLFW_KEY_P -> 0x50;
            case GLFW.GLFW_KEY_Q -> 0x51;
            case GLFW.GLFW_KEY_R -> 0x52;
            case GLFW.GLFW_KEY_S -> 0x53;
            case GLFW.GLFW_KEY_T -> 0x54;
            case GLFW.GLFW_KEY_U -> 0x55;
            case GLFW.GLFW_KEY_V -> 0x56;
            case GLFW.GLFW_KEY_W -> 0x57;
            case GLFW.GLFW_KEY_X -> 0x58;
            case GLFW.GLFW_KEY_Y -> 0x59;
            case GLFW.GLFW_KEY_Z -> 0x5A;
            case GLFW.GLFW_KEY_0 -> 0x30;
            case GLFW.GLFW_KEY_1 -> 0x31;
            case GLFW.GLFW_KEY_2 -> 0x32;
            case GLFW.GLFW_KEY_3 -> 0x33;
            case GLFW.GLFW_KEY_4 -> 0x34;
            case GLFW.GLFW_KEY_5 -> 0x35;
            case GLFW.GLFW_KEY_6 -> 0x36;
            case GLFW.GLFW_KEY_7 -> 0x37;
            case GLFW.GLFW_KEY_8 -> 0x38;
            case GLFW.GLFW_KEY_9 -> 0x39;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;
            case GLFW.GLFW_KEY_DELETE -> 0x2E;
            case GLFW.GLFW_KEY_TAB -> 0x09;
            case GLFW.GLFW_KEY_INSERT -> 0x2D;
            case GLFW.GLFW_KEY_HOME -> 0x24;
            case GLFW.GLFW_KEY_END -> 0x23;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x22;
            case GLFW.GLFW_KEY_LEFT -> 0x25;
            case GLFW.GLFW_KEY_UP -> 0x26;
            case GLFW.GLFW_KEY_RIGHT -> 0x27;
            case GLFW.GLFW_KEY_DOWN -> 0x28;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> 0x10;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> 0x11;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> 0x12;
            case GLFW.GLFW_KEY_LEFT_SUPER -> 0x5B;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> 0x5C;
            case GLFW.GLFW_KEY_F1 -> 0x70;
            case GLFW.GLFW_KEY_F2 -> 0x71;
            case GLFW.GLFW_KEY_F3 -> 0x72;
            case GLFW.GLFW_KEY_F4 -> 0x73;
            case GLFW.GLFW_KEY_F5 -> 0x74;
            case GLFW.GLFW_KEY_F6 -> 0x75;
            case GLFW.GLFW_KEY_F7 -> 0x76;
            case GLFW.GLFW_KEY_F8 -> 0x77;
            case GLFW.GLFW_KEY_F9 -> 0x78;
            case GLFW.GLFW_KEY_F10 -> 0x79;
            case GLFW.GLFW_KEY_F11 -> 0x7A;
            case GLFW.GLFW_KEY_F12 -> 0x7B;
            default -> 0;
        };
    }

    record TargetWindow(HWND hwnd) {
    }

    private interface User32Ext extends StdCallLibrary {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

        HWND WindowFromPoint(POINT.ByValue point);

        boolean ScreenToClient(HWND hwnd, POINT point);
    }
}