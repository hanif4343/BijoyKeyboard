package com.hanif.bijoykeyboard;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.view.MotionEvent;
import java.util.ArrayList;

public class MyKeyboardService extends InputMethodService {

    private String pendingVowel = "";
    private ArrayList<String> clipboardHistory = new ArrayList<>();
    private boolean isG_Pressed = false;
    private boolean isEnglishMode = false;
    private boolean isShiftPressed = false;
    private boolean isSymbolMode = false;
    private boolean isEmojiMode = false;
    private boolean isCtrlPressed = false;
    private Button btnCtrl;
    private View keyboardView;

    private Handler repeatUpdateHandler = new Handler();
    private boolean mAutoIncrement = false;

    private void doDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            CharSequence selectedText = ic.getSelectedText(0);
            if (selectedText != null && selectedText.length() > 0) {
                ic.commitText("", 1);
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        }
        resetStates();
    }

    class RptUpdater implements Runnable {
        public void run() {
            if (mAutoIncrement) {
                doDelete();
                repeatUpdateHandler.postDelayed(new RptUpdater(), 130);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.addPrimaryClipChangedListener(() -> updateClipboardItems());
        }
    }

    @Override
    public View onCreateInputView() {
        keyboardView = getLayoutInflater().inflate(R.layout.keyboard_layout, null);
        setupKeyboard();
        updateKeyLabels();
        return keyboardView;
    }

    private void updateClipboardItems() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                ClipData.Item item = clip.getItemAt(0);
                if (item != null && item.getText() != null) {
                    String text = item.getText().toString();
                    if (!clipboardHistory.contains(text)) {
                        clipboardHistory.add(0, text);
                        if (clipboardHistory.size() > 10) {
                            clipboardHistory.remove(clipboardHistory.size() - 1);
                        }
                    }
                }
            }
        }
        showClipboardInUI();
    }

    private void showClipboardInUI() {
        LinearLayout container = keyboardView.findViewById(R.id.clipboard_container);
        if (container == null) return;
        container.removeAllViews();
        for (String text : clipboardHistory) {
            Button btn = new Button(this);
            String displayText = text.length() > 10 ? text.substring(0, 10) + "..." : text;
            btn.setText(displayText);
            btn.setAllCaps(false);
            btn.setTextSize(10);
            btn.setTextColor(0xFFFFFFFF);
            btn.setBackgroundResource(R.drawable.key_background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            params.setMargins(5, 5, 5, 5);
            btn.setLayoutParams(params);
            btn.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(text, 1);
            });
            container.addView(btn);
        }
    }

    private void setupKeyboard() {
        int[] numberRowIds = {
                R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4, R.id.btn_5,
                R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9, R.id.btn_0
        };

        for (int id : numberRowIds) {
            Button btn = keyboardView.findViewById(id);
            if (btn != null) {
                btn.setOnClickListener(v -> {
                    String tag = v.getTag() != null ? v.getTag().toString() : "";
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        if (isEmojiMode) {
                            ic.commitText(((Button) v).getText().toString(), 1);
                            return;
                        }
                        if (!isEnglishMode && !isSymbolMode) {
                            String res = Bijoymaper.getUnicode(tag, isShiftPressed);
                            processBengaliLogic(res, ic);
                            if (isShiftPressed) { isShiftPressed = false; updateKeyLabels(); }
                            return;
                        }
                        ic.commitText(((Button) v).getText().toString(), 1);
                    }
                });
            }
        }

        int[] buttonIds = {
                R.id.btn_q, R.id.btn_w, R.id.btn_e, R.id.btn_r, R.id.btn_t, R.id.btn_y, R.id.btn_u, R.id.btn_i, R.id.btn_o, R.id.btn_p,
                R.id.btn_a, R.id.btn_s, R.id.btn_d, R.id.btn_f, R.id.btn_g, R.id.btn_h, R.id.btn_j, R.id.btn_k, R.id.btn_l,
                R.id.btn_z, R.id.btn_x, R.id.btn_c, R.id.btn_v, R.id.btn_b, R.id.btn_n, R.id.btn_m
        };

        for (int id : buttonIds) {
            Button btn = keyboardView.findViewById(id);
            if (btn != null) {
                btn.setOnClickListener(v -> {
                    Object tagObj = v.getTag();
                    if (tagObj != null) handleOnScreenKey(tagObj.toString());
                });
            }
        }

        // কমা বাটন এখন স্মার্ট বাটন হিসেবে কাজ করবে
        Button btnCommaEmoji = keyboardView.findViewById(R.id.btn_comma);
        if (btnCommaEmoji != null) {
            btnCommaEmoji.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (isSymbolMode) {
                    // সিম্বল মোডে থাকলে ইমোজি মোড অন হবে
                    isEmojiMode = !isEmojiMode;
                    updateKeyLabels();
                } else if (isEmojiMode) {
                    // ইমোজি মোডে থাকলে কমা কাজ করবে
                    if (ic != null) ic.commitText(",", 1);
                } else {
                    // সাধারণ মোডে কমা কাজ করবে
                    if (ic != null) ic.commitText(",", 1);
                }
            });
        }

        keyboardView.findViewById(R.id.btn_period).setOnClickListener(v -> {
            getCurrentInputConnection().commitText(".", 1);
            resetStates();
        });

        keyboardView.findViewById(R.id.btn_shift).setOnClickListener(v -> {
            isShiftPressed = !isShiftPressed;
            updateKeyLabels();
            v.setAlpha(isShiftPressed ? 0.5f : 1.0f);
        });

        keyboardView.findViewById(R.id.btn_lang).setOnClickListener(v -> {
            isEnglishMode = !isEnglishMode;
            isSymbolMode = false;
            isEmojiMode = false;
            updateKeyLabels();
            resetStates();
        });

        keyboardView.findViewById(R.id.btn_symbol).setOnClickListener(v -> {
            isSymbolMode = !isSymbolMode;
            isEmojiMode = false;
            updateKeyLabels();
        });

        keyboardView.findViewById(R.id.btn_space).setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            if (isG_Pressed && !isEnglishMode) {
                // হসন্ত pending — দৃশ্যমান হসন্ত রেখে space সরিয়ে দাও
                ic.commitText("\u09CD", 1); // হসন্ত বসাও
                ic.commitText(" ", 1);      // space বসাও
                ic.deleteSurroundingText(1, 0); // সেই space সরাও
                isG_Pressed = false;
            } else {
                ic.commitText(" ", 1);
                resetStates();
            }
        });

        keyboardView.findViewById(R.id.btn_enter).setOnClickListener(v -> {
            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            resetStates();
        });

        // ঃ বিসর্গ — Shift+0 দিয়ে number row handler এই handle করে

        Button btnDel = keyboardView.findViewById(R.id.btn_del);
        if (btnDel != null) {
            btnDel.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    mAutoIncrement = true;
                    repeatUpdateHandler.post(new RptUpdater());
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    mAutoIncrement = false;
                }
                return true;
            });
        }

        btnCtrl = keyboardView.findViewById(R.id.btn_ctrl);
        if (btnCtrl != null) {
            btnCtrl.setOnClickListener(v -> {
                isCtrlPressed = !isCtrlPressed;
                if (isCtrlPressed) { isSymbolMode = false; isEmojiMode = false; }
                updateKeyLabels();
            });
        }
    }

    private void updateKeyLabels() {
        int[] buttonIds = {
                R.id.btn_q, R.id.btn_w, R.id.btn_e, R.id.btn_r, R.id.btn_t, R.id.btn_y, R.id.btn_u, R.id.btn_i, R.id.btn_o, R.id.btn_p,
                R.id.btn_a, R.id.btn_s, R.id.btn_d, R.id.btn_f, R.id.btn_g, R.id.btn_h, R.id.btn_j, R.id.btn_k, R.id.btn_l,
                R.id.btn_z, R.id.btn_x, R.id.btn_c, R.id.btn_v, R.id.btn_b, R.id.btn_n, R.id.btn_m
        };

        for (int id : buttonIds) {
            Button btn = keyboardView.findViewById(id);
            if (btn != null && btn.getTag() != null) {
                String tag = btn.getTag().toString();
                if (isEmojiMode) {
                    btn.setText(getEmoji(tag));
                } else if (isSymbolMode) {
                    btn.setText(getSymbol(tag, isShiftPressed));
                } else if (isEnglishMode || isCtrlPressed) {
                    btn.setText(isShiftPressed ? tag.toUpperCase() : tag.toLowerCase());
                } else {
                    btn.setText(Bijoymaper.getUnicode(tag, isShiftPressed));
                }
            }
        }

        int[] numberRowIds = {
                R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4, R.id.btn_5,
                R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9, R.id.btn_0
        };
        for (int id : numberRowIds) {
            Button btn = keyboardView.findViewById(id);
            if (btn != null && btn.getTag() != null) {
                if (isEmojiMode) {
                    btn.setText(getEmoji(btn.getTag().toString()));
                } else if (!isEnglishMode && !isSymbolMode) {
                    btn.setText(Bijoymaper.getUnicode(btn.getTag().toString(), isShiftPressed));
                } else {
                    btn.setText(btn.getTag().toString());
                }
            }
        }

        // কমা বাটন আপডেট লজিক
        Button btnComma = keyboardView.findViewById(R.id.btn_comma);
        if (btnComma != null) {
            if (isSymbolMode) btnComma.setText("😊");
            else btnComma.setText(",");
        }

        Button langBtn = keyboardView.findViewById(R.id.btn_lang);
        if (langBtn != null) langBtn.setText(isEnglishMode ? "Eng" : "বাং");

        if (btnCtrl != null) {
            if (isSymbolMode || isCtrlPressed || isEmojiMode) {
                btnCtrl.setVisibility(View.VISIBLE);
                btnCtrl.setAlpha(isCtrlPressed ? 0.5f : 1.0f);
            } else {
                btnCtrl.setVisibility(View.GONE);
            }
        }
    }

    private String getEmoji(String tag) {
        // এখানে আমি রিদ্মিকের মতো অনেক ইমোজি সেট করে দিলাম
        switch (tag) {
            case "q": return "😊"; case "w": return "😂"; case "e": return "❤️"; case "r": return "😍";
            case "t": return "😘"; case "y": return "😭"; case "u": return "🔥"; case "i": return "👍";
            case "o": return "🙌"; case "p": return "✨";
            case "a": return "🙏"; case "s": return "😎"; case "d": return "🤣"; case "f": return "😮";
            case "g": return "😢"; case "h": return "😡"; case "j": return "🤔"; case "k": return "💯";
            case "l": return "💪";
            case "z": return "🎉"; case "x": return "🌹"; case "c": return "🤝"; case "v": return "👏";
            case "b": return "💖"; case "n": return "💙"; case "m": return "✅";
            case "1": return "😇"; case "2": return "🤩"; case "3": return "😅"; case "4": return "🙄";
            case "5": return "🤤"; case "6": return "🤐"; case "7": return "🤫"; case "8": return "🤑";
            case "9": return "😷"; case "0": return "😴";
            default: return "😀";
        }
    }

    private String getSymbol(String tag, boolean shift) {
        if (shift) {
            switch (tag) {
                case "q": return "["; case "w": return "]"; case "e": return "{"; case "r": return "}";
                case "t": return "©"; case "y": return "®"; case "u": return "™"; case "i": return "§";
                case "o": return "°"; case "p": return "•";
                case "a": return "√"; case "s": return "π"; case "d": return "Δ"; case "f": return "£";
                case "g": return "¥"; case "h": return "€"; case "j": return "¢"; case "k": return "←";
                case "l": return "→";
                case "z": return "↑"; case "x": return "↓"; case "c": return "≠"; case "v": return "≈";
                case "b": return "∞"; case "n": return "±"; case "m": return "μ";
                default: return "";
            }
        } else {
            switch (tag) {
                case "q": return "!"; case "w": return "@"; case "e": return "#"; case "r": return "$";
                case "t": return "%"; case "y": return "^"; case "u": return "&"; case "i": return "*";
                case "o": return "("; case "p": return ")";
                case "a": return "~"; case "s": return "\""; case "d": return "|"; case "f": return "_";
                case "g": return "-"; case "h": return ":"; case "j": return ";"; case "k": return "<";
                case "l": return ">";
                case "z": return "\\"; case "x": return "÷"; case "c": return "+"; case "v": return "=";
                case "b": return "/"; case "n": return "?"; case "m": return "×";
                default: return "";
            }
        }
    }

    private void handleOnScreenKey(String tag) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (isEmojiMode) { ic.commitText(getEmoji(tag), 1); return; }
        if (isCtrlPressed) {
            int keyCode = -1;
            switch (tag.toLowerCase()) {
                case "a": keyCode = KeyEvent.KEYCODE_A; break;
                case "c": keyCode = KeyEvent.KEYCODE_C; break;
                case "v": keyCode = KeyEvent.KEYCODE_V; break;
                case "x": keyCode = KeyEvent.KEYCODE_X; break;
                case "z": keyCode = KeyEvent.KEYCODE_Z; break;
            }
            if (keyCode != -1) {
                ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON));
                ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_CTRL_ON));
                isCtrlPressed = false; updateKeyLabels(); return;
            }
        }
        if (isSymbolMode) {
            ic.commitText(getSymbol(tag, isShiftPressed), 1);
            if (isShiftPressed) { isShiftPressed = false; updateKeyLabels(); }
            return;
        }
        if (isEnglishMode) {
            ic.commitText(isShiftPressed ? tag.toUpperCase() : tag.toLowerCase(), 1);
        } else {
            String result = Bijoymaper.getUnicode(tag, isShiftPressed);
            processBengaliLogic(result, ic);
        }
        if (isShiftPressed) { isShiftPressed = false; updateKeyLabels(); }
    }

    private void processBengaliLogic(String result, InputConnection ic) {
        if (result == null || result.isEmpty()) return;
        String prevChar;

        // ─── আ-কার (া)
        if (result.equals("\u09BE")) {
            if (isG_Pressed) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText("\u0986", 1); isG_Pressed = false; return;
            }
            if (pendingVowel.equals("\u0985")) { pendingVowel = ""; ic.commitText("\u0986", 1); isG_Pressed = false; return; }
            prevChar = getPreviousChar(ic);
            if (prevChar.equals("\u0985")) { ic.deleteSurroundingText(1, 0); ic.commitText("\u0986", 1); isG_Pressed = false; return; }
            if (pendingVowel.equals("\u09C7")) { pendingVowel = ""; ic.commitText("\u09CB", 1); isG_Pressed = false; return; }
            if (prevChar.equals("\u09C7")) { ic.deleteSurroundingText(1, 0); ic.commitText("\u09CB", 1); isG_Pressed = false; return; }
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(result, 1); isG_Pressed = false; return;
        }

        // ─── ৌ-কার
        if (result.equals("\u09CC")) {
            if (isG_Pressed) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText("\u0994", 1); isG_Pressed = false; return;
            }
            if (pendingVowel.equals("\u0993")) { pendingVowel = ""; ic.commitText("\u0994", 1); isG_Pressed = false; return; }
            prevChar = getPreviousChar(ic);
            if (prevChar.equals("\u0993")) { ic.deleteSurroundingText(1, 0); ic.commitText("\u0994", 1); isG_Pressed = false; return; }
            if (pendingVowel.equals("\u09C7")) { pendingVowel = ""; ic.commitText("\u09CC", 1); isG_Pressed = false; return; }
            if (prevChar.equals("\u09C7")) { ic.deleteSurroundingText(1, 0); ic.commitText("\u09CC", 1); isG_Pressed = false; return; }
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText("\u09CC", 1); isG_Pressed = false; return;
        }

        // ─── হসন্ত pending + যেকোনো কার → স্বরবর্ণ
        if (isG_Pressed && isBengaliKar(result)) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(convertKarToVowel(result), 1); isG_Pressed = false; return;
        }

        // ─── র‍্য (ZWJ)
        if (result.equals("\u09CD\u09AF")) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            prevChar = getPreviousChar(ic);
            ic.commitText(prevChar.equals("\u09B0") ? "\u200D" + result : result, 1);
            isG_Pressed = false; return;
        }

        // ─── রেফ
        if (result.equals("\u09B0\u09CD")) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            prevChar = getPreviousChar(ic);
            if (!prevChar.isEmpty()) {
                ic.deleteSurroundingText(1, 0);
                if (isBengaliKar(prevChar)) {
                    String mainChar = getPreviousChar(ic);
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(result + mainChar + prevChar, 1);
                } else { ic.commitText(result + prevChar, 1); }
            } else { ic.commitText(result, 1); }
            isG_Pressed = false; return;
        }

        // ─── হসন্ত
        if (result.equals("\u09CD")) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            isG_Pressed = true; return;
        }

        boolean isKar = isBengaliKar(result);
        boolean isAutoJoint = result.startsWith("\u09CD");

        // ─── যুক্তবর্ণ
        if (isG_Pressed || isAutoJoint) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            String lastChar = getPreviousChar(ic);
            if (!lastChar.isEmpty()) {
                ic.deleteSurroundingText(1, 0);
                if (isBengaliKar(lastChar)) {
                    String mainChar2 = getPreviousChar(ic);
                    if (!mainChar2.isEmpty()) {
                        ic.deleteSurroundingText(1, 0);
                        String jnt = isAutoJoint ? result : "\u09CD" + result;
                        ic.commitText(mainChar2 + jnt + lastChar, 1);
                    } else { ic.commitText(lastChar + result, 1); }
                } else {
                    String jnt2 = isAutoJoint ? result : "\u09CD" + result;
                    ic.commitText(lastChar + jnt2, 1);
                }
            } else { ic.commitText(result, 1); }
            isG_Pressed = false; return;
        }

        // ─── ি এবং এ-কার → pending (আগে বসে)
        if (result.equals("\u09BF") || result.equals("\u09C7")) {
            if (pendingVowel.equals("\u0985")) { pendingVowel = ""; ic.commitText(convertKarToVowel(result), 1); isG_Pressed = false; return; }
            prevChar = getPreviousChar(ic);
            if (prevChar.equals("\u0985")) { ic.deleteSurroundingText(1, 0); ic.commitText(convertKarToVowel(result), 1); isG_Pressed = false; return; }
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); }
            pendingVowel = result; return;
        }

        // ─── বাকি সব
        if (!isKar) {
            if (result.equals("\u0985")) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                pendingVowel = "\u0985"; isG_Pressed = false; return;
            }
            ic.commitText(result, 1);
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
        } else {
            if (pendingVowel.equals("\u0985")) { pendingVowel = ""; ic.commitText(convertKarToVowel(result), 1); isG_Pressed = false; return; }
            prevChar = getPreviousChar(ic);
            if (prevChar.equals("\u0985")) { ic.deleteSurroundingText(1, 0); ic.commitText(convertKarToVowel(result), 1); isG_Pressed = false; return; }
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(result, 1);
        }
        isG_Pressed = false;
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return super.onKeyDown(keyCode, event);
        if (event.isCtrlPressed()) return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DEL) { resetStates(); return super.onKeyDown(keyCode, event); }
        if (keyCode == KeyEvent.KEYCODE_SPACE && isG_Pressed && !isEnglishMode) {
            ic.commitText("\u09CD", 1);
            ic.commitText(" ", 1);
            ic.deleteSurroundingText(1, 0);
            isG_Pressed = false;
            return true;
        }
        if (event.isAltPressed() && keyCode == KeyEvent.KEYCODE_SPACE) {
            isEnglishMode = !isEnglishMode; isEmojiMode = false;
            resetStates(); updateKeyLabels(); return true;
        }
        if (isEnglishMode) return super.onKeyDown(keyCode, event);
        if (event.isPrintingKey() || (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)) {
            String tag;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) tag = String.valueOf(keyCode - KeyEvent.KEYCODE_0);
            else { char c = (char) event.getUnicodeChar(); tag = String.valueOf(c).toLowerCase(); }
            if (event.isShiftPressed() && tag.equals("7")) { processBengaliLogic(Bijoymaper.getUnicode("7", true), ic); return true; }
            if (event.isShiftPressed() && tag.equals("9")) { processBengaliLogic(Bijoymaper.getUnicode("9", true), ic); return true; }
            if (event.isShiftPressed() && tag.equals("0")) { processBengaliLogic(Bijoymaper.getUnicode("0", true), ic); return true; }
            String res = Bijoymaper.getUnicode(tag, event.isShiftPressed());
            if (res != null && !res.isEmpty() && !res.equals(tag)) { processBengaliLogic(res, ic); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void resetStates() {
        if (!pendingVowel.isEmpty()) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(pendingVowel, 1);
            pendingVowel = "";
        }
        isG_Pressed = false;
    }
    private boolean isBengaliKar(String s) { return "\u09BE\u09BF\u09C0\u09C1\u09C2\u09C3\u09C7\u09C8\u09CB\u09CC\u09D7".contains(s); }
    private String convertKarToVowel(String kar) {
        switch (kar) {
            case "\u09BE": return "\u0986"; case "\u09BF": return "\u0987";
            case "\u09C0": return "\u0988"; case "\u09C1": return "\u0989";
            case "\u09C2": return "\u098A"; case "\u09C3": return "\u098B";
            case "\u09C7": return "\u098F"; case "\u09C8": return "\u0990";
            case "\u09CB": return "\u0993"; case "\u09CC": return "\u0994";
            default: return kar;
        }
    }
    private String getPreviousChar(InputConnection ic) { CharSequence b = ic.getTextBeforeCursor(1, 0); return (b != null) ? b.toString() : ""; }
}