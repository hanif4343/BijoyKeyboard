package com.hanif.bijoykeyboard;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.List;

public class MyKeyboardService extends InputMethodService {

    // ══════════════════════════════════════
    // STATE VARIABLES
    // ══════════════════════════════════════
    private String pendingVowel = "";
    private ArrayList<String> clipboardHistory = new ArrayList<>();
    private boolean isG_Pressed    = false;
    private boolean isEnglishMode  = false;
    private boolean isShiftPressed = false;
    private boolean isSymbolMode   = false;
    private boolean isEmojiMode    = false;
    private boolean isCtrlPressed  = false;
    private Button  btnCtrl;
    private View    keyboardView;
    private SpeechRecognizer speechRecognizer = null;
    private boolean isListening = false;
    private Vibrator vibrator;

    private Handler repeatUpdateHandler = new Handler();
    private boolean mAutoIncrement = false;

    // ══════════════════════════════════════
    // DELETE (with long-press repeat)
    // ══════════════════════════════════════
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

    // ══════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════
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
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        loadClipboardHistory();
        setupKeyboard();
        updateKeyLabels();
        return keyboardView;
    }

    @Override
    public void onDestroy() {
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
        super.onDestroy();
    }

    // ══════════════════════════════════════
    // CLIPBOARD — persist, pin, show
    // ══════════════════════════════════════
    private static final String CLIP_PREFS = "clipboard_history";
    private static final String CLIP_KEY   = "history";
    private static final int    CLIP_MAX   = 20;

    private void saveClipboardHistory() {
        StringBuilder sb = new StringBuilder();
        for (String s : clipboardHistory) sb.append(s).append("||");
        getSharedPreferences(CLIP_PREFS, MODE_PRIVATE).edit()
            .putString(CLIP_KEY, sb.toString()).apply();
    }

    private void loadClipboardHistory() {
        String raw = getSharedPreferences(CLIP_PREFS, MODE_PRIVATE).getString(CLIP_KEY, "");
        clipboardHistory.clear();
        if (!raw.isEmpty()) {
            for (String s : raw.split("\\|\\|")) {
                if (!s.isEmpty()) clipboardHistory.add(s);
            }
        }
    }

    private void updateClipboardItems() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                ClipData.Item item = clip.getItemAt(0);
                if (item != null && item.getText() != null) {
                    String text = item.getText().toString().trim();
                    if (!text.isEmpty()) {
                        clipboardHistory.remove(text); // আগে থাকলে সরিয়ে দাও
                        clipboardHistory.add(0, text); // সবসময় সামনে আনো
                        if (clipboardHistory.size() > CLIP_MAX)
                            clipboardHistory.remove(clipboardHistory.size() - 1);
                        saveClipboardHistory();
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

        ArrayList<String> pinnedItems = getPinnedItems();
        ArrayList<String> allItems    = new ArrayList<>();
        for (String p : pinnedItems) allItems.add("📌 " + p);
        for (String h : clipboardHistory) {
            if (!pinnedItems.contains(h)) allItems.add(h);
        }

        for (String rawText : allItems) {
            boolean isPinned = rawText.startsWith("📌 ");
            String  text     = isPinned ? rawText.substring(3) : rawText;

            Button btn = new Button(this);
            String displayText = (isPinned ? "📌 " : "") +
                (text.length() > 12 ? text.substring(0, 12) + "…" : text);
            btn.setText(displayText);
            btn.setAllCaps(false);
            btn.setTextSize(10);
            btn.setTextColor(isPinned ? 0xFF38BDF8 : 0xFFFFFFFF);
            btn.setBackgroundResource(R.drawable.key_background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            params.setMargins(5, 5, 5, 5);
            btn.setLayoutParams(params);

            // Long-press → Pin / Unpin
            btn.setOnLongClickListener(v -> {
                if (isPinned) {
                    unpinItem(text);
                    Toast.makeText(this, "Pin সরানো হয়েছে", Toast.LENGTH_SHORT).show();
                } else {
                    pinItem(text);
                    Toast.makeText(this, "📌 Pin হয়েছে!", Toast.LENGTH_SHORT).show();
                }
                showClipboardInUI();
                return true;
            });

            // Single tap → paste; Double tap → delete
            btn.setOnClickListener(new View.OnClickListener() {
                private long lastClick = 0;
                @Override public void onClick(View v) {
                    long now = System.currentTimeMillis();
                    if (now - lastClick < 400) {
                        clipboardHistory.remove(text);
                        unpinItem(text);
                        saveClipboardHistory();
                        showClipboardInUI();
                        Toast.makeText(MyKeyboardService.this, "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show();
                    } else {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) ic.commitText(text, 1);
                    }
                    lastClick = now;
                }
            });

            container.addView(btn);
        }
    }

    private ArrayList<String> getPinnedItems() {
        String raw = getSharedPreferences("clipboard_pins", MODE_PRIVATE).getString("pins", "");
        ArrayList<String> list = new ArrayList<>();
        if (!raw.isEmpty())
            for (String s : raw.split("\\|\\|")) if (!s.isEmpty()) list.add(s);
        return list;
    }

    private void pinItem(String text) {
        ArrayList<String> pins = getPinnedItems();
        if (!pins.contains(text)) { pins.add(0, text); savePins(pins); }
    }

    private void unpinItem(String text) {
        ArrayList<String> pins = getPinnedItems();
        pins.remove(text);
        savePins(pins);
    }

    private void savePins(ArrayList<String> pins) {
        StringBuilder sb = new StringBuilder();
        for (String s : pins) sb.append(s).append("||");
        getSharedPreferences("clipboard_pins", MODE_PRIVATE).edit()
            .putString("pins", sb.toString()).apply();
    }

    // ══════════════════════════════════════
    // KEYBOARD SETUP
    // ══════════════════════════════════════
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
                        if (isEmojiMode) { ic.commitText(((Button) v).getText().toString(), 1); return; }
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
            R.id.btn_q, R.id.btn_w, R.id.btn_e, R.id.btn_r, R.id.btn_t,
            R.id.btn_y, R.id.btn_u, R.id.btn_i, R.id.btn_o, R.id.btn_p,
            R.id.btn_a, R.id.btn_s, R.id.btn_d, R.id.btn_f, R.id.btn_g,
            R.id.btn_h, R.id.btn_j, R.id.btn_k, R.id.btn_l,
            R.id.btn_z, R.id.btn_x, R.id.btn_c, R.id.btn_v,
            R.id.btn_b, R.id.btn_n, R.id.btn_m
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

        // Comma / Emoji-panel toggle
        Button btnCommaEmoji = keyboardView.findViewById(R.id.btn_comma);
        if (btnCommaEmoji != null) {
            btnCommaEmoji.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (isSymbolMode) {
                    if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                    isEmojiMode = true;
                    isSymbolMode = false;
                    showEmojiPanel();
                } else {
                    if (ic != null) {
                        if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                        ic.commitText(",", 1);
                    }
                }
            });
        }

        keyboardView.findViewById(R.id.btn_period).setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText(".", 1);
            }
            isG_Pressed = false;
        });

        keyboardView.findViewById(R.id.btn_shift).setOnClickListener(v -> {
            isShiftPressed = !isShiftPressed;
            updateKeyLabels();
            v.setAlpha(isShiftPressed ? 0.5f : 1.0f);
        });

        keyboardView.findViewById(R.id.btn_lang).setOnClickListener(v -> {
            isEnglishMode = !isEnglishMode;
            isSymbolMode  = false;
            isEmojiMode   = false;
            updateKeyLabels();
            resetStates();
        });

        keyboardView.findViewById(R.id.btn_symbol).setOnClickListener(v -> {
            isSymbolMode = !isSymbolMode;
            isEmojiMode  = false;
            if (isSymbolMode) setInputView(keyboardView);
            updateKeyLabels();
        });

        keyboardView.findViewById(R.id.btn_space).setOnClickListener(v -> {
            doHaptic();
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            if (isG_Pressed && !isEnglishMode) {
                // হসন্ত visible রেখে space
                ic.commitText("\u09CD", 1);
                ic.commitText(" ", 1);
                ic.deleteSurroundingText(1, 0);
                isG_Pressed = false;
            } else {
                ic.commitText(" ", 1);
                isG_Pressed = false;
            }
        });

        keyboardView.findViewById(R.id.btn_enter).setOnClickListener(v -> {
            doHaptic();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            }
            isG_Pressed = false;
        });

        // Mic button
        ImageView btnMicTop = keyboardView.findViewById(R.id.btn_mic_top);
        if (btnMicTop != null) btnMicTop.setOnClickListener(v -> startVoiceInput());

        // Delete — long-press repeat
        Button btnDel = keyboardView.findViewById(R.id.btn_del);
        if (btnDel != null) {
            btnDel.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    mAutoIncrement = true;
                    repeatUpdateHandler.post(new RptUpdater());
                } else if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    mAutoIncrement = false;
                }
                return true;
            });
        }

        // Ctrl button
        btnCtrl = keyboardView.findViewById(R.id.btn_ctrl);
        if (btnCtrl != null) {
            btnCtrl.setOnClickListener(v -> {
                isCtrlPressed = !isCtrlPressed;
                if (isCtrlPressed) { isSymbolMode = false; isEmojiMode = false; }
                updateKeyLabels();
            });
        }
    }

    // ══════════════════════════════════════
    // KEY LABELS
    // ══════════════════════════════════════
    private void updateKeyLabels() {
        int[] buttonIds = {
            R.id.btn_q, R.id.btn_w, R.id.btn_e, R.id.btn_r, R.id.btn_t,
            R.id.btn_y, R.id.btn_u, R.id.btn_i, R.id.btn_o, R.id.btn_p,
            R.id.btn_a, R.id.btn_s, R.id.btn_d, R.id.btn_f, R.id.btn_g,
            R.id.btn_h, R.id.btn_j, R.id.btn_k, R.id.btn_l,
            R.id.btn_z, R.id.btn_x, R.id.btn_c, R.id.btn_v,
            R.id.btn_b, R.id.btn_n, R.id.btn_m
        };
        for (int id : buttonIds) {
            Button btn = keyboardView.findViewById(id);
            if (btn != null && btn.getTag() != null) {
                String tag = btn.getTag().toString();
                if (isSymbolMode)              btn.setText(getSymbol(tag, isShiftPressed));
                else if (isEnglishMode || isCtrlPressed)
                                               btn.setText(isShiftPressed ? tag.toUpperCase() : tag.toLowerCase());
                else                           btn.setText(Bijoymaper.getUnicode(tag, isShiftPressed));
            }
        }

        int[] numberRowIds = {
            R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4, R.id.btn_5,
            R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9, R.id.btn_0
        };
        for (int id : numberRowIds) {
            Button btn = keyboardView.findViewById(id);
            if (btn != null && btn.getTag() != null) {
                if (!isEnglishMode && !isSymbolMode)
                    btn.setText(Bijoymaper.getUnicode(btn.getTag().toString(), isShiftPressed));
                else
                    btn.setText(btn.getTag().toString());
            }
        }

        Button btnComma = keyboardView.findViewById(R.id.btn_comma);
        if (btnComma != null) btnComma.setText(isSymbolMode ? "😊" : ",");

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

    // ══════════════════════════════════════
    // EMOJI PANEL
    // ══════════════════════════════════════
    private static final String[][] EMOJI_CATEGORIES = {
        {"😊",
         "😀","😁","😂","🤣","😃","😄","😅","😆","😇","😈","😉","😊","😋","😌","😍","😎",
         "😏","😐","😑","😒","😓","😔","😕","😖","😗","😘","😙","😚","😛","😜","😝","😞",
         "😟","😠","😡","😢","😣","😤","😥","😦","😧","😨","😩","😪","😫","😬","😭","😮",
         "😯","😰","😱","😲","😳","😴","😵","😶","😷","🙁","🙂","🙃","🙄","🤐","🤑","🤒",
         "🤓","🤔","🤕","🤗","🤠","🤡","🤢","🤣","🤤","🤥","🤧","🤨","🤩","🤪","🤫","🤬",
         "🤭","🤯","🥰","🥱","🥲","🥳","🥴","🥵","🥶","🥺","🫠","🫡","🫢","🫣","🫤","🫥"},
        {"❤️",
         "❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖",
         "💘","💝","💟","☮️","✝️","☯️","🕉️","✡️","🔯","🛐","⛎","♈","♉","♊","♋","♌",
         "♍","♎","♏","♐","♑","♒","♓","🆔","⚛️","🉑","☢️","☣️","📴","📳","🈶","🈚"},
        {"👋",
         "👋","🤚","🖐","✋","🖖","👌","🤌","🤏","✌️","🤞","🤟","🤘","🤙","👈","👉","👆",
         "🖕","👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌","👐","🤲","🙏","✍️","💅",
         "🤳","💪","🦾","🦿","🦵","🦶","👂","🦻","👃","👣","👁","👀","🫀","🫁","🧠","🦷"},
        {"🐶",
         "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🙈",
         "🙉","🙊","🐒","🐔","🐧","🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄","🐝",
         "🐛","🦋","🐌","🐞","🐜","🦟","🦗","🦂","🐢","🐍","🦎","🦖","🦕","🐙","🦑","🦐"},
        {"🍎",
         "🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈","🍒","🍑","🥭","🍍","🥥","🥝",
         "🍅","🍆","🥑","🥦","🥬","🥒","🌶","🫑","🌽","🥕","🧄","🧅","🥔","🍠","🥐","🥯",
         "🍞","🥖","🥨","🧀","🥚","🍳","🧈","🥞","🧇","🥓","🥩","🍗","🍖","🌭","🍔","🍟"},
        {"⚽",
         "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🪀","🏓","🏸","🏒","🥊","🥋",
         "🎽","🛹","🛼","🛷","⛸","🥌","🎿","⛷","🏂","🪂","🏋️","🤼","🤸","🤺","⛹","🤾",
         "🏌️","🏇","🧘","🏄","🏊","🤽","🚣","🧗","🚴","🏆","🥇","🥈","🥉","🏅","🎖","🎗"},
        {"🚗",
         "🚗","🚕","🚙","🚌","🚎","🏎","🚓","🚑","🚒","🚐","🛻","🚚","🚛","🚜","🏍","🛵",
         "🛺","🚲","🛴","🛹","🛼","🚏","🛣","🛤","⛽","🚧","⚓","🛟","⛵","🚤","🛥","🛳",
         "🚀","🛸","🚁","🛶","✈️","🛩","🪂","💺","🚂","🚃","🚄","🚅","🚆","🚇","🚈","🚉"},
        {"💻",
         "💻","🖥","🖨","⌨️","🖱","🖲","💽","💾","💿","📀","📱","📲","☎️","📞","📟","📠",
         "📺","📻","🧭","⏱","⏲","⏰","🕰","⌚","⏳","⌛","📡","🔋","🪫","🔌","💡","🔦",
         "🕯","🪔","🧲","💰","💴","💵","💶","💷","💸","💳","🪙","💹","✉️","📧","📨","📩"},
        {"🌸",
         "🌸","💐","🌹","🥀","🌺","🌻","🌼","🌷","🌱","🪴","🌲","🌳","🌴","🌵","🎋","🎍",
         "🍀","🍁","🍂","🍃","🍄","🌾","💧","🌊","🌬","🌀","🌈","🌂","☂","☔","⛱","⚡",
         "❄️","🔥","💥","🌙","⭐","🌟","💫","✨","☀️","🌤","⛅","🌥","☁️","🌦","🌧","⛈"},
        {"🎉",
         "🎉","🎊","🎈","🎁","🎀","🎗","🎟","🎫","🏷","🔖","🏮","🎆","🎇","🧨","✨","🎍",
         "🎋","🎄","🎃","🎑","🎐","🎏","🎠","🎡","🎢","💈","🎪","🎭","🖼","🎨","🎬","🎤",
         "🎧","🎼","🎵","🎶","🎷","🎸","🎹","🎺","🎻","🪕","🥁","🪘","🎮","🕹","🎲","♟"},
    };

    private static final String[] CATEGORY_NAMES = {
        "😊 হাসি","❤️ মন","👋 হাত","🐶 প্রাণী","🍎 খাবার",
        "⚽ খেলা","🚗 যান","💻 টেক","🌸 প্রকৃতি","🎉 উৎসব"
    };

    private int  currentEmojiCategory = 0;
    private View emojiPanelView        = null;

    private void showEmojiPanel() {
        if (emojiPanelView == null)
            emojiPanelView = getLayoutInflater().inflate(R.layout.emoji_panel, null);
        setInputView(emojiPanelView);

        LinearLayout tabs = emojiPanelView.findViewById(R.id.emoji_category_tabs);
        tabs.removeAllViews();
        for (int i = 0; i < CATEGORY_NAMES.length; i++) {
            final int idx = i;
            TextView tab = new TextView(this);
            tab.setText(EMOJI_CATEGORIES[i][0]);
            tab.setTextSize(22);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setPadding(12, 4, 12, 4);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            tab.setLayoutParams(p);
            tab.setBackgroundColor(i == currentEmojiCategory
                ? android.graphics.Color.parseColor("#1D4ED8")
                : android.graphics.Color.TRANSPARENT);
            tab.setOnClickListener(v -> { currentEmojiCategory = idx; showEmojiPanel(); });
            tabs.addView(tab);
        }

        loadEmojiGrid(emojiPanelView);

        TextView btnKeyboard = emojiPanelView.findViewById(R.id.btn_emoji_keyboard);
        btnKeyboard.setOnClickListener(v -> { isEmojiMode = false; setInputView(keyboardView); });

        TextView btnDel = emojiPanelView.findViewById(R.id.btn_emoji_del);
        btnDel.setOnClickListener(v -> {
            doHaptic();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        });
    }

    private void loadEmojiGrid(View panel) {
        GridLayout grid = panel.findViewById(R.id.emoji_grid);
        grid.removeAllViews();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cellSize    = screenWidth / 9;
        String[] emojis = EMOJI_CATEGORIES[currentEmojiCategory];
        for (int i = 1; i < emojis.length; i++) {
            final String emoji = emojis[i];
            TextView btn = new TextView(this);
            btn.setText(emoji);
            btn.setTextSize(24);
            btn.setGravity(android.view.Gravity.CENTER);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width  = cellSize;
            p.height = cellSize;
            p.setMargins(1, 1, 1, 1);
            btn.setLayoutParams(p);
            btn.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(emoji, 1);
                doHaptic();
            });
            grid.addView(btn);
        }
        ScrollView scroll = panel.findViewById(R.id.emoji_scroll);
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    // ══════════════════════════════════════
    // SYMBOL MAP
    // ══════════════════════════════════════
    private String getSymbol(String tag, boolean shift) {
        if (shift) {
            switch (tag) {
                case "q": return "[";  case "w": return "]";  case "e": return "{";  case "r": return "}";
                case "t": return "©";  case "y": return "®";  case "u": return "™";  case "i": return "§";
                case "o": return "°";  case "p": return "•";
                case "a": return "√";  case "s": return "π";  case "d": return "Δ";  case "f": return "'";
                case "g": return "¥";  case "h": return "€";  case "j": return "¢";  case "k": return "←";
                case "l": return "→";
                case "z": return "↑";  case "x": return "↓";  case "c": return "≠";  case "v": return "≈";
                case "b": return "∞";  case "n": return "±";  case "m": return "μ";
                default:  return "";
            }
        } else {
            switch (tag) {
                case "q": return "!";  case "w": return "@";  case "e": return "#";  case "r": return "$";
                case "t": return "%";  case "y": return "^";  case "u": return "&";  case "i": return "*";
                case "o": return "(";  case "p": return ")";
                case "a": return "~";  case "s": return "\""; case "d": return "|";  case "f": return "_";
                case "g": return "-";  case "h": return ":";  case "j": return ";";  case "k": return "<";
                case "l": return ">";
                case "z": return "\\"; case "x": return "÷";  case "c": return "+";  case "v": return "=";
                case "b": return "/";  case "n": return "?";  case "m": return "×";
                default:  return "";
            }
        }
    }

    // ══════════════════════════════════════
    // HAPTIC
    // ══════════════════════════════════════
    private void doHaptic() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
        else
            vibrator.vibrate(18);
    }

    // ══════════════════════════════════════
    // ON-SCREEN KEY HANDLER
    // ══════════════════════════════════════
    private void handleOnScreenKey(String tag) {
        doHaptic();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // Ctrl shortcuts
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
                ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, KeyEvent.META_CTRL_ON));
                isCtrlPressed = false; updateKeyLabels(); return;
            }
        }

        if (isSymbolMode) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
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

    // ══════════════════════════════════════════════════════════════════
    // BENGALI LOGIC  — পুরনো ভার্সনের সম্পূর্ণ লজিক, নতুনে মার্জ করা
    //
    //  Priority order (উপর থেকে নিচে):
    //  1. আ-কার (া)           — এ-কার + আ-কার → ও-কার; হসন্ত pending → আ
    //  2. ৌ-কার                — এ-কার + ৌ → ৌ; ও → ঔ
    //  3. হসন্ত pending + কার  → স্বরবর্ণ
    //  4. র‍্য                  — ZWJ দিয়ে র + ্য
    //  5. রেফ (র্)             — আগের অক্ষরের উপর রেফ
    //  6. হসন্ত (্)            → isG_Pressed = true (pending)
    //  7. যুক্তবর্ণ           — isG_Pressed বা auto-joint (্ দিয়ে শুরু)
    //  8. ি / এ-কার / ৈ-কার  → pendingVowel (ব্যঞ্জনের পরে flush)
    //  9. স্বরবর্ণ / ব্যঞ্জন   — সাধারণ commit
    // ══════════════════════════════════════════════════════════════════
    private void processBengaliLogic(String result, InputConnection ic) {
        if (result == null || result.isEmpty()) return;
        String prevChar;

        // ── 1. আ-কার (া  U+09BE) ──────────────────────────────────────
        if (result.equals("\u09BE")) {
            if (isG_Pressed) {
                // হসন্ত pending অবস্থায় আ-কার → স্বাধীন আ
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText("\u0986", 1);   // আ
                isG_Pressed = false; return;
            }
            // অ (U+0985) এর পর আ-কার → আ
            prevChar = getPreviousChar(ic);
            if (prevChar.equals("\u0985")) {
                ic.deleteSurroundingText(1, 0);
                ic.commitText("\u0986", 1);
                isG_Pressed = false; return;
            }
            // pendingVowel এ এ-কার থাকলে → ও-কার (ো)
            if (pendingVowel.equals("\u09C7")) {
                pendingVowel = "";
                ic.commitText("\u09CB", 1);   // ো
                isG_Pressed = false; return;
            }
            // আগের char-এ এ-কার থাকলে → ও-কার
            if (prevChar.equals("\u09C7")) {
                ic.deleteSurroundingText(1, 0);
                ic.commitText("\u09CB", 1);
                isG_Pressed = false; return;
            }
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(result, 1);
            isG_Pressed = false; return;
        }

        // ── 2. ৌ-কার (U+09CC) ─────────────────────────────────────────
        if (result.equals("\u09CC")) {
            if (isG_Pressed) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText("\u0994", 1);   // ঔ
                isG_Pressed = false; return;
            }
            // pending ও (U+0993) → ঔ
            if (pendingVowel.equals("\u0993")) {
                pendingVowel = "";
                ic.commitText("\u0994", 1);
                isG_Pressed = false; return;
            }
            prevChar = getPreviousChar(ic);
            if (prevChar.equals("\u0993")) {
                ic.deleteSurroundingText(1, 0);
                ic.commitText("\u0994", 1);
                isG_Pressed = false; return;
            }
            // pendingVowel এ এ-কার (U+09C7) থাকলে + ৌ → ৌ-কার
            if (pendingVowel.equals("\u09C7")) {
                pendingVowel = "";
                ic.commitText("\u09CC", 1);
                isG_Pressed = false; return;
            }
            // আগের char এ-কার → ৌ-কার
            if (prevChar.equals("\u09C7")) {
                ic.deleteSurroundingText(1, 0);
                ic.commitText("\u09CC", 1);
                isG_Pressed = false; return;
            }
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText("\u09CC", 1);
            isG_Pressed = false; return;
        }

        // ── 3. হসন্ত pending + যেকোনো কার → স্বরবর্ণ ─────────────────
        if (isG_Pressed && isBengaliKar(result)) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(convertKarToVowel(result), 1);
            isG_Pressed = false; return;
        }

        // ── 4. র‍্য  (ZWJ + ্য) ────────────────────────────────────────
        if (result.equals("\u09CD\u09AF")) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            prevChar = getPreviousChar(ic);
            ic.commitText(prevChar.equals("\u09B0") ? "\u200D" + result : result, 1);
            isG_Pressed = false; return;
        }

        // ── 5. রেফ (র্  U+09B0 + U+09CD) ──────────────────────────────
        if (result.equals("\u09B0\u09CD")) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            prevChar = getPreviousChar(ic);
            if (!prevChar.isEmpty()) {
                ic.deleteSurroundingText(1, 0);
                if (isBengaliKar(prevChar)) {
                    String mainChar = getPreviousChar(ic);
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(result + mainChar + prevChar, 1);
                } else {
                    ic.commitText(result + prevChar, 1);
                }
            } else {
                ic.commitText(result, 1);
            }
            isG_Pressed = false; return;
        }

        // ── 6. হসন্ত (্  U+09CD) → pending ────────────────────────────
        if (result.equals("\u09CD")) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            isG_Pressed = true; return;
        }

        boolean isKar      = isBengaliKar(result);
        boolean isAutoJoint = result.startsWith("\u09CD");   // ্ দিয়ে শুরু হলে auto-joint

        // ── 7. যুক্তবর্ণ ──────────────────────────────────────────────
        if (isG_Pressed || isAutoJoint) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            String lastChar = getPreviousChar(ic);
            if (!lastChar.isEmpty()) {
                ic.deleteSurroundingText(1, 0);
                if (isBengaliKar(lastChar)) {
                    // কার-এর আগের ব্যঞ্জনের সাথে জুড়ো, তারপর কার পুনরায় বসাও
                    String mainChar = getPreviousChar(ic);
                    if (!mainChar.isEmpty()) {
                        ic.deleteSurroundingText(1, 0);
                        String jnt = isAutoJoint ? result : "\u09CD" + result;
                        ic.commitText(mainChar + jnt + lastChar, 1);
                    } else {
                        ic.commitText(lastChar + result, 1);
                    }
                } else {
                    String jnt = isAutoJoint ? result : "\u09CD" + result;
                    ic.commitText(lastChar + jnt, 1);
                }
            } else {
                ic.commitText(result, 1);
            }
            isG_Pressed = false; return;
        }

        // ── 8. ি (U+09BF) / এ-কার (U+09C7) / ৈ-কার (U+09C8) → pending
        if (result.equals("\u09BF") || result.equals("\u09C7") || result.equals("\u09C8")) {
            prevChar = getPreviousChar(ic);
            // অ এর পর → স্বরবর্ণে রূপান্তর
            if (prevChar.equals("\u0985")) {
                ic.deleteSurroundingText(1, 0);
                ic.commitText(convertKarToVowel(result), 1);
                isG_Pressed = false; return;
            }
            // আগের pending flush করে নতুন pending রাখো
            if (!pendingVowel.isEmpty()) ic.commitText(pendingVowel, 1);
            pendingVowel = result;
            return;
        }

        // ── 9. বাকি সব (স্বরবর্ণ, ব্যঞ্জন, অন্য কার) ─────────────────
        if (!isKar) {
            // নতুন ব্যঞ্জন/স্বরবর্ণ আসার আগে pending vowel flush করো
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(result, 1);
        } else {
            // অন্য কার (ু, ূ, ৃ, ো, ৌ ইত্যাদি)
            prevChar = getPreviousChar(ic);
            if (prevChar.equals("\u0985")) {
                // অ → স্বরবর্ণ
                ic.deleteSurroundingText(1, 0);
                ic.commitText(convertKarToVowel(result), 1);
                isG_Pressed = false; return;
            }
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(result, 1);
        }
        isG_Pressed = false;
    }

    // ══════════════════════════════════════
    // PHYSICAL KEYBOARD HANDLER
    // ══════════════════════════════════════
    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return super.onKeyDown(keyCode, event);
        if (event.isCtrlPressed()) return super.onKeyDown(keyCode, event);

        if (keyCode == KeyEvent.KEYCODE_DEL) {
            resetStates();
            return super.onKeyDown(keyCode, event);
        }

        if (keyCode == KeyEvent.KEYCODE_SPACE && isG_Pressed && !isEnglishMode) {
            ic.commitText("\u09CD", 1);
            ic.commitText(" ", 1);
            ic.deleteSurroundingText(1, 0);
            isG_Pressed = false;
            return true;
        }

        if (event.isAltPressed() && keyCode == KeyEvent.KEYCODE_SPACE) {
            isEnglishMode = !isEnglishMode;
            isEmojiMode   = false;
            resetStates(); updateKeyLabels();
            return true;
        }

        // Navigation / Enter / Space → pending vowel flush
        if (keyCode == KeyEvent.KEYCODE_SPACE ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT  ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP    ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            isG_Pressed = false;
            return super.onKeyDown(keyCode, event);
        }

        if (isEnglishMode) return super.onKeyDown(keyCode, event);

        // ── External keyboard: Shift+9 → ( এবং Shift+0 → ) (হসন্ত/বিসর্গ নয়) ──
        // মোবাইলের অনস্ক্রিন কীবোর্ডে Shift+9=ৎ, Shift+0=ঃ থাকে,
        // কিন্তু external keyboard থাকলে bracket দরকার হয়
        if (event.getDeviceId() != 0 && !isEnglishMode) {
            // external keyboard detected (deviceId != 0 means physical device)
            if (event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_9) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText("(", 1);
                return true;
            }
            if (event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_0) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText(")", 1);
                return true;
            }
            // Backslash → ৎ, Shift+Backslash → ঃ (external keyboard এ dedicated key)
            if (keyCode == KeyEvent.KEYCODE_BACKSLASH) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                if (event.isShiftPressed()) {
                    ic.commitText("\u0983", 1); // ঃ বিসর্গ
                } else {
                    ic.commitText("\u09CE", 1); // ৎ হসন্ত
                }
                return true;
            }
        }

        if (event.isPrintingKey() || (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)) {
            String tag;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
                tag = String.valueOf(keyCode - KeyEvent.KEYCODE_0);
            else {
                char c = (char) event.getUnicodeChar();
                tag = String.valueOf(c).toLowerCase();
            }
            if (event.isShiftPressed() && tag.equals("7")) {
                // pendingVowel flush করো চন্দ্রবিন্দুর আগে
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                processBengaliLogic(Bijoymaper.getUnicode("7", true), ic);
                return true;
            }
            String res = Bijoymaper.getUnicode(tag, event.isShiftPressed());
            if (res != null && !res.isEmpty() && !res.equals(tag)) {
                processBengaliLogic(res, ic);
                return true;
            }
            // Bengali mode-এ কোনো unmapped printable key (যেমন !, ?, ., ,) → pendingVowel flush করো
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            isG_Pressed = false;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ══════════════════════════════════════
    // VOICE INPUT
    // ══════════════════════════════════════
    private Handler  waveHandler  = new Handler();
    private Runnable waveRunnable;
    private boolean  blinkState   = false;

    private void startWaveAnimation() {
        ImageView mic = keyboardView != null ? keyboardView.findViewById(R.id.btn_mic_top) : null;
        if (mic == null) return;
        waveRunnable = new Runnable() {
            @Override public void run() {
                if (!isListening) return;
                mic.setColorFilter(blinkState
                    ? android.graphics.Color.RED
                    : android.graphics.Color.parseColor("#94A3B8"));
                blinkState = !blinkState;
                waveHandler.postDelayed(this, 500);
            }
        };
        waveHandler.post(waveRunnable);
    }

    private void stopWaveAnimation() {
        waveHandler.removeCallbacks(waveRunnable);
        blinkState = false;
        ImageView mic = keyboardView != null ? keyboardView.findViewById(R.id.btn_mic_top) : null;
        if (mic != null) mic.setColorFilter(android.graphics.Color.parseColor("#94A3B8"));
    }

    private void startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition সাপোর্ট নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }

        String language = isEnglishMode ? "en-US" : "bn-BD";
        isListening = true;
        startWaveAnimation();

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                Toast.makeText(MyKeyboardService.this,
                    isEnglishMode ? "Listening… (English)" : "শুনছি… (বাংলা)",
                    Toast.LENGTH_SHORT).show();
            }
            @Override public void onResults(Bundle results) {
                List<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    if (!isEnglishMode) {
                        text = text
                            .replace("দাঁড়ি",        "।")
                            .replace("কমা",          ",")
                            .replace("প্রশ্নবোধক",   "?")
                            .replace("বিস্ময়বোধক",  "!")
                            .replace("সেমিকোলন",    ";")
                            .replace("কোলন",        ":")
                            .replace("নতুন লাইন",   "\n")
                            .replace("ড্যাশ",       "-")
                            .replace("উদ্ধৃতি",     "\"")
                            .replace("ব্র্যাকেট খোলো", "(")
                            .replace("ব্র্যাকেট বন্ধ",  ")")
                            .replace("স্পেস",       " ");
                    } else {
                        text = text
                            .replace(" comma",             ",")
                            .replace(" period",            ".")
                            .replace(" full stop",         ".")
                            .replace(" question mark",     "?")
                            .replace(" exclamation mark",  "!")
                            .replace(" new line",          "\n")
                            .replace(" semicolon",         ";")
                            .replace(" colon",             ":")
                            .replace(" dash",              "-");
                    }
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.commitText(text, 1);
                }
                isListening = false;
                stopWaveAnimation();
                if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
            }
            @Override public void onError(int error) {
                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:               msg = "কোনো কথা বোঝা যায়নি";       break;
                    case SpeechRecognizer.ERROR_NETWORK:                msg = "নেটওয়ার্ক সমস্যা";            break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "Microphone permission নেই"; break;
                    default:                                             msg = "ত্রুটি — আবার চেষ্টা করুন"; break;
                }
                Toast.makeText(MyKeyboardService.this, msg, Toast.LENGTH_SHORT).show();
                isListening = false;
                stopWaveAnimation();
                if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle b) {}
            @Override public void onEvent(int t, Bundle b) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,              RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,                    language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,         language);
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,                 1);
        speechRecognizer.startListening(intent);
    }

    // ══════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════

    /** pending vowel flush + state reset */
    private void resetStates() {
        if (!pendingVowel.isEmpty()) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(pendingVowel, 1);
            pendingVowel = "";
        }
        isG_Pressed = false;
    }

    /** কার চিহ্ন কিনা পরীক্ষা করে */
    private boolean isBengaliKar(String s) {
        return "\u09BE\u09BF\u09C0\u09C1\u09C2\u09C3\u09C7\u09C8\u09CB\u09CC\u09D7".contains(s);
    }

    /** কার → স্বরবর্ণ রূপান্তর */
    private String convertKarToVowel(String kar) {
        switch (kar) {
            case "\u09BE": return "\u0986";  // া → আ
            case "\u09BF": return "\u0987";  // ি → ই
            case "\u09C0": return "\u0988";  // ী → ঈ
            case "\u09C1": return "\u0989";  // ু → উ
            case "\u09C2": return "\u098A";  // ূ → ঊ
            case "\u09C3": return "\u098B";  // ৃ → ঋ
            case "\u09C7": return "\u098F";  // ে → এ
            case "\u09C8": return "\u0990";  // ৈ → ঐ
            case "\u09CB": return "\u0993";  // ো → ও
            case "\u09CC": return "\u0994";  // ৌ → ঔ
            default:       return kar;
        }
    }

    private String getPreviousChar(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(1, 0);
        return (b != null) ? b.toString() : "";
    }
}
