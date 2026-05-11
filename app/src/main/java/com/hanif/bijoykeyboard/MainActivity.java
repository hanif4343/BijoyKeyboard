package com.hanif.bijoykeyboard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private final Handler handler = new Handler();
    private Runnable statusChecker;
    private View cardStep1, cardStep2, cardDone;
    private ImageView iconStep1, iconStep2;
    private Button btnEnableKeyboard, btnSetDefault;

    // Onboarding
    private View viewOnboarding, viewSetup;
    private int currentPage = 0;
    private LinearLayout dotsLayout;

    private static final String PREFS = "bijoy_prefs";
    private static final String KEY_ONBOARDED = "onboarded";

    // Feature pages: emoji, title, desc
    private final String[][] FEATURES = {
        {"⌨️", "হানিফ বিজয় কিবোর্ড", "Unicode বাংলা কিবোর্ড\nচাকরির পরীক্ষা থেকে দৈনন্দিন টাইপিং\nসবকিছুর জন্য তৈরি"},
        {"🔤", "বিজয় লেআউট", "অফিসিয়াল বিজয় Unicode লেআউট\nশুদ্ধ বাংলা টাইপিং\nযুক্তবর্ণ, রেফ, কার সব সঠিক"},
        {"🎤", "ভয়েস টাইপিং", "বাংলায় কথা বললে বাংলা লেখা হবে\n\"দাঁড়ি\" বললে ।\n\"কমা\" বললে ,\n\"নতুন লাইন\" বললে লাইন বদলাবে"},
        {"📳", "হ্যাপটিক ফিডব্যাক", "প্রতিটি কী-তে হালকা কম্পন\nটাইপিং অনুভব করো\niPhone-এর মতো অনুভূতি"},
        {"🔢", "নম্বর রো সবসময়", "উপরে সবসময় ০-৯ নম্বর\n?123 চাপতে হবে না\nShift+০ দিলে ঃ বিসর্গ"},
        {"⚡", "দ্রুত শর্টকাট", "Shift+A = রেফ (র্)\nz = র-ফলা (্র)\nShift+Z = য-ফলা (্য)\ng + Space = দৃশ্যমান হসন্ত"},
        {"🌐", "বাংলা ও ইংরেজি", "এক ক্লিকে বাংলা↔ইংরেজি\nভয়েস টাইপও ভাষা অনুযায়ী\nExternal keyboard সম্পূর্ণ সাপোর্ট"},
        {"📋", "ক্লিপবোর্ড", "কপি করা টেক্সট সেভ থাকে\nযেকোনো সময় paste করো\nকিবোর্ড থেকেই অ্যাক্সেস"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean onboarded = prefs.getBoolean(KEY_ONBOARDED, false);

        if (onboarded) {
            showSetupScreen();
        } else {
            showOnboarding();
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    // ══════════════════════════════════════
    // ONBOARDING
    // ══════════════════════════════════════

    private void showOnboarding() {
        setContentView(R.layout.activity_onboarding);

        TextView tvEmoji   = findViewById(R.id.tv_feature_emoji);
        TextView tvTitle   = findViewById(R.id.tv_feature_title);
        TextView tvDesc    = findViewById(R.id.tv_feature_desc);
        Button   btnNext   = findViewById(R.id.btn_next);
        Button   btnSkip   = findViewById(R.id.btn_skip);
        dotsLayout         = findViewById(R.id.dots_layout);

        buildDots(0);
        loadPage(tvEmoji, tvTitle, tvDesc, btnNext, 0);

        btnNext.setOnClickListener(v -> {
            if (currentPage < FEATURES.length - 1) {
                currentPage++;
                loadPage(tvEmoji, tvTitle, tvDesc, btnNext, currentPage);
                buildDots(currentPage);
            } else {
                finishOnboarding();
            }
        });

        btnSkip.setOnClickListener(v -> finishOnboarding());
    }

    private void loadPage(TextView emoji, TextView title, TextView desc, Button btn, int page) {
        emoji.setText(FEATURES[page][0]);
        title.setText(FEATURES[page][1]);
        desc.setText(FEATURES[page][2]);
        btn.setText(page == FEATURES.length - 1 ? "শুরু করো ➜" : "পরবর্তী ➜");
    }

    private void buildDots(int active) {
        if (dotsLayout == null) return;
        dotsLayout.removeAllViews();
        for (int i = 0; i < FEATURES.length; i++) {
            TextView dot = new TextView(this);
            dot.setText("●");
            dot.setTextSize(10);
            dot.setPadding(4, 0, 4, 0);
            dot.setTextColor(i == active ? Color.parseColor("#38BDF8") : Color.parseColor("#334155"));
            dotsLayout.addView(dot);
        }
    }

    private void finishOnboarding() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDED, true).apply();
        showSetupScreen();
    }

    // ══════════════════════════════════════
    // SETUP SCREEN
    // ══════════════════════════════════════

    private void showSetupScreen() {
        setContentView(R.layout.activity_main);

        cardStep1         = findViewById(R.id.card_step1);
        cardStep2         = findViewById(R.id.card_step2);
        cardDone          = findViewById(R.id.card_done);
        iconStep1         = findViewById(R.id.icon_step1);
        iconStep2         = findViewById(R.id.icon_step2);
        btnEnableKeyboard = findViewById(R.id.btn_enable_keyboard);
        btnSetDefault     = findViewById(R.id.btn_set_default);

        // Feature guide button
        Button btnGuide = findViewById(R.id.btn_guide);
        if (btnGuide != null) {
            btnGuide.setOnClickListener(v -> {
                currentPage = 0;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_ONBOARDED, false).apply();
                showOnboarding();
            });
        }

        btnEnableKeyboard.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        );
        btnSetDefault.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        });

        statusChecker = new Runnable() {
            @Override public void run() {
                updateUI();
                handler.postDelayed(this, 1000);
            }
        };
    }

    @Override protected void onResume() {
        super.onResume();
        if (statusChecker != null) handler.post(statusChecker);
    }
    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusChecker);
    }

    private void updateUI() {
        if (cardStep1 == null) return;
        boolean enabled   = isKeyboardEnabled();
        boolean isDefault = isKeyboardDefault();

        if (enabled) {
            iconStep1.setImageResource(android.R.drawable.checkbox_on_background);
            cardStep1.setAlpha(0.45f);
            btnEnableKeyboard.setText("✓ Enable হয়েছে");
            btnEnableKeyboard.setEnabled(false);
        } else {
            iconStep1.setImageResource(android.R.drawable.radiobutton_off_background);
            cardStep1.setAlpha(1f);
            btnEnableKeyboard.setText("Enable করুন  ➜");
            btnEnableKeyboard.setEnabled(true);
        }

        if (!enabled) {
            cardStep2.setAlpha(0.35f);
            btnSetDefault.setEnabled(false);
        } else if (isDefault) {
            iconStep2.setImageResource(android.R.drawable.checkbox_on_background);
            cardStep2.setAlpha(0.45f);
            btnSetDefault.setText("✓ Default হয়েছে");
            btnSetDefault.setEnabled(false);
        } else {
            iconStep2.setImageResource(android.R.drawable.radiobutton_off_background);
            cardStep2.setAlpha(1f);
            btnSetDefault.setText("Default করুন  ➜");
            btnSetDefault.setEnabled(true);
        }

        if (enabled && isDefault) {
            cardDone.setVisibility(View.VISIBLE);
            handler.removeCallbacks(statusChecker);
        } else {
            cardDone.setVisibility(View.GONE);
        }
    }

    private boolean isKeyboardEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        for (InputMethodInfo imi : imm.getEnabledInputMethodList())
            if (imi.getPackageName().equals(getPackageName())) return true;
        return false;
    }

    private boolean isKeyboardDefault() {
        String def = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return def != null && def.startsWith(getPackageName());
    }
}
