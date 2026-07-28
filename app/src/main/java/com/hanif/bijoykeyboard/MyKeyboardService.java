package com.hanif.bijoykeyboard;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
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
import java.util.HashMap;
import java.util.Map;

public class MyKeyboardService extends InputMethodService {

    private String pendingVowel = "";
    private ArrayList<String> clipboardHistory = new ArrayList<>();
    private boolean isG_Pressed = false;
    private boolean isEnglishMode = false;
    private boolean isCapsLock = false;      // শুধু ইংরেজি মোডে সক্রিয় থাকে — Shift-এ ডাবল ট্যাপ করলে অন হয়
    private long lastShiftTapTime = 0;       // ডাবল ট্যাপ ডিটেকশনের জন্য

    // Adaptive word suggestion: ব্যবহারকারী যে শব্দ যতবার লিখেছে, তার count।
    // SharedPreferences("word_freq")-এ persist হয়, app বন্ধ করলেও থাকবে।
    private HashMap<String, Integer> adaptiveWords = new HashMap<>();
    private static final int MAX_ADAPTIVE_WORDS = 500;

    // Space বাটনে ৩ সেকেন্ড হোল্ড করলে সিস্টেমের কিবোর্ড-সুইচার (অন্য কিবোর্ড অ্যাপ
    // বেছে নেওয়ার ডায়ালগ) খুলবে। এই ফ্ল্যাগ দিয়ে বোঝা হয় লং-প্রেস ট্রিগার হয়েছে
    // কিনা, যাতে আঙুল তোলার সময় ভুলে একটা space কমিট না হয়ে যায়।
    private boolean spaceLongPressTriggered = false;
    private boolean isShiftPressed = false;
    private boolean isSymbolMode = false;
    private boolean isEmojiMode = false;
    private boolean isCtrlPressed = false;

    // কিছু Bluetooth/এক্সটার্নাল কিবোর্ডে Ctrl/Alt কী ছাড়ার (keyUp) ইভেন্টটা মিস হয়ে যায়।
    // তখন সিস্টেম event.isCtrlPressed()/event.isAltPressed() আসলে কী ছাড়ার পরেও
    // অনেকক্ষণ true রিপোর্ট করতে থাকে ("স্টাক" মেটা-স্টেট)। এর ফলে পরে যখনই ইউজার
    // সাধারণভাবে টাইপ করার সময় শুধু V অক্ষরটা চাপে, তখন কোডটা ভুলবশত মনে করে
    // Ctrl+Alt+V শর্টকাট চাপা হয়েছে — আর ভাষা নিজে নিজে বদলে যায় (টাইপ করতে করতে
    // বারবার ভাষা পরিবর্তন হওয়ার মূল কারণ এটাই)। আবার একই কারণে, ইচ্ছাকৃতভাবে
    // Ctrl+Alt+V চাপলেও কখনো ঠিকভাবে টগল না হওয়ার মতো সমস্যাও হতে পারে।
    // সমাধান: সিস্টেমের রিপোর্ট করা মেটা-স্টেটের ওপর সম্পূর্ণ ভরসা না করে, Ctrl ও Alt
    // কী-এর real down/up নিজেরাই ট্র্যাক করা হচ্ছে, এবং দুটো কী চাপার একটা নির্দিষ্ট
    // সময়সীমার (ALT_COMBO_WINDOW_MS) মধ্যেই V চাপলে সেটাকে ইচ্ছাকৃত কম্বো ধরা হয়।
    // এই সময়সীমা পার হয়ে যাওয়া কোনো leftover/stuck flag উপেক্ষা করা হয়, যাতে
    // সাধারণ টাইপিংয়ে (শুধু "v" লিখলে) ভাষা নিজে নিজে না বদলায়।
    private boolean altKeyDown = false;
    private long altDownAtMs = 0L;
    private boolean ctrlKeyDown = false;
    private long ctrlDownAtMs = 0L;
    private static final long ALT_COMBO_WINDOW_MS = 1200;

    private Button btnCtrl;
    private View keyboardView;
    private SpeechRecognizer speechRecognizer = null;
    private boolean isListening = false;
    private Vibrator vibrator;

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
        updateSuggestionStrip();
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
        loadAdaptiveWords();
    }

    @Override
    public View onCreateInputView() {
        keyboardView = getLayoutInflater().inflate(R.layout.keyboard_layout, null);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        setupKeyboard();
        updateKeyLabels();
        return keyboardView;
    }

    // নতুন কোনো টেক্সট ফিল্ডে ফোকাস গেলে (ট্যাব চেপে বা ক্লিক করে) Android এই মেথডটা
    // কল করে। এখানে ইচ্ছাকৃতভাবে isEnglishMode রিসেট করা হচ্ছে না — যাতে এক ফিল্ড
    // থেকে অন্য ফিল্ডে গেলে ভাষা নিজে থেকে বাংলায় ফিরে না যায়। শুধু UI (কী লেবেল,
    // ভাষা বাটনের টেক্সট) রিফ্রেশ রাখার জন্য এইটুকু রাখা হলো।
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        updateKeyLabels();
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

        ArrayList<String> pinnedItems = getPinnedItems();
        ArrayList<String> allItems = new ArrayList<>();
        for (String p : pinnedItems) allItems.add("📌 " + p);
        for (String h : clipboardHistory) {
            if (!pinnedItems.contains(h)) allItems.add(h);
        }

        for (String rawText : allItems) {
            boolean isPinned = rawText.startsWith("📌 ");
            String text = isPinned ? rawText.substring(3) : rawText;

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

            btn.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(text, 1);
                doHaptic();
                // পেস্ট করার পর ক্লিপবোর্ড স্ট্রিপ বন্ধ করে suggestion স্পেস দেখানো হচ্ছে
                View clipboardScroll = keyboardView.findViewById(R.id.clipboard_scroll);
                View suggestionStrip = keyboardView.findViewById(R.id.suggestion_strip);
                if (clipboardScroll != null && suggestionStrip != null) {
                    clipboardScroll.setVisibility(View.GONE);
                    suggestionStrip.setVisibility(View.VISIBLE);
                }
            });

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

            container.addView(btn);
        }
    }

    private ArrayList<String> getPinnedItems() {
        android.content.SharedPreferences prefs = getSharedPreferences("clipboard_pins", MODE_PRIVATE);
        String raw = prefs.getString("pins", "");
        ArrayList<String> list = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String s : raw.split("\\|\\|")) if (!s.isEmpty()) list.add(s);
        }
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

    // ══════════════════════════════════════════════════════════════════
    // WORD SUGGESTION — adaptive learning + starter dictionary
    // ══════════════════════════════════════════════════════════════════

    private void loadAdaptiveWords() {
        android.content.SharedPreferences prefs = getSharedPreferences("word_freq", MODE_PRIVATE);
        String raw = prefs.getString("freq", "");
        adaptiveWords.clear();
        if (!raw.isEmpty()) {
            for (String pair : raw.split("\\|\\|")) {
                if (pair.isEmpty()) continue;
                int sep = pair.lastIndexOf(':');
                if (sep <= 0) continue;
                String w = pair.substring(0, sep);
                try {
                    adaptiveWords.put(w, Integer.parseInt(pair.substring(sep + 1)));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void saveAdaptiveWords() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : adaptiveWords.entrySet()) {
            sb.append(e.getKey()).append(":").append(e.getValue()).append("||");
        }
        getSharedPreferences("word_freq", MODE_PRIVATE).edit()
            .putString("freq", sb.toString()).apply();
    }

    // স্পেস/এন্টার/দাঁড়ি/কমার আগে যে শব্দটা লেখা শেষ হলো, সেটা শেখানো হচ্ছে —
    // পরের বার একই শব্দ লিখতে গেলে এটা দ্রুত suggestion-এ উপরে চলে আসবে
    private void learnWord(String word) {
        if (word == null) return;
        word = word.trim();
        if (word.length() < 2) return; // একটা মাত্র অক্ষর শেখানোর দরকার নেই
        Integer count = adaptiveWords.get(word);
        adaptiveWords.put(word, (count == null ? 0 : count) + 1);

        if (adaptiveWords.size() > MAX_ADAPTIVE_WORDS) {
            String minKey = null; int minVal = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : adaptiveWords.entrySet()) {
                if (e.getValue() < minVal) { minVal = e.getValue(); minKey = e.getKey(); }
            }
            if (minKey != null) adaptiveWords.remove(minKey);
        }
        saveAdaptiveWords();
    }

    private void learnCurrentWord() {
        learnWord(getCurrentWordBeingTyped());
    }

    // কার্সরের ঠিক আগে যে শব্দটা লেখা হচ্ছে (এখনো space/দাঁড়ি পড়েনি), সেটা বের করা
    private String getCurrentWordBeingTyped() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(40, 0);
        if (before == null) return "";
        String text = before.toString();
        int i = text.length();
        while (i > 0 && !isWordBoundaryChar(text.charAt(i - 1))) i--;
        return text.substring(i);
    }

    private boolean isWordBoundaryChar(char c) {
        return Character.isWhitespace(c) || ",।.!?;:()\"'—–-…\n".indexOf(c) >= 0;
    }

    // এখন যা টাইপ হচ্ছে তার prefix মিলিয়ে সবচেয়ে সম্ভাব্য শব্দগুলো suggestion_strip-এ দেখানো।
    // adaptive (ইউজারের নিজের লেখা) শব্দের weight বেশি, starter dictionary baseline।
    private void updateSuggestionStrip() {
        if (keyboardView == null) return;
        LinearLayout strip = keyboardView.findViewById(R.id.suggestion_strip);
        if (strip == null) return;
        strip.removeAllViews();

        final String prefix = getCurrentWordBeingTyped();
        if (prefix.isEmpty()) return;

        HashMap<String, Integer> scores = new HashMap<>();
        for (Map.Entry<String, Integer> e : adaptiveWords.entrySet()) {
            if (!e.getKey().equals(prefix) && e.getKey().startsWith(prefix)) {
                scores.put(e.getKey(), e.getValue() * 10); // নিজের শেখা শব্দ অগ্রাধিকার পাবে
            }
        }
        for (String w : STARTER_WORDS) {
            if (!scores.containsKey(w) && !w.equals(prefix) && w.startsWith(prefix)) {
                scores.put(w, 1);
            }
        }
        if (scores.isEmpty()) return;

        ArrayList<String> candidates = new ArrayList<>(scores.keySet());
        candidates.sort((a, b) -> scores.get(b) - scores.get(a));

        int shown = 0;
        for (String word : candidates) {
            if (shown >= 4) break;
            addSuggestionChip(strip, word, prefix);
            shown++;
        }
    }

    private void addSuggestionChip(LinearLayout strip, String word, String prefix) {
        TextView chip = new TextView(this);
        chip.setText(word);
        chip.setTextSize(13);
        chip.setTextColor(0xFFE5E7EB);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(16, 4, 16, 4);
        chip.setBackgroundResource(R.drawable.key_background);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(4, 4, 4, 4);
        chip.setLayoutParams(p);
        chip.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            doHaptic();
            if (prefix.length() > 0) ic.deleteSurroundingText(prefix.length(), 0);
            ic.commitText(word + " ", 1);
            learnWord(word);
            updateSuggestionStrip();
        });
        strip.addView(chip);
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
                            if (isShiftPressed && !isCapsLock) { isShiftPressed = false; updateKeyLabels(); }
                            updateSuggestionStrip();
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

        Button btnCommaEmoji = keyboardView.findViewById(R.id.btn_comma);
        if (btnCommaEmoji != null) {
            btnCommaEmoji.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (isSymbolMode) {
                    isEmojiMode = true;
                    isSymbolMode = false;
                    showEmojiPanel();
                } else {
                    if (ic != null) {
                        learnCurrentWord();
                        pendingVowel = "";  // discard
                        ic.commitText(",", 1);
                        updateSuggestionStrip();
                    }
                }
            });
        }

        keyboardView.findViewById(R.id.btn_period).setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                learnCurrentWord();
                pendingVowel = "";  // discard
                ic.commitText(".", 1);
                updateSuggestionStrip();
            }
            isG_Pressed = false;
        });

        keyboardView.findViewById(R.id.btn_shift).setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            boolean isDoubleTap = (now - lastShiftTapTime) < 350;
            lastShiftTapTime = now;

            if (isDoubleTap && isEnglishMode) {
                // ডাবল ট্যাপ — শুধু ইংরেজি মোডে ক্যাপস লক টগল হবে
                isCapsLock = !isCapsLock;
                isShiftPressed = isCapsLock;
            } else if (isCapsLock) {
                // ক্যাপস লক চালু থাকা অবস্থায় সাধারণ ট্যাপ দিলে সেটা অফ হয়ে যাবে
                isCapsLock = false;
                isShiftPressed = false;
            } else {
                isShiftPressed = !isShiftPressed;
            }
            updateKeyLabels();
            v.setAlpha(isCapsLock ? 0.3f : (isShiftPressed ? 0.5f : 1.0f));
        });

        keyboardView.findViewById(R.id.btn_lang).setOnClickListener(v -> {
            isEnglishMode = !isEnglishMode;
            isSymbolMode = false;
            isEmojiMode = false;
            updateKeyLabels();
            resetStates();
        });

        // ক্লিপবোর্ড টগল আইকন — ট্যাপ করলে ক্লিপবোর্ড স্ট্রিপ দেখাবে/লুকাবে।
        // কপি করা জিনিসপত্র সবসময় দেখা যাবে না, শুধু এই আইকনে ট্যাপ করলেই দেখা যাবে।
        keyboardView.findViewById(R.id.btn_clipboard_toggle).setOnClickListener(v -> {
            doHaptic();
            View clipboardScroll = keyboardView.findViewById(R.id.clipboard_scroll);
            View suggestionStrip = keyboardView.findViewById(R.id.suggestion_strip);
            if (clipboardScroll == null || suggestionStrip == null) return;

            boolean isOpen = clipboardScroll.getVisibility() == View.VISIBLE;
            if (isOpen) {
                clipboardScroll.setVisibility(View.GONE);
                suggestionStrip.setVisibility(View.VISIBLE);
            } else {
                showClipboardInUI(); // এখনকার একই লজিক দিয়ে সবশেষ কপি করা জিনিস রিফ্রেশ করা
                suggestionStrip.setVisibility(View.GONE);
                clipboardScroll.setVisibility(View.VISIBLE);
            }
        });

        keyboardView.findViewById(R.id.btn_symbol).setOnClickListener(v -> {
            isSymbolMode = !isSymbolMode;
            isEmojiMode = false;
            if (isSymbolMode) setInputView(keyboardView);
            updateKeyLabels();
        });

        Button btnSpace = keyboardView.findViewById(R.id.btn_space);

        final Runnable spaceLongPressRunnable = () -> {
            spaceLongPressTriggered = true;
            doHaptic();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        };

        btnSpace.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    spaceLongPressTriggered = false;
                    repeatUpdateHandler.postDelayed(spaceLongPressRunnable, 3000);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    repeatUpdateHandler.removeCallbacks(spaceLongPressRunnable);
                    break;
            }
            return false; // স্বাভাবিক ক্লিক প্রসেসিং চলতে দেওয়া হচ্ছে
        });

        btnSpace.setOnClickListener(v -> {
            if (spaceLongPressTriggered) {
                // কিবোর্ড-সুইচার খোলার পর আঙুল তোলায় যে ক্লিক আসে, সেটাতে আর
                // space বসানো হবে না
                spaceLongPressTriggered = false;
                return;
            }
            doHaptic();
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            learnCurrentWord();
            pendingVowel = "";  // discard — ক+ি+space → "ক " হবে, "কি " নয়
            if (isG_Pressed && !isEnglishMode) {
                ic.commitText("\u09CD", 1);
                ic.commitText(" ", 1);
                ic.deleteSurroundingText(1, 0);
                isG_Pressed = false;
            } else {
                ic.commitText(" ", 1);
                isG_Pressed = false;
            }
            updateSuggestionStrip();
        });

        keyboardView.findViewById(R.id.btn_enter).setOnClickListener(v -> {
            doHaptic();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                learnCurrentWord();
                pendingVowel = "";  // discard
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                updateSuggestionStrip();
            }
            isG_Pressed = false;
        });

        ImageView btnMicTop = keyboardView.findViewById(R.id.btn_mic_top);
        if (btnMicTop != null) {
            btnMicTop.setOnClickListener(v -> startVoiceInput());
        }

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
        // ক্যাপস লক শুধু ইংরেজি মোডের জন্য — বাংলায় চলে গেলে সাথে সাথে অফ হয়ে যাবে
        if (!isEnglishMode && isCapsLock) {
            isCapsLock = false;
            isShiftPressed = false;
        }
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

        Button btnComma = keyboardView.findViewById(R.id.btn_comma);
        if (btnComma != null) {
            if (isSymbolMode) btnComma.setText("😊");
            else btnComma.setText(",");
        }

        Button langBtn = keyboardView.findViewById(R.id.btn_lang);
        if (langBtn != null) langBtn.setText(isEnglishMode ? "Eng" : "বাং");

        View shiftBtn = keyboardView.findViewById(R.id.btn_shift);
        if (shiftBtn != null) {
            shiftBtn.setAlpha(isCapsLock ? 0.3f : (isShiftPressed ? 0.5f : 1.0f));
        }

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

    // ══════════════════════════════════════
    // WORD SUGGESTION — starter dictionary
    // কমন বাংলা শব্দ + ব্যবহারকারীর পরিচিত নাম/জায়গার নাম (হানিফ, সোয়াদ,
    // সান্তনা, নশিরপুর, আব্বা, মা, হাবিবা, হিন্দা ইত্যাদি)। adaptive learning-এর
    // সাথে মিলে এই লিস্টটা 'cold start' suggestion দেয় — প্রথম দিনেই কিছু
    // suggestion দেখা যাবে, পরে ব্যবহারকারীর নিজের লেখা শব্দ শেখা হতে থাকবে।
    // ══════════════════════════════════════
    private static final String[] STARTER_WORDS = {
        "হানিফ","সোয়াদ","সান্তনা","নশিরপুর","আব্বা","মা","হাবিবা","হিন্দা","আমি","আমরা",
        "তুমি","তোমরা","তিনি","তারা","সে","এই","ওই","আপনি","আপনারা","আমার",
        "আমাদের","তোমার","তোমাদের","তার","তাদের","এটা","ওটা","এটি","ওটি","আমাকে",
        "তোমাকে","তাকে","আমাদেরকে","তোমাদেরকে","তাদেরকে","নিজে","নিজেই","কেউ","কেউকে","কি",
        "কী","কে","কোথায়","কখন","কেন","কিভাবে","কীভাবে","কোন","কয়টা","কতটা",
        "কতদিন","কার","কাদের","কোনটা","কোনগুলো","করি","করো","করে","করেন","করছি",
        "করছে","করছেন","করব","করবে","করবেন","করেছি","করেছে","করেছেন","করা","করতে",
        "করলে","করলাম","করলো","করলেন","যাই","যাও","যায়","যান","যাচ্ছি","যাচ্ছে",
        "যাচ্ছেন","যাব","যাবে","যাবেন","গিয়েছি","গিয়েছে","গিয়েছেন","যাওয়া","গেলাম","গেলো",
        "আসি","আসো","আসে","আসেন","আসছি","আসছে","আসছেন","আসব","আসবে","আসবেন",
        "এসেছি","এসেছে","এসেছেন","আসা","এলাম","এলো","খাই","খাও","খায়","খান",
        "খাচ্ছি","খাচ্ছে","খাচ্ছেন","খাব","খাবে","খাবেন","খেয়েছি","খেয়েছে","খেয়েছেন","খাওয়া",
        "দেখি","দেখো","দেখে","দেখেন","দেখছি","দেখছে","দেখছেন","দেখব","দেখবে","দেখবেন",
        "দেখেছি","দেখেছে","দেখেছেন","দেখা","বলি","বলো","বলে","বলেন","বলছি","বলছে",
        "বলছেন","বলব","বলবে","বলবেন","বলেছি","বলেছে","বলেছেন","বলা","বললাম","বললো",
        "বললেন","শুনি","শুনো","শুনে","শুনেন","শুনছি","শুনছে","শুনছেন","শুনব","শুনবে",
        "শুনবেন","নিই","নিয়ে","নিলাম","নিলো","নিলেন","নেব","নেবে","নেবেন","দিই",
        "দিয়ে","দিলাম","দিলো","দিলেন","দেব","দেবে","দেবেন","দেওয়া","হই","হও",
        "হয়","হন","হচ্ছি","হচ্ছে","হচ্ছেন","হব","হবে","হবেন","হয়েছি","হয়েছে",
        "হয়েছেন","হওয়া","হলাম","হলো","হলেন","আছি","আছো","আছে","আছেন","থাকি",
        "থাকো","থাকে","থাকেন","থাকব","থাকবে","থাকবেন","থাকা","ছিলাম","ছিলো","ছিলেন",
        "লাগবে","লাগে","লাগছে","পারি","পারো","পারে","পারেন","পারব","পারবে","পারবেন",
        "পারা","জানি","জানো","জানে","জানেন","জানতাম","জানব","জানবে","জানবেন","জানা",
        "চাই","চাও","চায়","চান","চেয়েছি","চেয়েছে","চেয়েছেন","চাওয়া","পাই","পাও",
        "পায়","পান","পেয়েছি","পেয়েছে","পেয়েছেন","পাওয়া","লিখি","লিখো","লিখে","লিখেন",
        "লিখছি","লিখব","লিখবে","লিখবেন","লেখা","লিখেছি","পড়ি","পড়ো","পড়ে","পড়েন",
        "পড়ছি","পড়ব","পড়বে","পড়বেন","পড়া","পড়েছি","ঘুমাই","ঘুমাও","ঘুমায়","ঘুমান",
        "ঘুমাচ্ছি","ঘুমানো","হাসি","হাসো","হাসে","হাসেন","হাসছি","হাসা","কাঁদি","কাঁদে",
        "কান্না","বসি","বসো","বসে","বসেন","বসছি","বসা","দাঁড়াই","দাঁড়ায়","দাঁড়ানো",
        "চলি","চলো","চলে","চলেন","চলছি","চলা","হাঁটি","হাঁটে","হাঁটা","দৌড়াই",
        "দৌড়ানো","খেলি","খেলো","খেলে","খেলেন","খেলছি","খেলা","কাজ","আজ","আজকে",
        "গতকাল","আগামীকাল","পরশু","গতপরশু","এখন","এখনই","তখন","সকাল","দুপুর","বিকাল",
        "সন্ধ্যা","রাত","রাতে","ভোর","মধ্যরাত","সপ্তাহ","মাস","বছর","দিন","ক্ষণ",
        "মুহূর্ত","সময়","ঘণ্টা","মিনিট","সেকেন্ড","সোমবার","মঙ্গলবার","বুধবার","বৃহস্পতিবার","শুক্রবার",
        "শনিবার","রবিবার","জানুয়ারি","ফেব্রুয়ারি","মার্চ","এপ্রিল","মে","জুন","জুলাই","আগস্ট",
        "সেপ্টেম্বর","অক্টোবর","নভেম্বর","ডিসেম্বর","পরে","আগে","সাথে","সবসময়","কখনো","কখনোই",
        "মাঝে","প্রায়ই","আব্বু","আম্মু","বাবা","মামা","মামী","চাচা","চাচী","ফুফা",
        "ফুফু","খালা","খালু","দাদা","দাদী","নানা","নানী","ভাই","বোন","ভাইয়া",
        "আপু","আপা","বড়ভাই","ছোটভাই","বড়বোন","ছোটবোন","স্বামী","স্ত্রী","স্বজন","আত্মীয়",
        "পরিবার","সন্তান","ছেলে","মেয়ে","নাতি","নাতনি","জামাই","বউ","বন্ধু","বান্ধবী",
        "প্রতিবেশী","চাচাতো","মামাতো","খালাতো","ফুফাতো","বাড়ি","ঘর","দরজা","জানালা","রাস্তা",
        "শহর","গ্রাম","দেশ","পৃথিবী","আকাশ","মাটি","পানি","জল","আগুন","বাতাস",
        "গাছ","ফুল","পাতা","ফল","সবজি","খাবার","ভাত","মাছ","মাংস","দুধ",
        "চা","রুটি","তেল","লবণ","চিনি","মরিচ","বাজার","দোকান","স্কুল","কলেজ",
        "বিশ্ববিদ্যালয়","হাসপাতাল","অফিস","কারখানা","মসজিদ","মন্দির","বই","খাতা","কলম","পেন্সিল",
        "ব্যাগ","জামা","কাপড়","জুতা","টাকা","পয়সা","মোবাইল","ফোন","কম্পিউটার","ইন্টারনেট",
        "টিভি","রেডিও","গাড়ি","বাস","ট্রেন","প্লেন","নৌকা","রিকশা","চাকরি","ব্যবসা",
        "পরীক্ষা","রেজাল্ট","ছুটি","ভ্রমণ","অনুষ্ঠান","উৎসব","বিয়ে","জন্মদিন","সমস্যা","সমাধান",
        "কারণ","ফলাফল","পরিকল্পনা","সিদ্ধান্ত","সুযোগ","ইচ্ছা","স্বপ্ন","লক্ষ্য","মন","হৃদয়",
        "শরীর","মাথা","হাত","পা","চোখ","কান","নাক","মুখ","চুল","দাঁত",
        "সংবাদ","খবর","তথ্য","কথা","গল্প","কবিতা","গান","সিনেমা","নাটক","ক্রিকেট",
        "ফুটবল","ভালো","খারাপ","সুন্দর","বড়","ছোট","নতুন","পুরাতন","পুরনো","লম্বা",
        "খাটো","মোটা","চিকন","গরম","ঠান্ডা","মিষ্টি","টক","ঝাল","নরম","শক্ত",
        "সহজ","কঠিন","সুখী","দুখী","খুশি","রাগী","ভয়","চিন্তিত","ব্যস্ত","ফ্রি",
        "ধনী","গরিব","তাজা","পরিষ্কার","নোংরা","উজ্জ্বল","অন্ধকার","শান্ত","অস্থির","স্বাস্থ্যকর",
        "অসুস্থ","এবং","কিন্তু","অথবা","তাই","তাহলে","তবে","যদি","যদিও","কেননা",
        "যেমন","অর্থাৎ","মানে","অবশ্যই","হয়তো","সম্ভবত","নিশ্চয়ই","আসলে","সত্যি","মিথ্যা",
        "এখানে","ওখানে","সেখানে","যেখানে","সবখানে","ভেতরে","বাইরে","উপরে","নিচে","পাশে",
        "সামনে","পিছনে","মাঝখানে","একসাথে","আলাদা","সবাই","কেউনা","সব","কিছু","সবকিছু",
        "সালাম","আসসালামু","আলাইকুম","ওয়ালাইকুম","শুকরিয়া","ধন্যবাদ","দুঃখিত","মাফ","ক্ষমা","স্বাগতম",
        "শুভ","শুভরাত্রি","শুভকামনা","অভিনন্দন","মোবারক","ইনশাআল্লাহ","আলহামদুলিল্লাহ","মাশাআল্লাহ","সুপ্রভাত","শুভেচ্ছা",
        "ভালোবাসা","দোয়া","বরকত","এক","দুই","তিন","চার","পাঁচ","ছয়","সাত",
        "আট","নয়","দশ","শত","হাজার","লক্ষ","কোটি","প্রথম","দ্বিতীয়","তৃতীয়",
        "শেষ","অর্ধেক","hello","thanks","please","sorry","ok","yes","no","okay",
        "good","morning","love","you","today","tomorrow","work","home","phone","message",
        "call","time"
    };

    private static final String[][] EMOJI_CATEGORIES = {
        {
         "😊","😀","😃","😄","😁","😆","😅","🤣",
         "😂","🙂","🙃","🫠","😉","😇","🥰","😍",
         "😘","😗","😙","😚","😋","😛","😝","😜",
         "🤪","🤨","🧐","🤓","😎","🥸","🤩","🥳",
         "🙂‍↕️","😏","😒","🙂‍↔️","😞","😔","😟","😕",
         "🙁","☹️","😣","😖","😫","😩","🥺","😢",
         "😭","😤","😠","😡","🤬","🤯","😳","🥵",
         "🥶","😱","😨","😰","😥","😓","🤗","🤔",
         "🫣","🤭","🫢","🫡","🤫","🤥","😶","🫥",
         "😐","🫤","😑","😬","🙄","😯","😦","😧",
         "😮","😲","🥱","😴","🤤","😪","😵","🤐",
         "🥴","🤢","🤮","🤧","😷","🤒","🤕","🤑",
         "🤠","😈","👿","👹","👺","🤡","💩","👻",
         "💀","☠️","👽","👾","🤖","🎃","😺","😸",
         "😹","😻","😼","😽","🙀","😿","😾"},
        {
         "👋","🤚","🖐️","✋","🖖","🫱","🫲","🫳",
         "🫴","👌","🤌","🤏","✌️","🤞","🫰","🤟",
         "🤘","🤙","👈","👉","👆","🖕","👇","☝️",
         "🫵","👍","👎","✊","👊","🤛","🤜","👏",
         "🙌","🫶","👐","🤲","🤝","🙏","✍️","💅",
         "🤳","💪","🦾","🦿","🦵","🦶","👂","🦻",
         "👃","🫀","🫁","🧠","🦷","🦴","👀","👁️",
         "👅","👄","🫦","👶","🧒","👦","👧","🧑",
         "👱","👨","🧔","👩","🧓","👴","👵","🙍",
         "🙎","🙅","🙆","💁","🙋","🧏","🙇","🤦",
         "🤷","👮","🕵️","💂","🥷","👷","🫅","🤴",
         "👸","👳","👲","🧕","🤵","👰","🤰","🫄",
         "🤱","👼","🎅","🤶","🦸","🦹","🧙","🧚",
         "🧛","🧜","🧝","🧞","🧟","🧌","💆","💇",
         "🚶","🧍","🧎","🏃","💃","🕺","🕴️","👯",
         "🧖","🧗","🤺","🏇","⛷️","🏂","🏌️","🏄",
         "🚣","🏊","⛹️","🏋️","🚴","🚵","🤸","🤼",
         "🤽","🤾","🤹","🧘","🛀","🛌","🧑‍🤝‍🧑","👭",
         "👫","👬","💏","💑","👪","🗣️","👤","👥",
         "🫂","👣"},
        {
         "❤️","🧡","💛","💚","💙","💜","🖤","🤍",
         "🤎","💔","❤️‍🔥","❤️‍🩹","❣️","💕","💞","💓",
         "💗","💖","💘","💝","💟","♥️","💯","💢",
         "💥","💫","💦","💨","🕳️","💬","👁️‍🗨️","🗨️",
         "🗯️","💭","💤","☮️","✝️","☪️","🕉️","☸️",
         "✡️","🔯","🕎","☯️","☦️","🛐","⛎","♈",
         "♉","♊","♋","♌","♍","♎","♏","♐",
         "♑","♒","♓","🆔","⚛️"},
        {
         "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼",
         "🐻‍❄️","🐨","🐯","🦁","🐮","🐷","🐽","🐸",
         "🐵","🙈","🙉","🙊","🐒","🐔","🐧","🐦",
         "🐤","🐣","🐥","🦆","🦅","🦉","🦇","🐺",
         "🐗","🐴","🦄","🐝","🪱","🐛","🦋","🐌",
         "🐞","🐜","🪰","🪲","🪳","🦟","🦗","🕷️",
         "🕸️","🦂","🐢","🐍","🦎","🦖","🦕","🐙",
         "🦑","🦐","🦞","🦀","🪸","🐡","🐠","🐟",
         "🐬","🐳","🐋","🦈","🐊","🐅","🐆","🦓",
         "🦍","🦧","🦣","🐘","🦛","🦏","🐪","🐫",
         "🦒","🦘","🦬","🐃","🐂","🐄","🐎","🐖",
         "🐏","🐑","🦙","🐐","🦌","🐕","🐩","🦮",
         "🐕‍🦺","🐈","🐈‍⬛","🪶","🐓","🦃","🦤","🦚",
         "🦜","🦢","🦩","🕊️","🐇","🦝","🦨","🦡",
         "🦫","🦦","🦥","🐁","🐀","🐿️","🦔","🐾",
         "🐉","🐲"},
        {
         "🌸","🌵","🎄","🌲","🌳","🌴","🪵","🌱",
         "🌿","☘️","🍀","🎍","🪴","🎋","🍃","🍂",
         "🍁","🍄","🐚","🪨","🌾","💐","🌷","🪷",
         "🌹","🥀","🪻","🌺","🌼","🌻","🌞","🌝",
         "🌛","🌜","🌚","🌕","🌖","🌗","🌘","🌑",
         "🌒","🌓","🌔","🌙","🌎","🌍","🌏","🪐",
         "💫","⭐","🌟","✨","⚡","☄️","💥","🔥",
         "🌪️","🌈","☀️","🌤️","⛅","🌥️","☁️","🌦️",
         "🌧️","⛈️","🌩️","🌨️","❄️","☃️","⛄","🌬️",
         "💨","💧","💦","🫧","☔","☂️","🌊","🌫️"},
        {
         "🍎","🍏","🍐","🍊","🍋","🍋‍🟩","🍌","🍉",
         "🍇","🍓","🫐","🍈","🍒","🍑","🥭","🍍",
         "🥥","🥝","🍅","🍆","🥑","🥦","🥬","🥒",
         "🌶️","🫑","🌽","🥕","🫒","🧄","🧅","🥔",
         "🍠","🫚","🥐","🥯","🍞","🥖","🥨","🧀",
         "🥚","🍳","🧈","🥞","🧇","🥓","🥩","🍗",
         "🍖","🦴","🌭","🍔","🍟","🍕","🫓","🥪",
         "🥙","🧆","🌮","🌯","🫔","🥗","🥘","🫕",
         "🥫","🍝","🍜","🍲","🍛","🍣","🍱","🥟",
         "🦪","🍤","🍙","🍚","🍘","🍥","🥠","🥮",
         "🍢","🍡","🍧","🍨","🍦","🥧","🧁","🍰",
         "🎂","🍮","🍭","🍬","🍫","🍿","🍩","🍪",
         "🌰","🥜","🫘","🍯","🥛","🍼","🫖","☕",
         "🍵","🧃","🥤","🧋","🍶","🍺","🍻","🥂",
         "🍷","🥃","🍸","🍹","🧉","🍾","🧊","🥄",
         "🍴","🍽️","🥣","🥡","🥢","🧂"},
        {
         "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉",
         "🥏","🎱","🪀","🏓","🏸","🏒","🏑","🥍",
         "🏏","🪃","🥅","⛳","🪁","🏹","🎣","🤿",
         "🥊","🥋","🎽","🛹","🛼","🛷","⛸️","🥌",
         "🎿","⛷️","🏂","🪂","🏋️","🤼","🤸","⛹️",
         "🤺","🤾","🏌️","🏇","🧘","🏄","🏊","🤽",
         "🚣","🧗","🚵","🚴","🏆","🥇","🥈","🥉",
         "🏅","🎖️","🏵️","🎗️","🎫","🎟️","🎪","🤹",
         "🎭","🩰","🎨","🎬","🎤","🎧","🎼","🎹",
         "🥁","🪘","🎷","🎺","🪗","🎸","🪕","🎻",
         "🎲","♟️","🎯","🎳","🎮","🎰","🧩","🪩",
         "🪅","🪆","🧸","🖼️","🪄"},
        {
         "🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑",
         "🚒","🚐","🛻","🚚","🚛","🚜","🦯","🦽",
         "🦼","🛴","🚲","🛵","🏍️","🛺","🚨","🚔",
         "🚍","🚘","🚖","🚡","🚠","🚟","🚃","🚋",
         "🚞","🚝","🚄","🚅","🚈","🚂","🚆","🚇",
         "🚊","🚉","✈️","🛫","🛬","🛩️","💺","🛰️",
         "🚀","🛸","🚁","🛶","⛵","🚤","🛥️","🛳️",
         "⛴️","🚢","⚓","🪝","⛽","🚧","🚦","🚥",
         "🗺️","🗿","🗽","🗼","🏰","🏯","🏟️","🎡",
         "🎢","🎠","⛲","⛱️","🏖️","🏝️","🏜️","🌋",
         "🏔️","⛰️","🗻","🏕️","🛖","🏠","🏡","🏘️",
         "🏚️","🏗️","🏭","🏢","🏬","🏣","🏤","🏥",
         "🏦","🏨","🏪","🏫","🏩","💒","🏛️","⛪",
         "🕌","🕍","🛕","🕋","⛩️","🌁","🌃","🏙️",
         "🌄","🌅","🌆","🌇","🌉","♨️","🎑","🛤️",
         "🛣️","🗾","🏞️"},
        {
         "💻","⌚","📱","📲","⌨️","🖥️","🖨️","🖱️",
         "🖲️","🕹️","🗜️","💽","💾","💿","📀","📼",
         "📷","📸","📹","🎥","📽️","🎞️","📞","☎️",
         "📟","📠","📺","📻","🎙️","🎚️","🎛️","🧭",
         "⏱️","⏲️","⏰","🕰️","⌛","⏳","📡","🔋",
         "🪫","🔌","💡","🔦","🕯️","🪔","🧯","🛢️",
         "💸","💵","💴","💶","💷","🪙","💰","💳",
         "🧾","💎","⚖️","🪜","🧰","🪛","🔧","🔨",
         "⚒️","🛠️","⛏️","🪚","🔩","⚙️","🪤","🧱",
         "⛓️","🧲","🔫","💣","🧨","🪓","🔪","🗡️",
         "⚔️","🛡️","🚬","⚰️","🪦","⚱️","🏺","🔮",
         "📿","🧿","🪬","💈","⚗️","🔭","🔬","🕳️",
         "🩹","🩺","🩻","🩼","💊","💉","🧬","🦠",
         "🧫","🧪","🌡️","🧹","🪠","🧺","🧻","🚽",
         "🚿","🛁","🛀","🪒","🧴","🧷","🧼","🪥",
         "🪮","🧽","🪣","🛒","🚪","🪞","🪟","🛏️",
         "🛋️","🪑","🪆","🖼️","🪧","🎁","🛍️","👓",
         "🕶️","🥽","🥼","🦺","👔","👕","👖","🧣",
         "🧤","🧥","🧦","👗","👘","🥻","🩱","🩲",
         "🩳","👙","👚","👛","👜","👝","🎒","🩴",
         "👞","👟","🥾","🥿","👠","👡","🩰","👢",
         "👑","👒","🎩","🎓","🧢","🪖","⛑️","💄",
         "💍","💼"},
        {
         "🎉","🎊","🎈","🎁","🎀","🪄","🎗️","🎟️",
         "🎫","🏷️","🔖","🏮","🎆","🎇","🧨","✨",
         "🎍","🎋","🎄","🎃","🎑","🎐","🎏","🪅",
         "🪆","🧧","🎂","🍰","🕯️","🥳","🎭","🖼️",
         "🎨","🧵","🪡","🧶","🪢"},
        {
         "📚","📖","📕","📗","📘","📙","📔","📓",
         "📒","📝","✏️","🖊️","🖋️","🖌️","🖍️","📐",
         "📏","🔬","🔭","🧮","🧪","🧫","⚗️","🧬",
         "🎒","🏫","🎓","📊","📈","📉","📋","📌",
         "📍","📎","🖇️","✂️","🗂️","🗄️","📁","📂",
         "📅","🗓️","⏰","🔖","🏷️","🧑‍🎓","👨‍🏫","👩‍🏫",
         "📇","📃","📜","📄","🗞️","📰","📑","🔢",
         "🔤","🔡","🔠","🌐","💯","🧑‍🔬","🧑‍💻"},
        {
         "🇧🇩","🇮🇳","🇵🇰","🇳🇵","🇧🇹","🇱🇰","🇲🇲","🇲🇻",
         "🇦🇫","🇸🇦","🇦🇪","🇶🇦","🇰🇼","🇴🇲","🇧🇭","🇯🇴",
         "🇱🇧","🇮🇶","🇮🇷","🇸🇾","🇾🇪","🇮🇱","🇵🇸","🇹🇷",
         "🇪🇬","🇱🇾","🇹🇳","🇩🇿","🇲🇦","🇸🇩","🇰🇪","🇳🇬",
         "🇿🇦","🇬🇭","🇪🇹","🇹🇿","🇺🇬","🇷🇼","🇸🇳","🇨🇮",
         "🇨🇲","🇲🇾","🇸🇬","🇮🇩","🇹🇭","🇻🇳","🇵🇭","🇰🇭",
         "🇱🇦","🇧🇳","🇹🇱","🇯🇵","🇰🇷","🇰🇵","🇨🇳","🇭🇰",
         "🇲🇴","🇹🇼","🇲🇳","🇰🇿","🇺🇿","🇹🇲","🇰🇬","🇹🇯",
         "🇺🇸","🇨🇦","🇲🇽","🇧🇷","🇦🇷","🇨🇱","🇨🇴","🇵🇪",
         "🇻🇪","🇪🇨","🇧🇴","🇵🇾","🇺🇾","🇨🇺","🇯🇲","🇭🇹",
         "🇩🇴","🇵🇦","🇨🇷","🇬🇹","🇭🇳","🇸🇻","🇳🇮","🇬🇧",
         "🇮🇪","🇫🇷","🇩🇪","🇮🇹","🇪🇸","🇵🇹","🇳🇱","🇧🇪",
         "🇨🇭","🇦🇹","🇸🇪","🇳🇴","🇩🇰","🇫🇮","🇮🇸","🇵🇱",
         "🇨🇿","🇸🇰","🇭🇺","🇷🇴","🇧🇬","🇬🇷","🇺🇦","🇷🇺",
         "🇧🇾","🇱🇹","🇱🇻","🇪🇪","🇭🇷","🇷🇸","🇸🇮","🇧🇦",
         "🇲🇰","🇦🇱","🇲🇹","🇨🇾","🇱🇺","🇲🇨","🇦🇺","🇳🇿",
         "🇫🇯","🇵🇬","🇺🇳","🏁","🚩","🏳️","🏴","🏳️‍🌈"},
        {
         "🔣","➕","➖","➗","✖️","🟰","♾️","💲",
         "💱","‼️","⁉️","❓","❔","❕","❗","〰️",
         "💠","🔘","🔴","🟠","🟡","🟢","🔵","🟣",
         "🟤","⚫","⚪","🟥","🟧","🟨","🟩","🟦",
         "🟪","🟫","⬛","⬜","◼️","◻️","◾","◽",
         "▪️","▫️","🔶","🔷","🔸","🔹","🔺","🔻",
         "💮","♻️","✅","☑️","✔️","❌","❎","➰",
         "➿","〽️","✳️","✴️","❇️","©️","®️","™️",
         "#️⃣","*️⃣","0️⃣","1️⃣","2️⃣","3️⃣","4️⃣","5️⃣",
         "6️⃣","7️⃣","8️⃣","9️⃣","🔟","🔢","⬆️","↗️",
         "➡️","↘️","⬇️","↙️","⬅️","↖️","↕️","↔️",
         "↩️","↪️","⤴️","⤵️","🔃","🔄","🔙","🔚",
         "🔛","🔜","🔝","🛑","🚫","⛔","📛","⚠️",
         "☢️","☣️","🚸","🔞","📵","🚭","⚧️","♂️",
         "♀️","⚥","⚦","🚹","🚺","🚼","🚻","🛗",
         "🚰","♿","🈳","🈹","🈚","🈶","🈸","🈺",
         "🈷️","㊙️","㊗️","🈴","🈵","🈲","🉐","🉑",
         "🈁","🔯","🕐","🕑","🕒","🕓","🕔","🕕",
         "🕖","🕗","🕘","🕙","🕚","🕛","🔊","🔉",
         "🔈","🔇","📢","📣","📯","🔔","🔕","🎼",
         "🎵","🎶","💹","🔀","🔁","🔂","▶️","⏸️",
         "⏯️","⏹️","⏺️","⏭️","⏮️","⏩","⏪","⏫",
         "⏬","◀️","🔼","🔽"}
    };

    private static final String[] CATEGORY_NAMES = {
        "😊 হাসি","👋 হাত ও শরীর","❤️ মন","🐶 প্রাণী","🌸 প্রকৃতি","🍎 খাবার","⚽ খেলা","🚗 যান ও স্থান","💻 জিনিসপত্র","🎉 উৎসব","📚 পড়াশোনা","🇧🇩 পতাকা","🔣 চিহ্ন"
    };

    private int currentEmojiCategory = 0;
    private View emojiPanelView = null;

    private void showEmojiPanel() {
        if (emojiPanelView == null) {
            emojiPanelView = getLayoutInflater().inflate(R.layout.emoji_panel, null);
        }
        setInputView(emojiPanelView);

        LinearLayout tabs = emojiPanelView.findViewById(R.id.emoji_category_tabs);
        tabs.removeAllViews();

        float density = getResources().getDisplayMetrics().density;
        int chipRadius = (int) (14 * density);
        int chipMarginH = (int) (4 * density);
        int chipMarginV = (int) (6 * density);
        int chipPadH = (int) (14 * density);
        int chipPadV = (int) (6 * density);

        for (int i = 0; i < CATEGORY_NAMES.length; i++) {
            final int idx = i;
            TextView tab = new TextView(this);
            tab.setText(EMOJI_CATEGORIES[i][0]);
            tab.setTextSize(20);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setPadding(chipPadH, chipPadV, chipPadH, chipPadV);

            // প্রতিটা ক্যাটাগরি ট্যাবকে গোলাকৃতির, হালকা কালারের একটা "বক্স/চিপ"
            // হিসেবে দেখানো হচ্ছে — যাতে বোঝা যায় এগুলো হেডিং/গ্রুপ বাটন,
            // শুধু plain টেক্সট নয়। নির্বাচিত ক্যাটাগরি সলিড নীল, বাকিগুলো
            // হালকা (semi-transparent সাদা) বক্স।
            android.graphics.drawable.GradientDrawable chip =
                    new android.graphics.drawable.GradientDrawable();
            chip.setCornerRadius(chipRadius);
            if (i == currentEmojiCategory) {
                chip.setColor(android.graphics.Color.parseColor("#1D4ED8"));
                tab.setTextColor(android.graphics.Color.WHITE);
            } else {
                chip.setColor(android.graphics.Color.parseColor("#33FFFFFF")); // হালকা সাদা
                chip.setStroke((int) (1 * density), android.graphics.Color.parseColor("#55FFFFFF"));
                tab.setTextColor(android.graphics.Color.parseColor("#E5E7EB"));
            }
            tab.setBackground(chip);

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(chipMarginH, chipMarginV, chipMarginH, chipMarginV);
            tab.setLayoutParams(p);

            tab.setOnClickListener(v -> {
                currentEmojiCategory = idx;
                showEmojiPanel();
            });
            tabs.addView(tab);
        }

        loadEmojiGrid(emojiPanelView);

        TextView btnKeyboard = emojiPanelView.findViewById(R.id.btn_emoji_keyboard);
        btnKeyboard.setOnClickListener(v -> {
            isEmojiMode = false;
            setInputView(keyboardView);
        });

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
        int cellSize = screenWidth / 9;

        String[] emojis = EMOJI_CATEGORIES[currentEmojiCategory];
        for (int i = 1; i < emojis.length; i++) {
            final String emoji = emojis[i];
            TextView btn = new TextView(this);
            btn.setText(emoji);
            btn.setTextSize(24);
            btn.setGravity(android.view.Gravity.CENTER);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = cellSize;
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

    private String getEmoji(String tag) {
        return "😀";
    }

    private String getSymbol(String tag, boolean shift) {
        if (shift) {
            switch (tag) {
                case "q": return "["; case "w": return "]"; case "e": return "{"; case "r": return "}";
                case "t": return "©"; case "y": return "®"; case "u": return "™"; case "i": return "§";
                case "o": return "°"; case "p": return "•";
                case "a": return "√"; case "s": return "π"; case "d": return "Δ"; case "f": return "'";
                case "g": return "∴"; case "h": return "€"; case "j": return "¥"; case "k": return "←";
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

    private void doHaptic() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(18);
        }
    }

    private void handleOnScreenKey(String tag) {
        doHaptic();
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
            pendingVowel = "";  // discard
            ic.commitText(getSymbol(tag, isShiftPressed), 1);
            if (isShiftPressed && !isCapsLock) { isShiftPressed = false; updateKeyLabels(); }
            return;
        }
        if (isEnglishMode) {
            ic.commitText(isShiftPressed ? tag.toUpperCase() : tag.toLowerCase(), 1);
        } else {
            String result = Bijoymaper.getUnicode(tag, isShiftPressed);
            processBengaliLogic(result, ic);
        }
        if (isShiftPressed && !isCapsLock) { isShiftPressed = false; updateKeyLabels(); }
        updateSuggestionStrip();
    }

    // ══════════════════════════════════════════════════════════════════
    // BENGALI LOGIC
    // বিজয় নিয়ম: ে / ি / ৈ / ৌ আগে press → pendingVowel এ রাখো
    // পরের ব্যঞ্জন আসলে: ব্যঞ্জন commit → তারপর কার commit
    // ══════════════════════════════════════════════════════════════════
    private void processBengaliLogic(String result, InputConnection ic) {
        if (result == null || result.isEmpty()) return;
        String prevChar;

        // ── 1. আ-কার (া U+09BE) ──────────────────────────────────────
        if (result.equals("\u09BE")) {
            if (isG_Pressed) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText("\u0986", 1); isG_Pressed = false; return;
            }
            prevChar = getPreviousChar(ic);
            if (pendingVowel.equals("\u09C7")) { pendingVowel = ""; ic.commitText("\u09CB", 1); isG_Pressed = false; return; }
            if (prevChar.equals("\u09C7")) { ic.deleteSurroundingText(1, 0); ic.commitText("\u09CB", 1); isG_Pressed = false; return; }
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(result, 1); isG_Pressed = false; return;
        }

        // ── 2. ৌ-কার (U+09CC) ─────────────────────────────────────────
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

        // ── 3. হসন্ত pending + যেকোনো কার → স্বরবর্ণ ─────────────────
        if (isG_Pressed && isBengaliKar(result)) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(convertKarToVowel(result), 1); isG_Pressed = false; return;
        }

        // ── 4. র‍্য (ZWJ) ────────────────────────────────────────────
        if (result.equals("\u09CD\u09AF")) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            prevChar = getPreviousChar(ic);
            ic.commitText(prevChar.equals("\u09B0") ? "\u200D" + result : result, 1);
            isG_Pressed = false; return;
        }

        // ── 5. রেফ (র্) ──────────────────────────────────────────────
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

        // ── 6. হসন্ত (্) → pending ────────────────────────────────────
        if (result.equals("\u09CD")) {
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            isG_Pressed = true; return;
        }

        boolean isKar = isBengaliKar(result);
        boolean isAutoJoint = result.startsWith("\u09CD");

        // ── 7. যুক্তবর্ণ ──────────────────────────────────────────────
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

        // ── 8. ি (U+09BF) / এ-কার (U+09C7) / ৈ-কার (U+09C8) → pendingVowel
        // বিজয় নিয়ম: এই তিনটে কার আগে press হয়, ব্যঞ্জন পরে
        // তাই এখানে pendingVowel এ রেখে দাও — ব্যঞ্জন আসলে section 9 এ flush হবে
        if (result.equals("\u09BF") || result.equals("\u09C7") || result.equals("\u09C8")) {
            // আগের pending flush করে নতুন pending রাখো
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); }
            pendingVowel = result;
            return;
        }

        // ── 9. বাকি সব (ব্যঞ্জন, স্বরবর্ণ, অন্য কার) ──────────────────
        if (!isKar) {
            // ব্যঞ্জন বা স্বরবর্ণ:
            // বিজয় নিয়ম — ব্যঞ্জন আগে commit, তারপর pendingVowel (ে/ি/ৈ) commit
            // যেমন: ে press → pendingVowel="ে", তারপর ব press → "ব" commit → "ে" commit = "বে"
            ic.commitText(result, 1);
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
        } else {
            // অন্য কার (ু, ূ, ৃ ইত্যাদি)
            if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
            ic.commitText(result, 1);
        }
        isG_Pressed = false;
    }
    // ══════════════════════════════════════
    // PHYSICAL / EXTERNAL KEYBOARD HANDLER
    // ══════════════════════════════════════
    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return super.onKeyDown(keyCode, event);

        // ১. সিস্টেম নেভিগেশন ও ফর্ম ফিল্ড মুভমেন্ট কি (Tab ইভেন্ট স্বাভাবিক রাখা)
        if (keyCode == KeyEvent.KEYCODE_TAB || 
            keyCode == KeyEvent.KEYCODE_NAVIGATE_NEXT || 
            keyCode == KeyEvent.KEYCODE_NAVIGATE_PREVIOUS) {
            return super.onKeyDown(keyCode, event);
        }

        // Ctrl/Alt কী নিজেই চাপা হলে — শুধু আসল (repeat নয়) down-এই টাইমস্ট্যাম্প রিসেট করো।
        // এইটুকু নিজস্ব ট্র্যাকিং না রাখলে stuck/leftover meta state-এর কারণে সাধারণ
        // "v" টাইপ করলেও ভাষা ভুলবশত বদলে যেতে পারে (নিচে বিস্তারিত দেখুন)।
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            if (event.getRepeatCount() == 0) { ctrlKeyDown = true; ctrlDownAtMs = System.currentTimeMillis(); }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (event.getRepeatCount() == 0) { altKeyDown = true; altDownAtMs = System.currentTimeMillis(); }
            return super.onKeyDown(keyCode, event);
        }
        // সিস্টেম এখন সঠিকভাবে Ctrl/Alt রিলিজড রিপোর্ট করছে — leftover ট্র্যাকিং সাথে সাথে ক্লিয়ার করো
        if (!event.isCtrlPressed()) ctrlKeyDown = false;
        if (!event.isAltPressed()) altKeyDown = false;

        // ২. Ctrl + Alt + V ল্যাঙ্গুয়েজ সুইচ (বাংলা/ইংরেজি)
        // শুধু event.isCtrlPressed()/isAltPressed()-এর ওপর ভরসা না করে, Ctrl ও Alt
        // দুটোই আমাদের নিজস্ব ট্র্যাকিং অনুযায়ী সম্প্রতি (ALT_COMBO_WINDOW_MS-এর মধ্যে)
        // সত্যিই চাপা হয়েছে কিনা সেটাও চেক করা হচ্ছে — নাহলে stuck meta flag-এর কারণে
        // সাধারণ "v" টাইপেও ভাষা পাল্টে যেতে পারে (এটাই মূল বাগ ছিল)।
        if (keyCode == KeyEvent.KEYCODE_V) {
            long now = System.currentTimeMillis();
            boolean genuineCombo = event.isCtrlPressed() && event.isAltPressed()
                    && ctrlKeyDown && (now - ctrlDownAtMs) <= ALT_COMBO_WINDOW_MS
                    && altKeyDown && (now - altDownAtMs) <= ALT_COMBO_WINDOW_MS;
            if (genuineCombo) {
                if (event.getRepeatCount() == 0) {
                    isEnglishMode = !isEnglishMode;
                    isEmojiMode = false;
                    resetStates();
                    updateKeyLabels();
                    ctrlKeyDown = false; altKeyDown = false; // কম্বো একবার ব্যবহার হয়ে গেলে সাথে সাথে ক্লিয়ার করো
                    Toast.makeText(this, isEnglishMode ? "English Mode" : "বাংলা মোড", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (event.isCtrlPressed() && event.isAltPressed()) {
                // মেটা-ফ্ল্যাগ true থাকলেও আমাদের ট্র্যাকিং অনুযায়ী এটা ইচ্ছাকৃত কম্বো নয়
                // (leftover/stuck) — তাই ভাষা না বদলে "v" স্বাভাবিকভাবেই টাইপ হবে
                ctrlKeyDown = false; altKeyDown = false;
            }
        }

        // ৩. অন্যান্য Ctrl ভিত্তিক শর্টকাটগুলোকে সিস্টেমের হাতে ছেড়ে দেওয়া (Ctrl+C, Ctrl+V, Ctrl+A ইত্যাদি)
        if (event.isCtrlPressed()) {
            return super.onKeyDown(keyCode, event);
        }

        if (keyCode == KeyEvent.KEYCODE_DEL) {
            resetStates();
            return super.onKeyDown(keyCode, event);
        }

        // Space + G pressed logic (যুক্তবর্ণ/হসন্ত)
        if (keyCode == KeyEvent.KEYCODE_SPACE && isG_Pressed && !isEnglishMode) {
            ic.commitText("\u09CD", 1);
            ic.commitText(" ", 1);
            ic.deleteSurroundingText(1, 0);
            isG_Pressed = false;
            return true;
        }

        // সাধারণ Space Key প্রসেসিং
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            pendingVowel = "";
            ic.commitText(" ", 1);
            isG_Pressed = false;
            return true;
        }

        // নেভিগেশন ও এন্টার কি
        if (keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            pendingVowel = "";
            isG_Pressed = false;
            return super.onKeyDown(keyCode, event);
        }

        if (isEnglishMode) return super.onKeyDown(keyCode, event);

        // বাংলা বিজয়ের কি-ম্যাপিং হ্যান্ডলার
        if (event.isPrintingKey() || (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)) {
            String tag;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                tag = String.valueOf(keyCode - KeyEvent.KEYCODE_0);
            } else {
                char c = (char) event.getUnicodeChar();
                tag = String.valueOf(c).toLowerCase();
            }

            if (event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_9) {
                pendingVowel = ""; isG_Pressed = false;
                ic.commitText("(", 1); return true;
            }
            if (event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_0) {
                pendingVowel = ""; isG_Pressed = false;
                ic.commitText(")", 1); return true;
            }
            if (event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_7) {
                processBengaliLogic(Bijoymaper.getUnicode("7", true), ic); return true;
            }

            String res = Bijoymaper.getUnicode(tag, event.isShiftPressed());
            if (res != null && !res.isEmpty() && !res.equals(tag)) {
                processBengaliLogic(res, ic);
                return true;
            }

            char actualChar = (char) event.getUnicodeChar(event.getMetaState());
            if (actualChar != 0) {
                pendingVowel = "";
                ic.commitText(String.valueOf(actualChar), 1);
                isG_Pressed = false;
                return true;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    // Ctrl/Alt কী ছাড়ার (release) সময় আমাদের নিজস্ব ট্র্যাকিং ফ্ল্যাগ সাথে সাথে ক্লিয়ার
    // করে দেওয়া হচ্ছে, যাতে "স্টাক" অবস্থা যতটা সম্ভব কম সময় স্থায়ী হয়। V বা অন্য কোনো
    // কী-এর keyUp ইভেন্ট এখানে ইচ্ছাকৃতভাবে consume করা হচ্ছে না — তাতে স্বাভাবিক
    // টাইপিং/repeat আচরণ (এবং Ctrl+C/V-এর মতো সিস্টেম শর্টকাট) অক্ষত থাকবে।
    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            ctrlKeyDown = false;
        } else if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            altKeyDown = false;
        }
        return super.onKeyUp(keyCode, event);
    }


    // ══════════════════════════════════════
    private Handler waveHandler = new Handler();
    private Runnable waveRunnable;
    private boolean blinkState = false;

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
                            .replace("দাঁড়ি", "।")
                            .replace("কমা", ",")
                            .replace("প্রশ্নবোধক", "?")
                            .replace("বিস্ময়বোধক", "!")
                            .replace("সেমিকোলন", ";")
                            .replace("কোলন", ":")
                            .replace("নতুন লাইন", "\n")
                            .replace("ড্যাশ", "-")
                            .replace("উদ্ধৃতি", "\"")
                            .replace("ব্র্যাকেট খোলো", "(")
                            .replace("ব্র্যাকেট বন্ধ", ")")
                            .replace("স্পেস", " ");
                    } else {
                        text = text
                            .replace(" comma", ",")
                            .replace(" period", ".")
                            .replace(" full stop", ".")
                            .replace(" question mark", "?")
                            .replace(" exclamation mark", "!")
                            .replace(" new line", "\n")
                            .replace(" semicolon", ";")
                            .replace(" colon", ":")
                            .replace(" dash", "-");
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
                    case SpeechRecognizer.ERROR_NO_MATCH: msg = "কোনো কথা বোঝা যায়নি"; break;
                    case SpeechRecognizer.ERROR_NETWORK:  msg = "নেটওয়ার্ক সমস্যা"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "Microphone permission নেই"; break;
                    default: msg = "ত্রুটি — আবার চেষ্টা করুন"; break;
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
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechRecognizer.startListening(intent);
    }

    @Override
    public void onDestroy() {
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
        super.onDestroy();
    }

    // ══════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════
    private void resetStates() {
        pendingVowel = "";  // discard — commit না করে বাদ
        isG_Pressed = false;
    }

    private boolean isBengaliKar(String s) {
        return "\u09BE\u09BF\u09C0\u09C1\u09C2\u09C3\u09C7\u09C8\u09CB\u09CC\u09D7".contains(s);
    }

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

    private String getPreviousChar(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(1, 0);
        return (b != null) ? b.toString() : "";
    }
}
