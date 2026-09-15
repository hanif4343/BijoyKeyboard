package com.hanif.bijoykeyboard;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
import android.text.InputType;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.media.AudioManager;
import android.graphics.Color;

public class MyKeyboardService extends InputMethodService {

    private String pendingVowel = "";
    private ArrayList<String> clipboardHistory = new ArrayList<>();

    // ══════════════════════════════════════
    // হার্ডওয়্যার (ব্লুটুথ/USB) কিবোর্ড দিয়ে Win+V ক্লিপবোর্ড ওভারলে
    // ══════════════════════════════════════
    // এক্সটার্নাল কিবোর্ড লাগানো থাকলে সাধারণত অন-স্ক্রিন কিবোর্ড ভিউ নিজে থেকে
    // ভেসে ওঠে না — Android ধরে নেয় ইউজারের আলাদা করে সফট কিবোর্ড দরকার নেই।
    // Windows+V চাপলে PC-তে যেমন হয় (পুরো কিবোর্ড না, শুধু একটা সরু, ওপর-থেকে-নিচে
    // সাজানো ক্লিপবোর্ড হিস্টোরি প্যানেল), এখানেও ঠিক তেমনই — hw_clipboard_overlay.xml
    // নামের আলাদা, ছোট লেআউট setInputView() দিয়ে সাময়িকভাবে বসানো হয় (পুরো
    // keyboard_layout.xml/QWERTY-এর বদলে), তারপর requestShowSelf(SHOW_FORCED) দিয়ে
    // জোর করে দেখানো হয় — openClipboardPanelViaHardware() দ্রষ্টব্য। প্যানেল বন্ধ
    // হলে (পেস্ট করার পর/Esc/আবার Win+V চেপে) closeClipboardPanel() আগের অবস্থায়
    // (কোনো ভিউ না, হাইড থাকা) ফিরিয়ে আনে।
    // মাউস নেই ধরে নিয়ে (হার্ডওয়্যার কিবোর্ড সাধারণত টাচ-লেস প্রেক্ষাপটে ব্যবহৃত হয়),
    // ওপরে/নিচে অ্যারো দিয়ে আইটেমগুলোর মধ্যে ফোকাস সরানো যায় আর Enter দিয়ে
    // সিলেক্ট/পেস্ট করা যায় — moveHwClipFocus, activateFocusedHwClipChip দ্রষ্টব্য।
    private boolean metaKeyDown = false;
    private long metaDownAtMs = 0L;
    private View hwClipboardOverlayView;
    private LinearLayout hwClipboardListContainer;
    private ScrollView hwClipboardScrollView;
    private List<Button> hwClipChipButtons = new ArrayList<>();
    private int hwClipFocusIndex = -1;
    private boolean clipboardHardwareNavActive = false; // true = কমপ্যাক্ট Win+V ওভারলে বর্তমানে খোলা

    // টাচ দিয়ে (কিবোর্ডের ক্লিপবোর্ড আইকনে ট্যাপ করে) খোলা আনুভূমিক ক্লিপবোর্ড স্ট্রিপের
    // চিপ বাটনগুলোর রেফারেন্স — এটা keyboardView-এর ভেতরের UI, হার্ডওয়্যার ওভারলে থেকে
    // সম্পূর্ণ আলাদা (showClipboardInUI দ্রষ্টব্য)
    private List<Button> clipboardChipButtons = new ArrayList<>();
    private boolean isG_Pressed = false;
    private boolean isEnglishMode = false;
    // *** নতুন: বিজয় ক্লাসিক বনাম বিজয় ইউনিকোড ***
    // শুধু isEnglishMode==false হলেই প্রাসঙ্গিক। false = Classic (আগের/এখনকার আচরণ,
    // ে/ি/ৈ আগে টাইপ করে বাফার করে রাখা হয়, ব্যঞ্জন পরে চাপলে সঠিক ক্রমে বসে)।
    // true = Unicode (ব্যঞ্জন আগে, কার-চিহ্ন পরে টাইপ করতে হয় — international logical
    // order অনুযায়ী; verified: ইউনিকোডে সবসময় ব্যঞ্জন+কার অর্ডারেই টাইপ করা হয়, কখনো
    // উল্টো না)। কী-লেআউট/ডিজাইন দুটো মোডেই হুবহু একই — শুধু processBengaliLogic()-এর
    // ি/ে/ৈ হ্যান্ডলিং (section ৮) আলাদা আচরণ করে মোড অনুযায়ী।
    private boolean isUnicodeMode = false;
    // Settings থেকে নিয়ন্ত্রিত — কোন মোড(গুলো) সাইকেল/শর্টকাটে পাওয়া যাবে (loadSettings() দ্রষ্টব্য)
    private boolean classicEnabled = true;
    private boolean unicodeEnabled = true;
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
    private boolean isAltPressed = false; // অন-স্ক্রিন Alt টগল বাটনের ভিজ্যুয়াল স্টেট (btnCtrl-এর হুবহু একই প্যাটার্ন)
    private Button btnAlt;

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

    // *** ডাবল-ট্যাপ Alt দিয়ে ভাষা টগল ফিচার সরিয়ে দেওয়া হয়েছে (ইউজারের অনুরোধে) —
    // এটা Ctrl+Alt+V/B কম্বোর সাথে মাঝেমধ্যে গুলিয়ে যাচ্ছিল। এখন Ctrl+Alt+V (Unicode)
    // আর Ctrl+Alt+B (Classic) — এই দুটোই একমাত্র/বাধ্যতামূলক হার্ডওয়্যার শর্টকাট।
    // HW_LANG_DOUBLE_TAP_MS কনস্ট্যান্টটা এখনও Ctrl-ডাবল-ট্যাপ-ক্লিপবোর্ড ফিচারে ব্যবহৃত হয়।
    private static final long HW_LANG_DOUBLE_TAP_MS = 400;

    // *** Win+V কেন "কিছুই হয় না" হতে পারে — মূল কারণ ***
    // AOSP-এর নিজস্ব ডকুমেন্টেশন অনুযায়ী, প্রতিটা হার্ডওয়্যার কী-ইভেন্ট প্রথমে
    // WindowManagerPolicy.interceptKeyBeforeDispatching()-এ যায় — "handles system
    // shortcuts and other functions" — অ্যাপ/IME পর্যন্ত পৌঁছানোরও আগে। Meta
    // (Windows) কী নির্দিষ্টভাবে Android-এ সিস্টেম-লেভেল শর্টকাটের জন্য সংরক্ষিত
    // (recent apps, split-screen, screenshot ইত্যাদি) — তাই অনেক ডিভাইসে/OEM-এ
    // Meta কী নিজে চাপলেই (V চাপার আগেই) সেটা সিস্টেম খেয়ে ফেলে, আমাদের কোড পর্যন্ত
    // metaKeyDown=true হওয়ার সুযোগই পায় না। ফলে আগের কোডে (যেখানে metaKeyDown
    // ট্র্যাকিং-ও লাগত) কম্বোটা কখনো "genuine" প্রমাণিত হতো না।
    // তাই Win+V ডিটেকশন এখন শুধু event.isMetaPressed()-এর ওপর সরাসরি ভরসা করে (V
    // কী-ইভেন্টে যে মেটা-স্টেট বিটটা থাকে, সেটা ইনপুট সিস্টেম কেন্দ্রীয়ভাবে ট্র্যাক
    // করে, নির্দিষ্ট কী-ইভেন্ট অ্যাপ পর্যন্ত পৌঁছেছে কিনা তার ওপর নির্ভর করে না)।
    //
    // তবু, যদি পুরো Meta+V কম্বোটাই কোনো ডিভাইসে সম্পূর্ণ ইন্টারসেপ্ট হয়ে যায় (V
    // ইভেন্টও না পৌঁছায়), সেক্ষেত্রে একটা গ্যারান্টিড বিকল্প হিসেবে — Ctrl কী পরপর
    // দুইবার (৪০০ms-এর মধ্যে) চাপলেও ক্লিপবোর্ড ওভারলে খুলবে/বন্ধ হবে। Ctrl সিস্টেম-
    // সংরক্ষিত না, তাই এটা সবসময় অ্যাপ পর্যন্ত পৌঁছানো উচিত। Alt চাপা থাকা অবস্থায়
    // (Ctrl+Alt+V কম্বোর অংশ হিসেবে Ctrl চাপা হলে) এই ডাবল-ট্যাপ সক্রিয় হয় না।
    private long lastHwCtrlTapTime = 0L;

    // ══════════════════════════════════════
    // Alt + নিউমেরিক-কীপ্যাড কোড (Windows-স্টাইল "Alt code") — যেমন Alt+0151 = — (em dash)
    // ══════════════════════════════════════
    // Windows-এর দুটো আলাদা কনভেনশন আছে, আর দুটোই সম্পূর্ণ ভিন্ন character table থেকে আসে:
    //  ১) Alt + 0ddd (leading zero, ৪ অঙ্ক) → Windows-1252/ANSI কোডপেজ — em dash, en dash,
    //     smart quotes, °, ©, ®, ™, ½, ¼, ×, ÷ ইত্যাদি প্র্যাকটিক্যাল চিহ্ন এখানে পাওয়া যায়।
    //  ২) Alt + ddd (leading zero ছাড়া) → পুরনো DOS/OEM কোডপেজ 437 — √ (Alt+251), π (227),
    //     Σ (228), ∞ (236), ≡ (240), ≤/≥ (243/242), ° (248) ইত্যাদি গণিত চিহ্ন এখানে।
    // দুটো টেবিলই সম্পূর্ণ স্বাধীন — একই সংখ্যা দুটো টেবিলে ভিন্ন চিহ্ন দিতে পারে।
    // শুধুমাত্র নিউমেরিক-কীপ্যাডের (KEYCODE_NUMPAD_0-9) ডিজিটই গণনা করা হয় — ওপরের
    // সংখ্যার সারি না, ঠিক Windows-এর মূল নিয়ম অনুযায়ী (এই কিবোর্ডে ডান পাশে আলাদা
    // নিউমেরিক কীপ্যাড আছে বলে নিশ্চিত হয়ে এই সিদ্ধান্ত)।
    // Ctrl চাপা অবস্থায় (Ctrl+Alt+V/B কম্বোর অংশ হিসেবে Alt চাপা হলে) এই বাফারিং সক্রিয়
    // হয় না, যাতে দুটো ফিচার একে অপরের সাথে না গুলিয়ে যায়। বাফারিং চলাকালীন
    // ডিজিট-ছাড়া অন্য কোনো কী চাপলে (যেমন হঠাৎ Ctrl বা কোনো অক্ষর) বাফারটা বাতিল হয়ে যায়।
    private boolean altCodeActive = false;
    private final StringBuilder altCodeBuffer = new StringBuilder();

    // পাসওয়ার্ড ফিল্ডে থাকলে suggestion/adaptive learning সম্পূর্ণ বন্ধ থাকবে (প্রাইভেসি)
    private boolean isPasswordField = false;
    // বর্তমান ফিল্ডের IME action (Done/Next/Search/Go/Send ইত্যাদি) — Enter কী-এর লেবেল/আচরণ এর ওপর নির্ভর করে
    private int currentImeAction = EditorInfo.IME_ACTION_NONE;

    // ══════════════════════════════════════
    // SETTINGS (SharedPreferences "kb_settings")
    // ══════════════════════════════════════
    private static final String SETTINGS_PREFS = "kb_settings";
    private SharedPreferences settingsPrefs;
    private String themeName = "dark";
    private int keyboardHeightPercent = 100; // ৭০–১৩০%
    private boolean keySoundEnabled = false;
    private boolean vibrationEnabled = true;
    private int vibrationStrengthPercent = 60; // ০–১০০%
    private AudioManager audioManager;

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
        settingsPrefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        loadSettings();
    }

    // Settings অ্যাক্টিভিটি থেকে সেভ করা মান পড়ে আনা হচ্ছে। onStartInputView-এও আবার
    // কল হয়, যাতে ইউজার মাঝে Settings-এ গিয়ে কিছু বদলালে সাথে সাথে প্রতিফলিত হয়।
    private void loadSettings() {
        if (settingsPrefs == null) return;
        themeName = settingsPrefs.getString("theme", "dark");
        keyboardHeightPercent = settingsPrefs.getInt("height_percent", 100);
        keySoundEnabled = settingsPrefs.getBoolean("key_sound", false);
        vibrationEnabled = settingsPrefs.getBoolean("vibration_enabled", true);
        vibrationStrengthPercent = settingsPrefs.getInt("vibration_strength", 60);
        // *** নতুন: সবশেষ ব্যবহৃত মোড (English/Classic/Unicode) মনে রাখা ***
        // ইউজারের অনুরোধ: Classic-এ হাত পাকা হয়ে গেছে, কিন্তু Unicode এখনো practice করছেন —
        // তাই যে মোডে রেখে বের হবেন, পরের বার কিবোর্ড খুললে/ফিল্ড পাল্টালে সেই মোডেই
        // যেন থাকে, প্রতিবার Classic-এ রিসেট না হয়ে যায়।
        isEnglishMode = settingsPrefs.getBoolean("mode_english", false);
        isUnicodeMode = settingsPrefs.getBoolean("mode_unicode", false);

        // *** নতুন: Settings থেকে Classic/Unicode আলাদা করে অন/অফ ***
        // ডিফল্ট দুটোই true (চালু) — ইউজার Settings-এ গিয়ে যেকোনোটা বন্ধ করতে পারবেন।
        // বন্ধ থাকা মোড ভাষা-টগল সাইকেল (toggleLanguageMode) আর Ctrl+Alt+V/B শর্টকাট
        // (applyModeShortcut) — দুই জায়গা থেকেই বাদ পড়বে।
        classicEnabled = settingsPrefs.getBoolean("classic_enabled", true);
        unicodeEnabled = settingsPrefs.getBoolean("unicode_enabled", true);

        // এইমাত্র লোড হওয়া সেটিংসে বর্তমান মোডটাই যদি বন্ধ থাকে (যেমন Classic মোডে
        // থাকা অবস্থায় ইউজার Settings-এ গিয়ে Classic বন্ধ করে দিলেন), তাহলে জোর করে
        // English-এ ফিরিয়ে আনা হচ্ছে — নাহলে একটা "বন্ধ" মোডেই আটকে থাকতে পারতেন
        if (!isEnglishMode) {
            if (isUnicodeMode && !unicodeEnabled) { isEnglishMode = true; isUnicodeMode = false; }
            else if (!isUnicodeMode && !classicEnabled) { isEnglishMode = true; }
        }
    }

    // toggleLanguageMode() আর applyModeShortcut()-এর শেষে কল হয় — মোড বদলানোর সাথে
    // সাথেই ডিস্কে সেভ হয়ে যায়, যাতে কিবোর্ড বন্ধ/রিস্টার্ট হলেও (অন্য অ্যাপে গেলে,
    // ফোন রিস্টার্ট হলেও) সবশেষ মোডটাই ফিরে আসে
    private void saveLanguageMode() {
        if (settingsPrefs == null) return;
        settingsPrefs.edit()
                .putBoolean("mode_english", isEnglishMode)
                .putBoolean("mode_unicode", isUnicodeMode)
                .apply();
    }

    // ══════════════════════════════════════
    // এক্সটার্নাল কিবোর্ড দিয়ে দৈনিক শব্দ-টাইপিং ট্র্যাকিং (Settings-এ দেখা যাবে)
    // ══════════════════════════════════════
    // শুধু হার্ডওয়্যার কিবোর্ডের Space চাপাকেই "শব্দ শেষ" হিসেবে গণনা করা হচ্ছে
    // (on-screen টাচ কিবোর্ডের handleOnScreenKey() থেকে এটা কল হয় না) — যেহেতু
    // ব্যবহারকারী নির্দিষ্টভাবে এক্সটার্নাল কিবোর্ডে প্র্যাকটিসের হিসাব চেয়েছেন।
    // এটা একটা সরল আনুমানিক গণনা (প্রতিটা Space = ১ শব্দ), নিখুঁত ভাষাতাত্ত্বিক
    // শব্দ-সীমানা নির্ণয় না — প্র্যাকটিসের progress বোঝার জন্য যথেষ্ট।
    // "typing_stats" নামের আলাদা SharedPreferences-এ তারিখ-ভিত্তিক কী (yyyy-MM-dd)
    // দিয়ে সেভ হয়, SettingsActivity একই ফাইল থেকে পড়ে লিস্ট বানায়।
    private void recordWordTypedViaHardware() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        SharedPreferences statsPrefs = getSharedPreferences("typing_stats", MODE_PRIVATE);
        int current = statsPrefs.getInt(today, 0);
        statsPrefs.edit().putInt(today, current + 1).apply();
    }

    // *** এক্সটার্নাল কিবোর্ড + "Show on-screen keyboard" সেটিংস অফ থাকার আসল সমাধান ***
    // শুধু requestShowSelf(SHOW_FORCED) কল করাটা যথেষ্ট নির্ভরযোগ্য না — Android নিজের
    // ডকুমেন্টেশন/ইঞ্জিনিয়ারিং নোটেই এটাকে "সম্পূর্ণ সমাধান না" বলে উল্লেখ করা আছে,
    // কারণ এটা কাজ করবে কিনা তা view/window ফোকাসের কিছু শর্তের ওপর নির্ভর করে, যেগুলো
    // ফোনের Settings > System > Languages & input > Physical keyboard > "Show on-screen
    // keyboard" অপশন বন্ধ থাকলে প্রথমেই পূরণ নাও হতে পারে।
    // Gboard, FUTO Keyboard-সহ প্রায় সব কাস্টম কিবোর্ড অ্যাপ যেটা আসলে করে তা হলো
    // onEvaluateInputViewShown() override করা — সিস্টেম ইনপুট ভিউ দেখাবে কিনা সিদ্ধান্ত
    // নেওয়ার সময় ঠিক এই মেথডটাই জিজ্ঞেস করে, তাই এখানে true রিটার্ন করাটাই সবচেয়ে
    // নির্ভরযোগ্য উপায় হার্ডওয়্যার কিবোর্ড/সেটিংস যাই থাকুক না কেন ভিউ দেখানোর জন্য।
    // clipboardHardwareNavActive শুধু তখনই true, যখন Win+V ওভারলে দেখানো দরকার —
    // বাকি সময় স্বাভাবিক (হাইড থাকা) আচরণ অক্ষত থাকে।
    @Override
    public boolean onEvaluateInputViewShown() {
        if (clipboardHardwareNavActive) return true;
        return super.onEvaluateInputViewShown();
    }

    @Override
    public View onCreateInputView() {
        keyboardView = getLayoutInflater().inflate(R.layout.keyboard_layout, null);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        setupKeyboard();
        updateKeyLabels();
        loadSettings();
        applyTheme();
        applyKeyboardHeightScale();
        return keyboardView;
    }

    // নতুন কোনো টেক্সট ফিল্ডে ফোকাস গেলে (ট্যাব চেপে বা ক্লিক করে) Android এই মেথডটা
    // কল করে। এখানে ইচ্ছাকৃতভাবে isEnglishMode রিসেট করা হচ্ছে না — যাতে এক ফিল্ড
    // থেকে অন্য ফিল্ডে গেলে ভাষা নিজে থেকে বাংলায় ফিরে না যায়। শুধু UI (কী লেবেল,
    // ভাষা বাটনের টেক্সট) রিফ্রেশ রাখার জন্য এইটুকু রাখা হলো।
    //
    // বাগ ফিক্স: pendingVowel/isG_Pressed আগে কখনো রিসেট হতো না যখন কিবোর্ড নতুন
    // ফিল্ডে/অ্যাপে চলে যেত (restarting == false)। ফলে আগের ফিল্ডে ি/ে/ৈ বা হসন্ত
    // চাপার পর তার জোড়া ব্যঞ্জনটা যদি আর না আসত (ফিল্ড বদলানো, অ্যাপ সুইচ, টাচ করে
    // কার্সর সরানো ইত্যাদি), সেই leftover pendingVowel পরের ফিল্ডে ঢুকে প্রথম
    // keystroke-টা গিলে ফেলত/ভুল জায়গায় বসিয়ে দিত — এই কারণেই "ি কার প্রথমবার
    // নিচ্ছে না, পরেরবার নিচ্ছে" সমস্যাটা হতো। নতুন ফিল্ডে ঢোকার সময় তাই leftover
    // state discard করে (commit না করেই) ক্লিন স্টেটে শুরু করা হচ্ছে।
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (!restarting) {
            resetStates(); // পুরনো ফিল্ডের leftover ি/ে/ৈ/হসন্ত discard করে ক্লিন স্টার্ট
        }
        loadSettings();
        applyTheme();
        applyKeyboardHeightScale();
        detectPasswordField(info);
        updateEnterKeyForField(info);
        updateKeyLabels();
    }

    // কিবোর্ড ভিউ বন্ধ হওয়ার সময়ও (অন্য অ্যাপে চলে গেলে, কিবোর্ড লুকিয়ে ফেললে ইত্যাদি)
    // pendingVowel/isG_Pressed ক্লিয়ার করে দেওয়া হচ্ছে — দুই দিক থেকেই (এখানে এবং
    // onStartInputView-এ) গার্ড রাখলে leftover state কোনোভাবেই পরের সেশনে/ফিল্ডে
    // ফাঁক গলে ঢুকতে পারবে না।
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        resetStates();
    }

    // পাসওয়ার্ড/পিন ফিল্ডে থাকলে suggestion strip আর adaptive learning সম্পূর্ণ বন্ধ —
    // নাহলে টাইপ করা পাসওয়ার্ড suggestion বা dictionary-তে জমা হয়ে প্রাইভেসি ঝুঁকি তৈরি করতে পারে
    private void detectPasswordField(EditorInfo info) {
        if (info == null) { isPasswordField = false; return; }
        int classType = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        boolean textPassword = classType == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        boolean numberPassword = classType == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        isPasswordField = textPassword || numberPassword;
        if (isPasswordField && keyboardView != null) {
            LinearLayout strip = keyboardView.findViewById(R.id.suggestion_strip);
            if (strip != null) strip.removeAllViews();
        }
    }

    // ফিল্ড অনুযায়ী Enter কী-এর লেবেল (Done/Next/Search/Go/Send) ঠিক করা হচ্ছে,
    // যেভাবে Gboard/SwiftKey করে। মাল্টিলাইন ফিল্ড বা IME_FLAG_NO_ENTER_ACTION থাকলে
    // সাধারণ নিউলাইন আইকনই দেখানো হবে।
    private void updateEnterKeyForField(EditorInfo info) {
        currentImeAction = EditorInfo.IME_ACTION_NONE;
        String label = "\u23CE"; // ⏎ ডিফল্ট নিউলাইন আইকন
        boolean isWord = false;

        if (info != null) {
            boolean noEnterAction = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
            int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
            if (!noEnterAction) {
                switch (action) {
                    case EditorInfo.IME_ACTION_DONE:
                        currentImeAction = action; label = "Done"; isWord = true; break;
                    case EditorInfo.IME_ACTION_GO:
                        currentImeAction = action; label = "Go"; isWord = true; break;
                    case EditorInfo.IME_ACTION_SEARCH:
                        currentImeAction = action; label = "Search"; isWord = true; break;
                    case EditorInfo.IME_ACTION_SEND:
                        currentImeAction = action; label = "Send"; isWord = true; break;
                    case EditorInfo.IME_ACTION_NEXT:
                        currentImeAction = action; label = "Next"; isWord = true; break;
                    case EditorInfo.IME_ACTION_PREVIOUS:
                        currentImeAction = action; label = "Prev"; isWord = true; break;
                    default:
                        currentImeAction = EditorInfo.IME_ACTION_NONE; label = "\u23CE";
                }
            }
        }

        if (keyboardView != null) {
            Button btnEnter = keyboardView.findViewById(R.id.btn_enter);
            if (btnEnter != null) {
                btnEnter.setText(label);
                btnEnter.setTextSize(isWord ? 13 : 22);
            }
        }
    }



    // ══════════════════════════════════════
    // THEME
    // ══════════════════════════════════════
    // suggestion chip তৈরির সময় (addSuggestionChip/addSuggestionDivider) এই রঙগুলোই ব্যবহার হয়,
    // যাতে থিম বদলালে suggestion strip-ও সাথে সাথে মানানসই দেখায়
    private int themeKeyText = 0xFFFFFFFF;
    private int themeAccentBg = 0xFF374151;

    // সংশ্লিষ্ট বাটনগুলো (Shift/Del/Symbol/Lang/Ctrl/Enter) কে সাধারণ কী থেকে একটু ভিন্ন
    // (accent) রঙে দেখানো হয় — যেভাবে সব কিবোর্ডেই স্পেশাল কী গুলো আলাদা করে বোঝানো হয়
    private java.util.Set<Integer> accentKeyIds() {
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        ids.add(R.id.btn_shift); ids.add(R.id.btn_del); ids.add(R.id.btn_symbol);
        ids.add(R.id.btn_lang); ids.add(R.id.btn_ctrl); ids.add(R.id.btn_enter);
        return ids;
    }

    private void applyTheme() {
        if (keyboardView == null) return;
        int panelBg, topBarBg, keyBg, keyText, accentBg, accentText;
        switch (themeName) {
            case "light":
                panelBg = 0xFFE5E7EB; topBarBg = 0xFFD1D5DB; keyBg = 0xFFFFFFFF;
                keyText = 0xFF111827; accentBg = 0xFFC7CED6; accentText = 0xFF111827;
                break;
            case "midnight_blue":
                panelBg = 0xFF0B1220; topBarBg = 0xFF0F1B2E; keyBg = 0xFF152238;
                keyText = 0xFFE2E8F0; accentBg = 0xFF1E3A5F; accentText = 0xFFFFFFFF;
                break;
            case "forest_green":
                panelBg = 0xFF0F1F16; topBarBg = 0xFF12291B; keyBg = 0xFF1B3A26;
                keyText = 0xFFE7F5EC; accentBg = 0xFF2F5D3F; accentText = 0xFFFFFFFF;
                break;
            default: // "dark" — বর্তমান ডিফল্ট ডিজাইন
                panelBg = 0xFF111827; topBarBg = 0xFF1A1A1A; keyBg = 0xFF1F2937;
                keyText = 0xFFFFFFFF; accentBg = 0xFF374151; accentText = 0xFFFFFFFF;
        }
        themeKeyText = keyText;
        themeAccentBg = accentBg;

        keyboardView.setBackgroundColor(panelBg);
        View topBar = keyboardView.findViewById(R.id.top_bar_container);
        if (topBar != null) topBar.setBackgroundColor(topBarBg);

        java.util.Set<Integer> accentIds = accentKeyIds();
        applyThemeToViewTree(keyboardView, keyBg, keyText, accentBg, accentText, accentIds);
    }

    // leaf-level "কী" ভিউগুলোতে (Button/আইকন TextView) রিকার্সিভলি থিম প্রয়োগ করা হচ্ছে।
    // container (LinearLayout/FrameLayout ইত্যাদি) আর ImageView (mic আইকন) স্বেচ্ছায় বাদ —
    // যাতে transparent/স্তরভিত্তিক ব্যাকগ্রাউন্ড নষ্ট না হয়
    private void applyThemeToViewTree(View v, int keyBg, int keyText, int accentBg, int accentText, java.util.Set<Integer> accentIds) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyThemeToViewTree(vg.getChildAt(i), keyBg, keyText, accentBg, accentText, accentIds);
            }
            return;
        }
        if (v instanceof ImageView) return; // মাইক আইকনের মতো transparent-bg ভিউ বাদ
        if (v.getId() == View.NO_ID || v.getBackground() == null) return;

        boolean accent = accentIds.contains(v.getId());
        int bg = accent ? accentBg : keyBg;
        int text = accent ? accentText : keyText;
        v.setBackgroundTintList(ColorStateList.valueOf(bg));
        if (v instanceof TextView) ((TextView) v).setTextColor(text);
    }

    // ══════════════════════════════════════
    // KEYBOARD HEIGHT
    // ══════════════════════════════════════
    // MyKeyStyle-এ বেস height 55dp ধরে, keyboardHeightPercent (৭০–১৩০%) অনুযায়ী স্কেল করা হয়।
    // সবসময় ফিক্সড বেস (৫৫dp) থেকে হিসাব করা হয় — বারবার apply করলেও height compound হয়ে
    // বেড়ে/কমে যাবে না
    private void applyKeyboardHeightScale() {
        if (keyboardView == null) return;
        float scale = keyboardHeightPercent / 100f;
        applyHeightToViewTree(keyboardView, scale);
    }

    private void applyHeightToViewTree(View v, float scale) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) applyHeightToViewTree(vg.getChildAt(i), scale);
        }
        if (v instanceof Button) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                int baseDp = 55; // মূল XML স্টাইলে (MyKeyStyle) ডিফাইন করা বেস height
                lp.height = Math.round(baseDp * scale * getResources().getDisplayMetrics().density);
                v.setLayoutParams(lp);
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
                    String text = item.getText().toString();
                    // আগে: জিনিসটা লিস্টে আগে থেকে থাকলে কিছুই করা হতো না, ফলে সেটা
                    // পুরনো (নিচের) জায়গাতেই আটকে থাকত। এখন: আগের অবস্থান থেকে সরিয়ে
                    // (remove) আবার একদম শুরুতে (index 0) বসানো হচ্ছে — ঠিক Windows-এর
                    // Win+V বা Gboard-এর ক্লিপবোর্ডের মতো, একই জিনিস আবার কপি করলে সেটাই
                    // সবার আগে/সাম্প্রতিক হিসেবে দেখাবে
                    clipboardHistory.remove(text); // ArrayList.remove(Object) — না থাকলে নিরাপদে কিছুই করে না
                    clipboardHistory.add(0, text);
                    if (clipboardHistory.size() > 10) {
                        clipboardHistory.remove(clipboardHistory.size() - 1);
                    }
                }
            }
        }
        showClipboardInUI();
    }

    private void showClipboardInUI() {
        // এক্সটার্নাল/হার্ডওয়্যার কিবোর্ড লাগানো থাকলে অনেক সময় আমাদের ইনপুট ভিউ
        // (keyboardView) কখনোই তৈরি হয় না (onCreateInputView কল হয় না) — কারণ
        // Android তখন ধরে নেয় সফট কিবোর্ড দেখানোর দরকার নেই। এই মেথডটা ক্লিপবোর্ড
        // বদলানোর PrimaryClipChangedListener থেকেও কল হয় (onCreate-এ রেজিস্টার করা,
        // যেটা আমাদের ভিউ তৈরি আছে কিনা তার সাথে সম্পূর্ণ স্বাধীন)। আগে এখানে
        // keyboardView-এর null-check না থাকায় এমন পরিস্থিতিতে NullPointerException
        // হয়ে পুরো কিবোর্ড সার্ভিসটাই ক্র্যাশ/রিস্টার্ট হয়ে যেত — বাইরে থেকে দেখতে
        // মনে হতো ভাষা "নিজে থেকে" বদলে যাচ্ছে বা আটকে যাচ্ছে।
        if (keyboardView == null) return;
        LinearLayout container = keyboardView.findViewById(R.id.clipboard_container);
        if (container == null) return;
        container.removeAllViews();
        clipboardChipButtons.clear(); // পুরনো তালিকা রিফ্রেশ করার সময় হার্ডওয়্যার-নেভিগেশনের রেফারেন্সও নতুন করে বানাতে হবে

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
            // *** ফিক্স: এক টাচেই পেস্ট না হওয়ার আসল কারণ ছিল এখানে ***
            // আগে এখানে setFocusable(true)/setFocusableInTouchMode(true) ছিল (হার্ডওয়্যার
            // DPAD নেভিগেশনের জন্য যোগ করা হয়েছিল)। কিন্তু IME-এর উইন্ডো সাধারণত
            // input-focusable না (যাতে টাচ করলে অ্যাপের EditText থেকে ফোকাস না কেড়ে নেয়)
            // — আর তার ভেতরের একটা চাইল্ড ভিউকে focusableInTouchMode(true) করলে প্রথম
            // ট্যাপটা শুধু ফোকাস নেওয়ার জন্য খরচ হয়ে যায়, ক্লিক ইভেন্ট আসে দ্বিতীয়
            // ট্যাপে। যেহেতু হার্ডওয়্যার DPAD নেভিগেশন এখন সম্পূর্ণ আলাদা ওভারলেতে
            // (hw_clipboard_overlay.xml/hwClipChipButtons) হয়, এই টাচ-স্ট্রিপের বাটনে
            // ফোকাসেবল রাখার আর দরকার নেই — বাদ দেওয়া হলো, তাই এক ট্যাপেই পেস্ট হবে।

            btn.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(text, 1);
                doHaptic();
                // পেস্ট করার পর ক্লিপবোর্ড স্ট্রিপ বন্ধ করে suggestion স্পেস দেখানো হচ্ছে
                closeClipboardPanel();
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
            clipboardChipButtons.add(btn);
        }
    }

    // ══════════════════════════════════════
    // হার্ডওয়্যার কিবোর্ড দিয়ে Win+V কমপ্যাক্ট ক্লিপবোর্ড ওভারলে
    // ══════════════════════════════════════

    // hw_clipboard_overlay.xml লেআউটটা একবারই inflate করা হয়, বারবার না —
    // এটাই সেই ভিউ যেটা Win+V চাপলে পুরো QWERTY কিবোর্ডের বদলে সাময়িকভাবে দেখানো হবে
    private void ensureHwClipboardOverlay() {
        if (hwClipboardOverlayView != null) return;
        hwClipboardOverlayView = getLayoutInflater().inflate(R.layout.hw_clipboard_overlay, null);
        hwClipboardListContainer = hwClipboardOverlayView.findViewById(R.id.hw_clip_list);
        hwClipboardScrollView = hwClipboardOverlayView.findViewById(R.id.hw_clip_scroll);
        View closeBtn = hwClipboardOverlayView.findViewById(R.id.hw_clip_close);
        if (closeBtn != null) closeBtn.setOnClickListener(v -> closeClipboardPanel());
    }

    // সবশেষ কপি করা তালিকা দিয়ে ওপর-থেকে-নিচে সাজানো কার্ড-লিস্টটা রিফ্রেশ করা হচ্ছে —
    // ঠিক PC-তে Windows+V চাপলে যেমন হয় সেভাবেই: প্রতিটা আইটেম আলাদা লাইনে, বড়
    // টেক্সট হলে maxLines(1) + ellipsize দিয়ে স্বয়ংক্রিয়ভাবে "..." কেটে দেখানো হয়
    // (নিজে থেকে substring করে ক্যারেক্টার-সংখ্যা হিসাব করার দরকার নেই)
    private void populateHwClipboardOverlay() {
        if (hwClipboardListContainer == null) return;
        hwClipboardListContainer.removeAllViews();
        hwClipChipButtons.clear();

        ArrayList<String> pinnedItems = getPinnedItems();
        ArrayList<String> allItems = new ArrayList<>();
        for (String p : pinnedItems) allItems.add("📌 " + p);
        for (String h : clipboardHistory) {
            if (!pinnedItems.contains(h)) allItems.add(h);
        }

        if (allItems.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("এখনো কিছু কপি করা হয়নি");
            empty.setTextColor(0xFF64748B);
            empty.setTextSize(12);
            empty.setPadding(16, 20, 16, 20);
            hwClipboardListContainer.addView(empty);
            return;
        }

        for (String rawText : allItems) {
            boolean isPinned = rawText.startsWith("📌 ");
            String text = isPinned ? rawText.substring(3) : rawText;

            Button row = new Button(this);
            row.setText((isPinned ? "📌 " : "") + text);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END); // বড় টেক্সট হলে শুরুর কয়েকটা অক্ষরের পর "..." — কন্টেইনারের প্রস্থ অনুযায়ী স্বয়ংক্রিয়ভাবে
            row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            row.setAllCaps(false);
            row.setTextSize(13);
            row.setTextColor(isPinned ? 0xFF38BDF8 : 0xFFE5E7EB);
            row.setBackgroundResource(R.drawable.card_bg);
            row.setPadding(24, 22, 24, 22);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, 8);
            row.setLayoutParams(params);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);

            row.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(text, 1);
                doHaptic();
                closeClipboardPanel();
            });

            row.setOnLongClickListener(v -> {
                if (isPinned) {
                    unpinItem(text);
                    Toast.makeText(this, "Pin সরানো হয়েছে", Toast.LENGTH_SHORT).show();
                } else {
                    pinItem(text);
                    Toast.makeText(this, "📌 Pin হয়েছে!", Toast.LENGTH_SHORT).show();
                }
                populateHwClipboardOverlay();
                return true;
            });

            hwClipboardListContainer.addView(row);
            hwClipChipButtons.add(row);
        }
    }

    // Windows+V (মেটা/উইন্ডোজ কী + V) চাপলে কল হয়। এক্সটার্নাল কিবোর্ড লাগানো অবস্থায়
    // স্বাভাবিকভাবে আমাদের ইনপুট ভিউ দেখানো হয় না (এমনকি ফোনের সেটিংসে "Show on-screen
    // keyboard" অপশন বন্ধ থাকলেও) — তাই তিনটা কাজ একসাথে করা হচ্ছে:
    // (১) clipboardHardwareNavActive = true — এটা আগে সেট করা *জরুরি*, কারণ
    //     onEvaluateInputViewShown() override-টা এই ফ্ল্যাগ দেখেই সিদ্ধান্ত নেয় (নিচে
    //     requestShowSelf কল করার সময় সিস্টেম এই মেথডটা আবার জিজ্ঞেস করতে পারে)
    // (২) setInputView() দিয়ে পুরো QWERTY কিবোর্ডের (keyboard_layout.xml) বদলে শুধু
    //     এই কমপ্যাক্ট ওভারলেটাই ভিউ হিসেবে বসানো — যাতে অযথা কী-বোর্ডের বাকি অংশ না দেখায়
    // (৩) requestShowSelf(SHOW_FORCED) — ইনপুট ভিউ জোর করে দেখানোর অনুরোধ; এটা একাই
    //     সবসময় যথেষ্ট না, তাই (১)-এর override-টাই মূল ভরসা
    private void openClipboardPanelViaHardware() {
        ensureHwClipboardOverlay();
        if (hwClipboardOverlayView == null) return;

        populateHwClipboardOverlay();
        clipboardHardwareNavActive = true; // onEvaluateInputViewShown() override সক্রিয় হওয়ার আগেই সেট করা লাগবে
        setInputView(hwClipboardOverlayView);
        requestShowSelf(InputMethodManager.SHOW_FORCED);

        hwClipFocusIndex = hwClipChipButtons.isEmpty() ? -1 : 0;
        highlightHwClipChip(hwClipFocusIndex);
        doHaptic();
    }

    // প্যানেল বন্ধ করা হচ্ছে — দুটো ভিন্ন পরিস্থিতির জন্য দুটো ভিন্ন পথ:
    //  ১) হার্ডওয়্যার Win+V দিয়ে খোলা কমপ্যাক্ট ওভারলে বন্ধ হলে — clipboardHardwareNavActive
    //     আগেই false করে দেওয়া হচ্ছে (যাতে onEvaluateInputViewShown() স্বাভাবিক আচরণে
    //     ফিরে যায়), তারপর setInputView() দিয়ে আগের ভিউ (keyboardView থাকলে সেটা,
    //     নাহলে null — যাতে পরের বার স্বাভাবিক শো-এর সময় onCreateInputView() নতুন করে
    //     ঠিকভাবে কল হয়) ফিরিয়ে এনে requestHideSelf() দিয়ে পুরোপুরি হাইড করা হচ্ছে —
    //     মানে এক্সটার্নাল কিবোর্ডে আপনি যেভাবে "হাইড করে রাখা" পছন্দ করেন, ঠিক সেই
    //     অবস্থাতেই ফিরে যাওয়া।
    //  ২) টাচ দিয়ে (কিবোর্ডের ক্লিপবোর্ড আইকনে ট্যাপ করে) খোলা আনুভূমিক স্ট্রিপ বন্ধ
    //     হলে — শুধু keyboardView-এর ভেতরের suggestion/clipboard strip টগল-ব্যাক করা হয়,
    //     পুরো ইনপুট ভিউ হাইড করার দরকার নেই।
    private void closeClipboardPanel() {
        boolean wasHardwareOverlay = clipboardHardwareNavActive;
        clipboardHardwareNavActive = false;
        hwClipFocusIndex = -1;

        if (wasHardwareOverlay) {
            if (keyboardView != null) {
                setInputView(keyboardView);
            } else {
                setInputView(null);
            }
            requestHideSelf(0);
        } else if (keyboardView != null) {
            View clipboardScroll = keyboardView.findViewById(R.id.clipboard_scroll);
            View suggestionStrip = keyboardView.findViewById(R.id.suggestion_strip);
            if (clipboardScroll != null) clipboardScroll.setVisibility(View.GONE);
            if (suggestionStrip != null) suggestionStrip.setVisibility(View.VISIBLE);
        }
    }

    // বর্তমানে ফোকাসড কার্ডটাকে দৃশ্যত হাইলাইট করা (থিমের accent রঙে) এবং বাকিগুলো
    // স্বাভাবিক card_bg রঙে ফিরিয়ে আনা হচ্ছে, সাথে ভিউ-ফোকাসও আর ভার্টিক্যাল
    // স্ক্রলে সেটা দৃশ্যমান জায়গায় আনা হচ্ছে
    private void highlightHwClipChip(int index) {
        for (int i = 0; i < hwClipChipButtons.size(); i++) {
            Button b = hwClipChipButtons.get(i);
            if (i == index) {
                b.setBackgroundTintList(ColorStateList.valueOf(themeAccentBg));
                b.requestFocus();
                if (hwClipboardScrollView != null) hwClipboardScrollView.requestChildFocus(b, b);
            } else {
                b.setBackgroundTintList(null);
            }
        }
    }

    // দিকনির্দেশনা (-1 = ওপরে/আগেরটা, +1 = নিচে/পরেরটা) অনুযায়ী ফোকাস সরানো হয়,
    // তালিকার শুরু/শেষে গিয়ে থেমে যায় (loop না করে) — Windows-এর Win+V প্যানেলের
    // মতোই স্বাভাবিক অনুভূতি
    private void moveHwClipFocus(int direction) {
        if (hwClipChipButtons.isEmpty()) return;
        int next = hwClipFocusIndex + direction;
        if (next < 0) next = 0;
        if (next >= hwClipChipButtons.size()) next = hwClipChipButtons.size() - 1;
        hwClipFocusIndex = next;
        highlightHwClipChip(hwClipFocusIndex);
        doHaptic();
    }

    // Enter চাপলে বর্তমানে ফোকাসড কার্ডটাই পেস্ট হবে — ঠিক মাউস দিয়ে ট্যাপ করলে যা হতো
    // তাই, performClick() ব্যবহার করায় paste + panel বন্ধ করার লজিক (onClickListener-এ)
    // পুনর্ব্যবহার হচ্ছে
    private void activateFocusedHwClipChip() {
        if (hwClipFocusIndex < 0 || hwClipFocusIndex >= hwClipChipButtons.size()) return;
        hwClipChipButtons.get(hwClipFocusIndex).performClick();
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
        if (isPasswordField) return; // পাসওয়ার্ড ফিল্ডে কখনোই কিছু শেখানো/সেভ করা হবে না
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
        if (isPasswordField) return; // পাসওয়ার্ড ফিল্ডে কোনো suggestion দেখানো হবে না

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

        // ঠিক ৩টা suggestion দেখাতে হবে — Gboard-স্টাইলে সমান তিন ঘরে (প্রতিটার width = 1/3 strip)
        final int MAX_SUGGESTIONS = 3;
        int shown = 0;
        for (String word : candidates) {
            if (shown >= MAX_SUGGESTIONS) break;
            if (shown > 0) addSuggestionDivider(strip);
            addSuggestionChip(strip, word, prefix);
            shown++;
        }
    }

    // দুটো suggestion ঘরের মাঝে একটা পাতলা ভার্টিকাল লাইন — যাতে ৩টা আলাদা "ঘর" এর মতো দেখায়
    private void addSuggestionDivider(LinearLayout strip) {
        View divider = new View(this);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(2, LinearLayout.LayoutParams.MATCH_PARENT);
        int vMargin = (int) (10 * getResources().getDisplayMetrics().density);
        dp.setMargins(0, vMargin, 0, vMargin);
        divider.setLayoutParams(dp);
        divider.setBackgroundColor((themeKeyText & 0x00FFFFFF) | 0x33000000);
        strip.addView(divider);
    }

    private void addSuggestionChip(LinearLayout strip, String word, String prefix) {
        TextView chip = new TextView(this);
        chip.setText(word);
        // আগে ছিল 13sp, ছোট মিল-থাকা তিনটা suggestion এখন বড় সাইজে (18sp) দেখাবে
        chip.setTextSize(18);
        chip.setTextColor(themeKeyText);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setMaxLines(1);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(12, 4, 12, 4);
        // প্রতিটা suggestion strip-এর ঠিক ১/৩ অংশ দখল করবে — সমান তিনটা "ঘর"
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
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
            toggleLanguageMode();
        });

        // সেটিংস গিয়ার আইকন — ট্যাপ করলে থিম/হাইট/সাউন্ড/ভাইব্রেশন/ডিকশনারি ম্যানেজ করার
        // স্ক্রিন খুলবে। IME service থেকে Activity চালু করতে হলে NEW_TASK flag লাগে।
        View btnSettingsGear = keyboardView.findViewById(R.id.btn_settings_gear);
        if (btnSettingsGear != null) {
            btnSettingsGear.setOnClickListener(v -> {
                doHaptic();
                Intent intent = new Intent(this, SettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            });
        }

        // ক্লিপবোর্ড টগল আইকন — ট্যাপ করলে ক্লিপবোর্ড স্ট্রিপ দেখাবে/লুকাবে।
        // কপি করা জিনিসপত্র সবসময় দেখা যাবে না, শুধু এই আইকনে ট্যাপ করলেই দেখা যাবে।
        keyboardView.findViewById(R.id.btn_clipboard_toggle).setOnClickListener(v -> {
            doHaptic();
            View clipboardScroll = keyboardView.findViewById(R.id.clipboard_scroll);
            View suggestionStrip = keyboardView.findViewById(R.id.suggestion_strip);
            if (clipboardScroll == null || suggestionStrip == null) return;

            boolean isOpen = clipboardScroll.getVisibility() == View.VISIBLE;
            if (isOpen) {
                closeClipboardPanel();
            } else {
                showClipboardInUI(); // এখনকার একই লজিক দিয়ে সবশেষ কপি করা জিনিস রিফ্রেশ করা
                suggestionStrip.setVisibility(View.GONE);
                clipboardScroll.setVisibility(View.VISIBLE);
                // এটা টাচ দিয়ে খোলা হয়েছে (মাউস/আঙুল আছে) — তাই DPAD হার্ডওয়্যার-নেভিগেশন মোড সক্রিয় করা হচ্ছে না
                clipboardHardwareNavActive = false;
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
                if (currentImeAction != EditorInfo.IME_ACTION_NONE) {
                    // ফিল্ড নির্দিষ্ট action চাইলে (Done/Next/Search/Go/Send) সেটাই ট্রিগার করা হবে,
                    // শুধু raw নিউলাইন পাঠানো হবে না — অ্যাপগুলো তখন ফর্ম সাবমিট/সার্চ ঠিকভাবে বুঝবে
                    ic.performEditorAction(currentImeAction);
                } else {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                }
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

        // Tab — টার্গেট অ্যাপের কাছে সরাসরি KEYCODE_TAB পাঠানো হচ্ছে (ফোকাস-নেক্সট/ট্যাব-ইনসার্ট,
        // অ্যাপ যেভাবে হ্যান্ডল করে সেভাবেই কাজ করবে, ঠিক হার্ডওয়্যার Tab-এর মতো)
        Button btnTab = keyboardView.findViewById(R.id.btn_tab);
        if (btnTab != null) {
            btnTab.setOnClickListener(v -> {
                doHaptic();
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0));
                ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0));
            });
        }

        // Alt — btnCtrl-এর মতোই টগল বাটন। আগে এখান থেকে সরাসরি onKeyDown(ALT_LEFT)
        // কল করা হতো (Alt-ডাবল-ট্যাপ ভাষা-টগল ফিচারের জন্য) — সেই ফিচার এখন সরিয়ে
        // দেওয়া হয়েছে, আর যেহেতু এই টগলে কোনো matching key-up ইভেন্ট আসে না, তাই সেই
        // কল রেখে দিলে altCodeActive (নতুন Alt-কোড ফিচার) স্টাক true থেকে যেত এবং
        // পরবর্তী হার্ডওয়্যার নিউমেরিক-কীপ্যাড ডিজিট ভুলভাবে বাফারে ধরে ফেলত। তাই এখন
        // শুধু isAltPressed (ভিজ্যুয়াল + on-screen Ctrl+Alt+V/B কম্বো/অ্যারো-মেটা-ফ্ল্যাগের
        // জন্য) টগল করা হচ্ছে, হার্ডওয়্যার altKeyDown/altCodeActive-এ হাত দেওয়া হচ্ছে না।
        btnAlt = keyboardView.findViewById(R.id.btn_alt);
        if (btnAlt != null) {
            btnAlt.setOnClickListener(v -> {
                isAltPressed = !isAltPressed;
                updateKeyLabels();
            });
        }

        // অ্যারো কী — টার্গেট অ্যাপের কাছে DPAD কী-ইভেন্ট পাঠানো হচ্ছে, সাথে বর্তমান
        // on-screen Shift/Ctrl/Alt টগল অবস্থাগুলো মেটা-ফ্ল্যাগ হিসেবে জুড়ে দেওয়া হচ্ছে —
        // তাই Shift+Arrow (সিলেকশন), Ctrl+Arrow (শব্দ-লাফ) ইত্যাদি শর্টকাট টার্গেট অ্যাপে
        // ঠিক হার্ডওয়্যার কিবোর্ডের মতোই কাজ করবে
        int[] dpadIds = {R.id.btn_dpad_left, R.id.btn_dpad_up, R.id.btn_dpad_down, R.id.btn_dpad_right};
        int[] dpadCodes = {KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT};
        for (int di = 0; di < dpadIds.length; di++) {
            Button btnDpad = keyboardView.findViewById(dpadIds[di]);
            final int dpadCode = dpadCodes[di];
            if (btnDpad != null) {
                btnDpad.setOnClickListener(v -> {
                    doHaptic();
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) return;
                    int meta = 0;
                    if (isShiftPressed) meta |= KeyEvent.META_SHIFT_ON;
                    if (isCtrlPressed) meta |= KeyEvent.META_CTRL_ON;
                    if (isAltPressed) meta |= KeyEvent.META_ALT_ON;
                    ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, dpadCode, 0, meta));
                    ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP, dpadCode, 0, meta));
                    boolean needsRefresh = false;
                    if (isCtrlPressed || isAltPressed) { isCtrlPressed = false; isAltPressed = false; needsRefresh = true; }
                    if (isShiftPressed && !isCapsLock) { isShiftPressed = false; needsRefresh = true; }
                    if (needsRefresh) updateKeyLabels();
                });
            }
        }
    }

    private void updateKeyLabels() {
        // *** মূল বাগ ফিক্স ***
        // এক্সটার্নাল/USB (OTG) কিবোর্ড লাগানো থাকলে Android অনেক সময় আমাদের ইনপুট ভিউ
        // (keyboardView) তৈরিই করে না (onCreateInputView কল হয় না) — যেহেতু সফট কিবোর্ড
        // দেখানোর দরকার নেই বলে সিস্টেম ধরে নেয়। কিন্তু ভাষা টগল হলে (toggleLanguageMode)
        // এই মেথডটা কল হয়, আর আগে এখানে keyboardView-এর কোনো null-check ছিল না —
        // ফলে keyboardView.findViewById(...) এ সরাসরি NullPointerException হয়ে
        // পুরো কিবোর্ড সার্ভিসটাই ক্র্যাশ করে যেত।
        //
        // এটাই "কিছুই না চাপলেও ভাষা এমনি এমনি বদলে যাওয়া / একবার বদলানোর পর থেকে
        // আর ঠিকভাবে কাজ না করা" সমস্যার আসল কারণ ছিল: প্রথমবার ভাষা বদলানোর
        // চেষ্টা করলেই (Ctrl+Alt+V ইত্যাদি) এখানে ক্র্যাশ হতো, সার্ভিসটা সিস্টেম দিয়ে
        // মেরে/রিস্টার্ট করানো হতো — আর তখন isEnglishMode-সহ সব স্টেট রিসেট হয়ে
        // ডিফল্টে (বাংলা) ফিরে যেত, অথবা আংশিকভাবে টগল হয়ে অসামঞ্জস্যপূর্ণ অবস্থায়
        // আটকে থাকত। এখন keyboardView null হলে UI আপডেট বাদ দিয়ে চুপচাপ রিটার্ন করা
        // হচ্ছে — isEnglishMode ঠিকভাবেই বদলে থাকে (toggleLanguageMode-এ, এই কলের
        // আগেই), শুধু লেবেল আপডেট স্থগিত থাকে যতক্ষণ না ভিউ সত্যিই তৈরি হয় — আর
        // onCreateInputView() নিজেই শেষে আরেকবার updateKeyLabels() কল করে, তাই ভিউ
        // তৈরি হওয়ার সাথে সাথে লেবেল ঠিকঠাক আপডেট হয়ে যাবে, কোনো ক্র্যাশ ছাড়াই।
        if (keyboardView == null) return;

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
        if (langBtn != null) {
            langBtn.setText(isEnglishMode ? "Eng" : (isUnicodeMode ? "Uni" : "বাং"));
        }

        // সেটিংস গিয়ার আইকনের পাশের লেবেল — বর্তমান মোড স্পষ্ট অক্ষরে ও আলাদা আলাদা
        // রঙে দেখানো হয়, যাতে এক নজরেই বোঝা যায় কোন মোডে আছেন — বোল্ড + প্রতিটা
        // মোডের জন্য ভিন্ন উজ্জ্বল রঙ (dark background-এও স্পষ্ট দেখা যাবে)
        TextView modeLabel = keyboardView.findViewById(R.id.mode_label);
        if (modeLabel != null) {
            if (isEnglishMode) {
                modeLabel.setText("English");
                modeLabel.setTextColor(0xFF60A5FA); // নীল
            } else if (isUnicodeMode) {
                modeLabel.setText("Unicode");
                modeLabel.setTextColor(0xFF34D399); // সবুজ
            } else {
                modeLabel.setText("Classic");
                modeLabel.setTextColor(0xFFFBBF24); // অ্যাম্বার/কমলা
            }
            modeLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

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

        if (btnAlt != null) {
            btnAlt.setAlpha(isAltPressed ? 0.5f : 1.0f);
        }

        // Tab/Alt/অ্যারো সারিটা সবসময় দেখানো হবে না — জায়গা বাঁচাতে এটা শুধু "123"
        // (সিম্বল মোড) চাপলেই দেখা যাবে, নাহলে GONE থাকবে (position/order অপরিবর্তিত,
        // শুধু visibility টগল হচ্ছে — ঠিক btnCtrl-এর মতোই প্যাটার্ন)
        View navRow = keyboardView.findViewById(R.id.nav_row);
        if (navRow != null) {
            navRow.setVisibility(isSymbolMode ? View.VISIBLE : View.GONE);
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
         "📏","🔬","🔭","🧮","🖩","➕","➖","✖️",
         "➗","🟰","🧪","🧫","⚗️","🧬",
         "🎒","🏫","🎓","📊","📈","📉","📋","📌",
         "📍","📎","🖇️","✂️","🗂️","🗄️","📁","📂",
         "📅","🗓️","⏰","🔖","🏷️","🧑‍🎓","👨‍🏫","👩‍🏫",
         "📇","📃","📜","📄","🗞️","📰","📑","🔢",
         "🔤","🔡","🔠","🌐","💯","🧑‍🔬","🧑‍💻"},
        {
         "🇧🇩","🇺🇸","🇮🇳","🇵🇰","🇳🇵","🇧🇹","🇱🇰","🇲🇲","🇲🇻",
         "🇦🇫","🇸🇦","🇦🇪","🇶🇦","🇰🇼","🇴🇲","🇧🇭","🇯🇴",
         "🇱🇧","🇮🇶","🇮🇷","🇸🇾","🇾🇪","🇮🇱","🇵🇸","🇹🇷",
         "🇪🇬","🇱🇾","🇹🇳","🇩🇿","🇲🇦","🇸🇩","🇰🇪","🇳🇬",
         "🇿🇦","🇬🇭","🇪🇹","🇹🇿","🇺🇬","🇷🇼","🇸🇳","🇨🇮",
         "🇨🇲","🇲🇾","🇸🇬","🇮🇩","🇹🇭","🇻🇳","🇵🇭","🇰🇭",
         "🇱🇦","🇧🇳","🇹🇱","🇯🇵","🇰🇷","🇰🇵","🇨🇳","🇭🇰",
         "🇲🇴","🇹🇼","🇲🇳","🇰🇿","🇺🇿","🇹🇲","🇰🇬","🇹🇯",
         "🇨🇦","🇲🇽","🇧🇷","🇦🇷","🇨🇱","🇨🇴","🇵🇪",
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
        // key sound চালু থাকলে ক্লিক সাউন্ড বাজবে
        if (keySoundEnabled && audioManager != null) {
            audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 1.0f);
        }
        if (!vibrationEnabled) return;
        if (vibrator == null || !vibrator.hasVibrator()) return;
        // vibrationStrengthPercent (0–100) থেকে amplitude (1–255) বের করা হচ্ছে
        int amplitude = Math.max(1, Math.round(vibrationStrengthPercent * 255f / 100f));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(18, amplitude));
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
            // Ctrl+Alt+V (Unicode মোড) / Ctrl+Alt+B (Classic মোড) — অন-স্ক্রিন Ctrl ও Alt
            // দুটো টগলই সক্রিয় থাকা অবস্থায় "v"/"b" চাপলে, হার্ডওয়্যার শর্টকাটের
            // হুবহু একই আচরণ পুনর্ব্যবহার করা হচ্ছে (applyModeShortcut)
            if (isAltPressed) {
                if (tag.equalsIgnoreCase("v")) {
                    applyModeShortcut(true);
                    Toast.makeText(this, isEnglishMode ? "English Mode" : "Unicode মোড", Toast.LENGTH_SHORT).show();
                    isCtrlPressed = false; isAltPressed = false; updateKeyLabels(); return;
                }
                if (tag.equalsIgnoreCase("b")) {
                    applyModeShortcut(false);
                    Toast.makeText(this, isEnglishMode ? "English Mode" : "Classic মোড", Toast.LENGTH_SHORT).show();
                    isCtrlPressed = false; isAltPressed = false; updateKeyLabels(); return;
                }
            }
            int keyCode = -1;
            switch (tag.toLowerCase()) {
                case "a": keyCode = KeyEvent.KEYCODE_A; break;
                case "c": keyCode = KeyEvent.KEYCODE_C; break;
                case "v": keyCode = KeyEvent.KEYCODE_V; break;
                case "x": keyCode = KeyEvent.KEYCODE_X; break;
                case "z": keyCode = KeyEvent.KEYCODE_Z; break;
            }
            if (keyCode != -1) {
                int metaFlags = KeyEvent.META_CTRL_ON | (isAltPressed ? KeyEvent.META_ALT_ON : 0);
                ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaFlags));
                ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaFlags));
                isCtrlPressed = false; isAltPressed = false; updateKeyLabels(); return;
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

        // ── 8. ি (U+09BF) / এ-কার (U+09C7) / ৈ-কার (U+09C8)
        // Classic: বিজয় নিয়ম — এই তিনটে কার আগে press হয়, ব্যঞ্জন পরে; তাই pendingVowel
        // এ রেখে দাও — ব্যঞ্জন আসলে section 9 এ flush হবে।
        // Unicode: আন্তর্জাতিক স্ট্যান্ডার্ড অনুযায়ী ব্যঞ্জন সবসময় আগে টাইপ হয়, কার পরে —
        // তাই বাফার করার দরকার নেই, সরাসরি এখনই commit করা হচ্ছে (ব্যঞ্জন ততক্ষণে আগেই
        // কার্সরের আগে বসে গেছে)। ো/ৌ কম্বিনেশন (ওপরের section ১/২-এ prevChar চেক) এতে
        // প্রভাবিত হয় না, কারণ সেটা টেক্সট ফিল্ডের প্রকৃত আগের অক্ষর দেখেই কাজ করে।
        if (result.equals("\u09BF") || result.equals("\u09C7") || result.equals("\u09C8")) {
            if (isUnicodeMode) {
                if (!pendingVowel.isEmpty()) { ic.commitText(pendingVowel, 1); pendingVowel = ""; }
                ic.commitText(result, 1);
                return;
            }
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
            if (event.getRepeatCount() == 0) {
                long now = System.currentTimeMillis();
                // Alt চাপা না থাকলেই শুধু ডাবল-ট্যাপ ট্রিগার সক্রিয় — নাহলে Ctrl+Alt+V
                // কম্বোর প্রথম Ctrl-চাপাটাও ভুলবশত ক্লিপবোর্ড প্যানেল খুলে ফেলতে পারত।
                // এটা Win+V-এর গ্যারান্টিড বিকল্প — Ctrl সিস্টেম-সংরক্ষিত না, তাই Meta
                // কী ইন্টারসেপ্ট হয়ে গেলেও এটা কাজ করবে (ওপরের কমেন্ট দ্রষ্টব্য)
                // *** ফিক্স: "কখনো হয়, কখনো হয় না" সমস্যার আসল কারণ ***
                // আগে এখানে `!altKeyDown` (একটা কখনো-না-ফুরানো বুলিয়ান) চেক করা হতো।
                // এই এক্সটার্নাল কিবোর্ডে যেহেতু Alt-এর key-up ইভেন্ট মাঝেমধ্যে মিস হয়
                // (আগেও এই একই কারণে অন্য জায়গায় সমস্যা হয়েছিল), altKeyDown একবার stuck
                // হয়ে গেলে Ctrl ডাবল-ট্যাপ পুরোপুরি নিষ্ক্রিয় হয়ে যেত যতক্ষণ না Alt আবার
                // চাপা/ছাড়া হয় — টাইপ করতে করতে এলোমেলোভাবে কখনো কাজ করত, কখনো করত না।
                // এখন altKeyDown-এর বদলে altDownAtMs-ও চেক করা হচ্ছে — Alt সত্যিই এইমাত্র
                // (ALT_COMBO_WINDOW_MS-এর মধ্যে) চাপা হলে তবেই সাপ্রেস করা হবে, পুরনো/স্টাক
                // ফ্ল্যাগ আর ব্লক করতে পারবে না।
                boolean altRecentlyHeld = altKeyDown && (now - altDownAtMs) <= ALT_COMBO_WINDOW_MS;
                if (!altRecentlyHeld) {
                    boolean doubleTap = (now - lastHwCtrlTapTime) < HW_LANG_DOUBLE_TAP_MS;
                    if (doubleTap) {
                        if (clipboardHardwareNavActive) {
                            closeClipboardPanel();
                        } else {
                            openClipboardPanelViaHardware();
                        }
                        lastHwCtrlTapTime = 0; // তিন/চারবার পরপর ট্যাপ করলে যেন বারবার টগল না হয়
                        ctrlKeyDown = true; ctrlDownAtMs = now;
                        return true;
                    }
                    lastHwCtrlTapTime = now;
                }
                ctrlKeyDown = true; ctrlDownAtMs = now;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            // *** ডাবল-ট্যাপ Alt দিয়ে ভাষা টগল ফিচারটা সরিয়ে দেওয়া হলো (ইউজারের অনুরোধে) ***
            // এটা Ctrl+Alt+V/B কম্বোর সাথে মাঝেমধ্যে গুলিয়ে যাচ্ছিল/সমস্যা করছিল। এখন
            // Ctrl+Alt+V (Unicode) আর Ctrl+Alt+B (Classic) — এই দুটোই একমাত্র/বাধ্যতামূলক
            // হার্ডওয়্যার শর্টকাট। নিচের altKeyDown/altDownAtMs ট্র্যাকিং অক্ষত রাখা হলো,
            // কারণ সেটা এখনও Ctrl+Alt+V/B কম্বো ডিটেকশনের জন্য দরকার।
            if (event.getRepeatCount() == 0) {
                altKeyDown = true;
                altDownAtMs = System.currentTimeMillis();
                // Ctrl চাপা না থাকলেই Alt-কোড বাফারিং শুরু — Ctrl চাপা থাকলে এটা
                // নিশ্চয়ই Ctrl+Alt+V/B কম্বোর অংশ, Alt-কোড না
                altCodeActive = !ctrlKeyDown;
                altCodeBuffer.setLength(0);
            }
            return super.onKeyDown(keyCode, event);
        }
        // Alt চাপা থাকা অবস্থায় নিউমেরিক-কীপ্যাডের ডিজিট চাপলে — Alt-কোড বাফারে যোগ
        // করা হচ্ছে, স্বাভাবিক ডিজিট-টাইপিং হিসেবে কমিট করা হচ্ছে না (return true দিয়ে
        // consume করা হচ্ছে)। Alt সক্রিয় না থাকলে (altCodeActive==false) এই ব্লক
        // স্কিপ হয়ে যায়, তাই স্বাভাবিক নিউমেরিক-কীপ্যাড টাইপিং অক্ষত থাকে।
        if (altCodeActive && keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            if (altCodeBuffer.length() < 4) { // Windows-এও সর্বোচ্চ ৪ অঙ্ক (0ddd) ব্যবহৃত হয়
                altCodeBuffer.append((char) ('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)));
            }
            return true;
        }
        // Alt-কোড বাফারিং চলাকালীন ডিজিট ছাড়া অন্য কোনো কী চাপলে (Ctrl+Alt+V/B কম্বোর
        // জন্য Ctrl, বা হঠাৎ অন্য অক্ষর) — বাফারটা বাতিল করা হচ্ছে, যাতে অসম্পূর্ণ/ভুল
        // কোড পরে Alt ছাড়ার সময় ভুল চিহ্ন কমিট না করে ফেলে
        if (altCodeActive && keyCode != KeyEvent.KEYCODE_ALT_LEFT && keyCode != KeyEvent.KEYCODE_ALT_RIGHT) {
            altCodeActive = false;
            altCodeBuffer.setLength(0);
        }
        // Windows/মেটা কী নিজেই চাপা হলে — Ctrl/Alt-এর মতোই শুধু আসল down-এ ট্র্যাক করা হচ্ছে,
        // যাতে stuck মেটা-স্টেটের কারণে সাধারণ "v" টাইপে ভুলবশত ক্লিপবোর্ড প্যানেল খুলে না যায়
        if (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (event.getRepeatCount() == 0) { metaKeyDown = true; metaDownAtMs = System.currentTimeMillis(); }
            return super.onKeyDown(keyCode, event);
        }
        // সিস্টেম এখন সঠিকভাবে Ctrl/Alt/Meta রিলিজড রিপোর্ট করছে — leftover ট্র্যাকিং সাথে সাথে ক্লিয়ার করো
        if (!event.isCtrlPressed()) ctrlKeyDown = false;
        if (!event.isAltPressed()) altKeyDown = false;
        if (!event.isMetaPressed()) metaKeyDown = false;

        // ১.৫ ক্লিপবোর্ড ওভারলে খোলা থাকলে (Win+V দিয়ে খোলা হয়েছে) — মাউস নেই ধরে নিয়ে
        // শুধু ওপরে/নিচে অ্যারো দিয়ে (PC-তে Win+V প্যানেলে যেমন হয় ঠিক তেমনই — লিস্টটা
        // ভার্টিক্যাল) আইটেমের মধ্যে ফোকাস সরানো আর Enter দিয়ে পেস্ট/সিলেক্ট করা হয়,
        // Escape/Back দিয়ে বন্ধ করা যায়। অন্য যেকোনো কী চাপলে (যেমন সরাসরি টাইপ শুরু
        // করলে) ওভারলেটা বন্ধ হয়ে যাবে আর সেই কী স্বাভাবিকভাবেই প্রসেস হবে (নিচে) —
        // আলাদা করে Esc চাপার দরকার নেই। এই ব্লকটা সাধারণ DPAD/Enter হ্যান্ডলিং-এর
        // (নিচে, টেক্সট-ফিল্ড কার্সর মুভমেন্টের জন্য) আগে বসানো।
        if (clipboardHardwareNavActive) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                moveHwClipFocus(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                moveHwClipFocus(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getRepeatCount() == 0) activateFocusedHwClipChip();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
                closeClipboardPanel();
                return true;
            }
            closeClipboardPanel(); // অন্য কী — ওভারলে বন্ধ করে নিচের স্বাভাবিক প্রসেসিং চালিয়ে যাওয়া হচ্ছে
        }

        // ২. Ctrl + Alt + V → Unicode মোড ⇄ English (নিচে Ctrl+Alt+B → Classic মোড ⇄ English)
        // Ctrl+Alt+V-এর জন্য এখনও নিজস্ব ট্র্যাকিং (genuineCombo) লাগে — নাহলে stuck
        // flag-এর কারণে সাধারণ "v" টাইপেও মোড পাল্টে যেতে পারে। কিন্তু Win+V-এর জন্য
        // (genuineMetaCombo) সরাসরি event.isMetaPressed() ব্যবহার করা হচ্ছে, কারণ Meta
        // কী-এর keyDown ইভেন্টটা নিজেই সিস্টেম-লেভেলে ইন্টারসেপ্ট হয়ে যেতে পারে (ওপরে
        // lastHwCtrlTapTime-এর কমেন্টে বিস্তারিত) — তাই আমাদের নিজস্ব metaKeyDown
        // ট্র্যাকিং-এর ওপর ভরসা করলে কম্বোটা কখনো "genuine" প্রমাণিতই হতো না।
        if (keyCode == KeyEvent.KEYCODE_V) {
            long now = System.currentTimeMillis();
            boolean genuineCombo = event.isCtrlPressed() && event.isAltPressed()
                    && ctrlKeyDown && (now - ctrlDownAtMs) <= ALT_COMBO_WINDOW_MS
                    && altKeyDown && (now - altDownAtMs) <= ALT_COMBO_WINDOW_MS;
            boolean genuineMetaCombo = event.isMetaPressed(); // মেটা কী-এর নিজস্ব keyDown সিস্টেম-লেভেলে ইন্টারসেপ্ট হয়ে যেতে পারে (ওপরের কমেন্ট দ্রষ্টব্য), তাই এখানে সরাসরি লাইভ মেটা-স্টেট বিটটাই ব্যবহার করা হচ্ছে
            if (genuineCombo) {
                if (event.getRepeatCount() == 0) {
                    applyModeShortcut(true); // → Unicode (আগে থেকেই Unicode-এ থাকলে English-এ ফিরবে)
                    ctrlKeyDown = false; altKeyDown = false; // কম্বো একবার ব্যবহার হয়ে গেলে সাথে সাথে ক্লিয়ার করো
                    Toast.makeText(this, isEnglishMode ? "English Mode" : "Unicode মোড", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (event.isCtrlPressed() && event.isAltPressed()) {
                // মেটা-ফ্ল্যাগ true থাকলেও আমাদের ট্র্যাকিং অনুযায়ী এটা ইচ্ছাকৃত কম্বো নয়
                // (leftover/stuck) — তাই মোড না বদলে "v" স্বাভাবিকভাবেই টাইপ হবে
                ctrlKeyDown = false; altKeyDown = false;
            } else if (genuineMetaCombo) {
                // Windows কী + V — ক্লিপবোর্ড হিস্টোরি প্যানেল টগল করা হচ্ছে
                if (event.getRepeatCount() == 0) {
                    if (clipboardHardwareNavActive) {
                        closeClipboardPanel();
                    } else {
                        openClipboardPanelViaHardware();
                    }
                    metaKeyDown = false; // কম্বো একবার ব্যবহার হয়ে গেলে সাথে সাথে ক্লিয়ার করো
                }
                return true;
            } else if (event.isMetaPressed()) {
                // leftover/stuck মেটা ফ্ল্যাগ — সাধারণ "v" স্বাভাবিকভাবেই টাইপ হবে
                metaKeyDown = false;
            }
        }

        // ২.৫ Ctrl + Alt + B → Classic মোড ⇄ English (Ctrl+Alt+V-এর সাথে হুবহু একই প্যাটার্ন,
        // শুধু target মোড Classic — genuineCombo-স্টাইল ট্র্যাকিং এখানেও লাগে)
        if (keyCode == KeyEvent.KEYCODE_B) {
            long now = System.currentTimeMillis();
            boolean genuineComboB = event.isCtrlPressed() && event.isAltPressed()
                    && ctrlKeyDown && (now - ctrlDownAtMs) <= ALT_COMBO_WINDOW_MS
                    && altKeyDown && (now - altDownAtMs) <= ALT_COMBO_WINDOW_MS;
            if (genuineComboB) {
                if (event.getRepeatCount() == 0) {
                    applyModeShortcut(false); // → Classic (আগে থেকেই Classic-এ থাকলে English-এ ফিরবে)
                    ctrlKeyDown = false; altKeyDown = false;
                    Toast.makeText(this, isEnglishMode ? "English Mode" : "Classic মোড", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (event.isCtrlPressed() && event.isAltPressed()) {
                // leftover/stuck flag — মোড না বদলে "b" স্বাভাবিকভাবেই টাইপ হবে
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
            recordWordTypedViaHardware();
            return true;
        }

        // সাধারণ Space Key প্রসেসিং
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            pendingVowel = "";
            ic.commitText(" ", 1);
            isG_Pressed = false;
            recordWordTypedViaHardware();
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
            resolveAltCodeOnRelease();
        } else if (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            metaKeyDown = false;
        }
        return super.onKeyUp(keyCode, event);
    }

    // Alt ছাড়ার মুহূর্তে বাফারে জমা ডিজিট থেকে চিহ্নটা রিজলভ করে কমিট করা হচ্ছে।
    // leading zero ('0' দিয়ে শুরু) থাকলে Windows-1252/ANSI টেবিল, নাহলে DOS/OEM
    // কোডপেজ 437 টেবিল থেকে লুকআপ করা হয় — ঠিক Windows-এর নিয়ম অনুযায়ী।
    private void resolveAltCodeOnRelease() {
        if (!altCodeActive) return;
        altCodeActive = false;
        String buffer = altCodeBuffer.toString();
        altCodeBuffer.setLength(0);
        if (buffer.isEmpty()) return;

        Character resolved = null;
        try {
            if (buffer.startsWith("0") && buffer.length() > 1) {
                int code = Integer.parseInt(buffer.substring(1));
                resolved = windows1252Char(code);
            } else {
                int code = Integer.parseInt(buffer);
                resolved = cp437Char(code);
            }
        } catch (NumberFormatException ignored) {
            // অসম্পূর্ণ/অবৈধ সংখ্যা — কিছুই কমিট হবে না
        }

        if (resolved != null) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                // Classic মোডে কোনো কার-চিহ্ন বাফারে আটকে থাকলে (যেমন ে চাপা হয়েছে, ব্যঞ্জনের
                // অপেক্ষায়) সেটা চুপচাপ হারিয়ে না গিয়ে আগে commit হয়ে যাক, তারপর Alt-কোড চিহ্নটা
                if (!pendingVowel.isEmpty()) {
                    ic.commitText(pendingVowel, 1);
                    pendingVowel = "";
                }
                isG_Pressed = false;
                ic.commitText(String.valueOf(resolved.charValue()), 1);
            }
        }
    }

    // Windows-1252 (ANSI) কোডপেজ — Alt+0128 থেকে Alt+0255। 160-255 রেঞ্জ ISO-8859-1
    // (Latin-1)-এর সাথে হুবহু মেলে; 128-159 রেঞ্জ Windows-নির্দিষ্ট এক্সটেনশন
    // (em dash, smart quotes, €, ™ ইত্যাদি) — Microsoft/Adobe-এর অফিসিয়াল রেফারেন্স
    // টেবিল যাচাই করে বসানো হয়েছে।
    private Character windows1252Char(int code) {
        switch (code) {
            case 128: return '\u20AC'; // €
            case 130: return '\u201A'; // ‚
            case 131: return '\u0192'; // ƒ
            case 132: return '\u201E'; // „
            case 133: return '\u2026'; // …
            case 134: return '\u2020'; // †
            case 135: return '\u2021'; // ‡
            case 136: return '\u02C6'; // ˆ
            case 137: return '\u2030'; // ‰
            case 138: return '\u0160'; // Š
            case 139: return '\u2039'; // ‹
            case 140: return '\u0152'; // Œ
            case 142: return '\u017D'; // Ž
            case 145: return '\u2018'; // '
            case 146: return '\u2019'; // '
            case 147: return '\u201C'; // "
            case 148: return '\u201D'; // "
            case 149: return '\u2022'; // •
            case 150: return '\u2013'; // – en dash
            case 151: return '\u2014'; // — em dash
            case 152: return '\u02DC'; // ˜
            case 153: return '\u2122'; // ™
            case 154: return '\u0161'; // š
            case 155: return '\u203A'; // ›
            case 156: return '\u0153'; // œ
            case 158: return '\u017E'; // ž
            case 159: return '\u0178'; // Ÿ
            case 160: return '\u00A0'; // nbsp
            case 161: return '\u00A1'; // ¡
            case 162: return '\u00A2'; // ¢
            case 163: return '\u00A3'; // £
            case 164: return '\u00A4'; // ¤
            case 165: return '\u00A5'; // ¥
            case 166: return '\u00A6'; // ¦
            case 167: return '\u00A7'; // §
            case 168: return '\u00A8'; // ¨
            case 169: return '\u00A9'; // ©
            case 170: return '\u00AA'; // ª
            case 171: return '\u00AB'; // «
            case 172: return '\u00AC'; // ¬
            case 173: return '\u00AD'; // soft hyphen
            case 174: return '\u00AE'; // ®
            case 175: return '\u00AF'; // ¯
            case 176: return '\u00B0'; // °
            case 177: return '\u00B1'; // ±
            case 178: return '\u00B2'; // ²
            case 179: return '\u00B3'; // ³
            case 180: return '\u00B4'; // ´
            case 181: return '\u00B5'; // µ
            case 182: return '\u00B6'; // ¶
            case 183: return '\u00B7'; // ·
            case 184: return '\u00B8'; // ¸
            case 185: return '\u00B9'; // ¹
            case 186: return '\u00BA'; // º
            case 187: return '\u00BB'; // »
            case 188: return '\u00BC'; // ¼
            case 189: return '\u00BD'; // ½
            case 190: return '\u00BE'; // ¾
            case 191: return '\u00BF'; // ¿
            case 192: return '\u00C0'; case 193: return '\u00C1';
            case 194: return '\u00C2'; case 195: return '\u00C3';
            case 196: return '\u00C4'; case 197: return '\u00C5';
            case 198: return '\u00C6'; case 199: return '\u00C7';
            case 200: return '\u00C8'; case 201: return '\u00C9';
            case 202: return '\u00CA'; case 203: return '\u00CB';
            case 204: return '\u00CC'; case 205: return '\u00CD';
            case 206: return '\u00CE'; case 207: return '\u00CF';
            case 208: return '\u00D0'; case 209: return '\u00D1';
            case 210: return '\u00D2'; case 211: return '\u00D3';
            case 212: return '\u00D4'; case 213: return '\u00D5';
            case 214: return '\u00D6'; case 215: return '\u00D7'; // ×
            case 216: return '\u00D8'; case 217: return '\u00D9';
            case 218: return '\u00DA'; case 219: return '\u00DB';
            case 220: return '\u00DC'; case 221: return '\u00DD';
            case 222: return '\u00DE'; case 223: return '\u00DF';
            case 224: return '\u00E0'; case 225: return '\u00E1';
            case 226: return '\u00E2'; case 227: return '\u00E3';
            case 228: return '\u00E4'; case 229: return '\u00E5';
            case 230: return '\u00E6'; case 231: return '\u00E7';
            case 232: return '\u00E8'; case 233: return '\u00E9';
            case 234: return '\u00EA'; case 235: return '\u00EB';
            case 236: return '\u00EC'; case 237: return '\u00ED';
            case 238: return '\u00EE'; case 239: return '\u00EF';
            case 240: return '\u00F0'; case 241: return '\u00F1';
            case 242: return '\u00F2'; case 243: return '\u00F3';
            case 244: return '\u00F4'; case 245: return '\u00F5';
            case 246: return '\u00F6'; case 247: return '\u00F7'; // ÷
            case 248: return '\u00F8'; case 249: return '\u00F9';
            case 250: return '\u00FA'; case 251: return '\u00FB';
            case 252: return '\u00FC'; case 253: return '\u00FD';
            case 254: return '\u00FE'; case 255: return '\u00FF';
            default: return null; // 129/141/143/144/157 — Windows-1252-এ অনির্ধারিত (undefined)
        }
    }

    // DOS/OEM কোডপেজ 437 — Alt+ddd (leading zero ছাড়া)। এখানে শুধু যাচাই-করা,
    // প্র্যাকটিক্যালি গুরুত্বপূর্ণ সাবসেট রাখা হয়েছে: গণিত/বিজ্ঞান চিহ্ন (√, π, ≤, ≥,
    // ≈, ≡, ∞, Σ, ±, ÷, °, ² ইত্যাদি — সোর্স যাচাই করা) এবং শুরুর কিছু প্রতীক/অ্যারো
    // (source-confirmed)। বক্স-ড্রইং ক্যারেক্টার আর উচ্চারণ-চিহ্নযুক্ত ল্যাটিন অক্ষর
    // (128-226 রেঞ্জের বেশিরভাগ) ইচ্ছাকৃতভাবে বাদ দেওয়া হলো — এই কিবোর্ডে ব্যবহারিক
    // মূল্য কম, আর সেগুলোর নিখুঁত ম্যাপিং নিশ্চিতভাবে যাচাই করা যায়নি।
    private Character cp437Char(int code) {
        switch (code) {
            case 1: return '\u263A';  // ☺
            case 2: return '\u263B';  // ☻
            case 3: return '\u2665';  // ♥
            case 4: return '\u2666';  // ♦
            case 5: return '\u2663';  // ♣
            case 6: return '\u2660';  // ♠
            case 7: return '\u2022';  // •
            case 13: return '\u266A'; // ♪
            case 14: return '\u266B'; // ♫
            case 15: return '\u263C'; // ☼
            case 16: return '\u25BA'; // ►
            case 17: return '\u25C4'; // ◄
            case 18: return '\u2195'; // ↕
            case 20: return '\u00B6'; // ¶
            case 21: return '\u00A7'; // §
            case 23: return '\u21A8'; // ↨
            case 24: return '\u2191'; // ↑
            case 25: return '\u2193'; // ↓
            case 26: return '\u2192'; // →
            case 27: return '\u2190'; // ←
            case 29: return '\u2194'; // ↔
            case 30: return '\u25B2'; // ▲
            case 31: return '\u25BC'; // ▼
            case 227: return '\u03C0'; // π
            case 228: return '\u03A3'; // Σ (উচ্চ কনফিডেন্স রেফারেন্স অনুযায়ী; কিছু চার্টে গ্রিক sigma-এর ছোট রূপও দেখা যায়)
            case 230: return '\u00B5'; // µ
            case 236: return '\u221E'; // ∞
            case 239: return '\u2229'; // ∩
            case 240: return '\u2261'; // ≡
            case 241: return '\u00B1'; // ±
            case 242: return '\u2265'; // ≥
            case 243: return '\u2264'; // ≤
            case 246: return '\u00F7'; // ÷
            case 247: return '\u2248'; // ≈
            case 248: return '\u00B0'; // °
            case 249: return '\u2219'; // ∙
            case 250: return '\u00B7'; // ·
            case 251: return '\u221A'; // √
            case 252: return '\u207F'; // ⁿ
            case 253: return '\u00B2'; // ²
            case 254: return '\u25A0'; // ■
            default: return null;
        }
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

    // ভাষা টগল করার একমাত্র কেন্দ্রীয় জায়গা — টাচ (btn_lang), Alt-ডাবল-ট্যাপ তিনটাই
    // এই একই মেথড কল করে। এখন তিনটা মোডের মধ্যে সাইকেল করে:
    // English → Classic → Unicode → English → ...
    private void toggleLanguageMode() {
        if (isEnglishMode) {
            // English থেকে পরের চালু মোডে — আগে Classic (যদি চালু থাকে), নাহলে Unicode
            // (যদি চালু থাকে), দুটোই বন্ধ থাকলে English-ই থেকে যাবে
            if (classicEnabled) { isEnglishMode = false; isUnicodeMode = false; }
            else if (unicodeEnabled) { isEnglishMode = false; isUnicodeMode = true; }
        } else if (!isUnicodeMode) {
            // Classic থেকে — Unicode চালু থাকলে সেখানে, নাহলে সরাসরি English-এ (Unicode বাদ)
            if (unicodeEnabled) { isUnicodeMode = true; }
            else { isEnglishMode = true; }
        } else {
            // Unicode থেকে English-এ — English কখনো বন্ধ হয় না, তাই এটা সবসময় নিরাপদ
            isEnglishMode = true;
            isUnicodeMode = false;
        }
        isSymbolMode = false;
        isEmojiMode = false;
        resetStates();
        updateKeyLabels();
        saveLanguageMode();
    }

    // Ctrl+Alt+B (Classic ⇄ English) আর Ctrl+Alt+V (Unicode ⇄ English) — দুটো
    // হার্ডওয়্যার শর্টকাটই এই একই মেথড কল করে, শুধু target মোড আলাদা। যেকোনো
    // অবস্থা থেকেই কল করা যায়: এখন যদি ইতিমধ্যে সেই target মোডে থাকা হয়, তাহলে
    // English-এ ফিরে যাবে; নাহলে সরাসরি সেই target মোডে চলে যাবে (মাঝখানে অন্য
    // মোড থাকলেও)। যেমন: Unicode মোডে থাকা অবস্থায় Ctrl+Alt+B চাপলে সরাসরি Classic-এ চলে যাবে।
    // Settings থেকে target মোডটা বন্ধ করা থাকলে কিছুই বদলাবে না, শুধু একটা টোস্ট দেখাবে।
    private void applyModeShortcut(boolean targetIsUnicode) {
        boolean targetEnabled = targetIsUnicode ? unicodeEnabled : classicEnabled;
        if (!targetEnabled) {
            Toast.makeText(this, (targetIsUnicode ? "Unicode" : "Classic") + " মোড বন্ধ আছে (Settings থেকে চালু করুন)", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean alreadyInTarget = !isEnglishMode && (isUnicodeMode == targetIsUnicode);
        if (alreadyInTarget) {
            isEnglishMode = true;
        } else {
            isEnglishMode = false;
            isUnicodeMode = targetIsUnicode;
        }
        isSymbolMode = false;
        isEmojiMode = false;
        resetStates();
        updateKeyLabels();
        saveLanguageMode();
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
