package nl.bartvandermeeren.dudan.desktop

import java.awt.event.KeyEvent as AwtKey

/**
 * A key a shortcut can use, with its AWT code (what Compose reports while dudan has the focus) and its
 * Win32 virtual-key code (what RegisterHotKey takes for the quick question). They differ for punctuation.
 */
enum class ShortcutKey(val label: String, val awt: Int, val win: Int) {
    A("A", AwtKey.VK_A, 0x41), B("B", AwtKey.VK_B, 0x42), C("C", AwtKey.VK_C, 0x43), D("D", AwtKey.VK_D, 0x44),
    E("E", AwtKey.VK_E, 0x45), F("F", AwtKey.VK_F, 0x46), G("G", AwtKey.VK_G, 0x47), H("H", AwtKey.VK_H, 0x48),
    I("I", AwtKey.VK_I, 0x49), J("J", AwtKey.VK_J, 0x4A), K("K", AwtKey.VK_K, 0x4B), L("L", AwtKey.VK_L, 0x4C),
    M("M", AwtKey.VK_M, 0x4D), N("N", AwtKey.VK_N, 0x4E), O("O", AwtKey.VK_O, 0x4F), P("P", AwtKey.VK_P, 0x50),
    Q("Q", AwtKey.VK_Q, 0x51), R("R", AwtKey.VK_R, 0x52), S("S", AwtKey.VK_S, 0x53), T("T", AwtKey.VK_T, 0x54),
    U("U", AwtKey.VK_U, 0x55), V("V", AwtKey.VK_V, 0x56), W("W", AwtKey.VK_W, 0x57), X("X", AwtKey.VK_X, 0x58),
    Y("Y", AwtKey.VK_Y, 0x59), Z("Z", AwtKey.VK_Z, 0x5A),
    Digit0("0", AwtKey.VK_0, 0x30), Digit1("1", AwtKey.VK_1, 0x31), Digit2("2", AwtKey.VK_2, 0x32), Digit3("3", AwtKey.VK_3, 0x33),
    Digit4("4", AwtKey.VK_4, 0x34), Digit5("5", AwtKey.VK_5, 0x35), Digit6("6", AwtKey.VK_6, 0x36), Digit7("7", AwtKey.VK_7, 0x37),
    Digit8("8", AwtKey.VK_8, 0x38), Digit9("9", AwtKey.VK_9, 0x39),
    F1("F1", AwtKey.VK_F1, 0x70), F2("F2", AwtKey.VK_F2, 0x71), F3("F3", AwtKey.VK_F3, 0x72), F4("F4", AwtKey.VK_F4, 0x73),
    F5("F5", AwtKey.VK_F5, 0x74), F6("F6", AwtKey.VK_F6, 0x75), F7("F7", AwtKey.VK_F7, 0x76), F8("F8", AwtKey.VK_F8, 0x77),
    F9("F9", AwtKey.VK_F9, 0x78), F10("F10", AwtKey.VK_F10, 0x79), F11("F11", AwtKey.VK_F11, 0x7A), F12("F12", AwtKey.VK_F12, 0x7B),
    Space("Space", AwtKey.VK_SPACE, 0x20), Enter("Enter", AwtKey.VK_ENTER, 0x0D), Tab("Tab", AwtKey.VK_TAB, 0x09),
    Backspace("Backspace", AwtKey.VK_BACK_SPACE, 0x08), Delete("Delete", AwtKey.VK_DELETE, 0x2E), Insert("Insert", AwtKey.VK_INSERT, 0x2D),
    Up("Up", AwtKey.VK_UP, 0x26), Down("Down", AwtKey.VK_DOWN, 0x28), Left("Left", AwtKey.VK_LEFT, 0x25), Right("Right", AwtKey.VK_RIGHT, 0x27),
    Home("Home", AwtKey.VK_HOME, 0x24), End("End", AwtKey.VK_END, 0x23), PageUp("PageUp", AwtKey.VK_PAGE_UP, 0x21), PageDown("PageDown", AwtKey.VK_PAGE_DOWN, 0x22),
    Comma(",", AwtKey.VK_COMMA, 0xBC), Period(".", AwtKey.VK_PERIOD, 0xBE), Minus("-", AwtKey.VK_MINUS, 0xBD), Equals("=", AwtKey.VK_EQUALS, 0xBB),
    Slash("/", AwtKey.VK_SLASH, 0xBF), Backslash("\\", AwtKey.VK_BACK_SLASH, 0xDC), Semicolon(";", AwtKey.VK_SEMICOLON, 0xBA),
    Quote("'", AwtKey.VK_QUOTE, 0xDE), OpenBracket("[", AwtKey.VK_OPEN_BRACKET, 0xDB), CloseBracket("]", AwtKey.VK_CLOSE_BRACKET, 0xDD),
    BackQuote("`", AwtKey.VK_BACK_QUOTE, 0xC0), NumPlus("Num +", AwtKey.VK_ADD, 0x6B), NumMinus("Num -", AwtKey.VK_SUBTRACT, 0x6D);

    val isFunctionKey: Boolean get() = awt in AwtKey.VK_F1..AwtKey.VK_F12

    companion object {
        fun fromAwt(code: Int): ShortcutKey? = entries.firstOrNull { it.awt == code }
        fun fromLabel(label: String): ShortcutKey? = entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
    }
}

/** A key with its modifiers, as Settings shows and saves it: "Ctrl+Alt+Space". */
data class KeyCombo(val key: ShortcutKey, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
    override fun toString(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(key.label)
    }

    fun matches(awtCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean =
        key.awt == awtCode && this.ctrl == ctrl && this.alt == alt && this.shift == shift

    /** RegisterHotKey's modifier flags. */
    val winModifiers: Int
        get() = (if (alt) GlobalShortcut.MOD_ALT else 0) or (if (ctrl) GlobalShortcut.MOD_CONTROL else 0) or (if (shift) GlobalShortcut.MOD_SHIFT else 0)

    companion object {
        /** The saved form back; null for an empty or unknown one. */
        fun parse(text: String): KeyCombo? {
            if (text.isBlank()) return null
            // "Num +" and "+" itself contain the separator, so the key is whatever follows the last modifier.
            var rest = text.trim()
            var ctrl = false
            var alt = false
            var shift = false
            while (true) {
                when {
                    rest.startsWith("Ctrl+", ignoreCase = true) -> { ctrl = true; rest = rest.substring(5) }
                    rest.startsWith("Alt+", ignoreCase = true) -> { alt = true; rest = rest.substring(4) }
                    rest.startsWith("Shift+", ignoreCase = true) -> { shift = true; rest = rest.substring(6) }
                    else -> break
                }
            }
            val key = ShortcutKey.fromLabel(rest) ?: return null
            return KeyCombo(key, ctrl, alt, shift)
        }
    }
}

/**
 * What a shortcut does. The quick question is [global]: Windows passes it to dudan over every app. The
 * rest work while a dudan window has the focus. Esc always closes the top layer, and isn't one of them.
 */
enum class ShortcutAction(val id: String, val default: KeyCombo?, val global: Boolean = false) {
    QuickAsk("quick_ask", KeyCombo(ShortcutKey.Space, ctrl = true, alt = true), global = true),
    NewChat("new_chat", KeyCombo(ShortcutKey.N, ctrl = true)),
    SearchChats("search_chats", KeyCombo(ShortcutKey.K, ctrl = true)),
    ToggleSidebar("toggle_sidebar", KeyCombo(ShortcutKey.B, ctrl = true)),
    PreviousChat("previous_chat", KeyCombo(ShortcutKey.Up, alt = true)),
    NextChat("next_chat", KeyCombo(ShortcutKey.Down, alt = true)),
    SettleChat("settle_chat", KeyCombo(ShortcutKey.E, ctrl = true)),
    ReadAloud("read_aloud", KeyCombo(ShortcutKey.U, ctrl = true, shift = true)),
    TextBigger("text_bigger", KeyCombo(ShortcutKey.Equals, ctrl = true)),
    TextSmaller("text_smaller", KeyCombo(ShortcutKey.Minus, ctrl = true)),
    TextReset("text_reset", KeyCombo(ShortcutKey.Digit0, ctrl = true)),
    OpenSettings("settings", KeyCombo(ShortcutKey.Comma, ctrl = true));

    companion object {
        fun fromId(id: String): ShortcutAction? = entries.firstOrNull { it.id == id }
    }
}

/** Why a combo can't be a shortcut, for the line under the recorder. */
enum class ShortcutProblem { NeedsModifier, Reserved, InUse }

object Shortcuts {
    /** The combo each action has: its own when the user set one (none counts), else the default. */
    fun resolve(saved: Map<String, String>): Map<ShortcutAction, KeyCombo?> =
        ShortcutAction.entries.associateWith { action -> if (action.id in saved) KeyCombo.parse(saved.getValue(action.id)) else action.default }

    /**
     * Whether [combo] can do [action] next to the [current] shortcuts. Shortcuts that work while typing need
     * Ctrl or Alt, except the function keys, and leave the text field's own keys alone. A global one needs
     * Ctrl or Alt even on a function key, since it takes the key away from every other app.
     */
    fun problem(action: ShortcutAction, combo: KeyCombo, current: Map<ShortcutAction, KeyCombo?>): ShortcutProblem? {
        val modified = combo.ctrl || combo.alt
        if (!modified && (action.global || !combo.key.isFunctionKey)) return ShortcutProblem.NeedsModifier
        if (combo in reserved) return ShortcutProblem.Reserved
        if (current.any { (other, existing) -> other != action && existing == combo }) return ShortcutProblem.InUse
        return null
    }

    /** The action that already has [combo], for the "in use" message. */
    fun owner(combo: KeyCombo, current: Map<ShortcutAction, KeyCombo?>): ShortcutAction? = current.entries.firstOrNull { it.value == combo }?.key

    /** Keys text fields and Windows need: copy and paste, undo, moving by word, closing the window. */
    private val reserved: Set<KeyCombo> = buildSet {
        listOf(ShortcutKey.A, ShortcutKey.C, ShortcutKey.V, ShortcutKey.X, ShortcutKey.Z, ShortcutKey.Y).forEach { add(KeyCombo(it, ctrl = true)) }
        add(KeyCombo(ShortcutKey.Z, ctrl = true, shift = true))
        add(KeyCombo(ShortcutKey.Insert, ctrl = true))
        listOf(ShortcutKey.Backspace, ShortcutKey.Delete, ShortcutKey.Left, ShortcutKey.Right, ShortcutKey.Home, ShortcutKey.End).forEach {
            add(KeyCombo(it, ctrl = true))
            add(KeyCombo(it, ctrl = true, shift = true))
        }
        add(KeyCombo(ShortcutKey.F4, alt = true))
        add(KeyCombo(ShortcutKey.Tab, alt = true))
        add(KeyCombo(ShortcutKey.Space, alt = true))
    }
}
