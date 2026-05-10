package com.hanif.bijoykeyboard;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private final Handler handler = new Handler();
    private Runnable statusChecker;

    private View cardStep1, cardStep2, cardDone;
    private ImageView iconStep1, iconStep2;
    private Button btnEnableKeyboard, btnSetDefault;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cardStep1         = findViewById(R.id.card_step1);
        cardStep2         = findViewById(R.id.card_step2);
        cardDone          = findViewById(R.id.card_done);
        iconStep1         = findViewById(R.id.icon_step1);
        iconStep2         = findViewById(R.id.icon_step2);
        btnEnableKeyboard = findViewById(R.id.btn_enable_keyboard);
        btnSetDefault     = findViewById(R.id.btn_set_default);

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

    @Override protected void onResume() { super.onResume(); handler.post(statusChecker); }
    @Override protected void onPause()  { super.onPause();  handler.removeCallbacks(statusChecker); }

    private void updateUI() {
        boolean enabled   = isKeyboardEnabled();
        boolean isDefault = isKeyboardDefault();

        // ── Step 1
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

        // ── Step 2
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

        // ── Done
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
        String def = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        return def != null && def.startsWith(getPackageName());
    }
}
