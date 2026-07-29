package com.hanif.bijoykeyboard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// কিবোর্ডের সব কাস্টমাইজেশন (থিম, হাইট, কী সাউন্ড, ভাইব্রেশন, ডিকশনারি ইমপোর্ট/এক্সপোর্ট) এখান থেকে
// নিয়ন্ত্রণ করা হয়। MyKeyboardService.java একই SharedPreferences ("kb_settings" এবং "word_freq")
// থেকে মান পড়ে, তাই এখানে সেভ করলেই কিবোর্ডে সাথে সাথে প্রতিফলিত হবে (পরের বার কিবোর্ড
// খোলার/ফিল্ড বদলানোর সময়)।
public class SettingsActivity extends AppCompatActivity {

    private static final String SETTINGS_PREFS = "kb_settings";
    private static final String DICT_PREFS = "word_freq";
    private static final int REQ_EXPORT = 101;
    private static final int REQ_IMPORT = 102;

    private SharedPreferences settingsPrefs;
    private TextView tvHeightValue, tvVibrationValue, tvDictCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        settingsPrefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);

        setupBackButton();
        setupThemeSection();
        setupHeightSection();
        setupSoundVibrationSection();
        setupDictionarySection();
    }

    private void setupBackButton() {
        View btnBack = findViewById(R.id.btn_settings_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
    }

    // ══════════════════════════════════════
    // থিম
    // ══════════════════════════════════════
    private void setupThemeSection() {
        RadioGroup group = findViewById(R.id.theme_radio_group);
        if (group == null) return;

        String saved = settingsPrefs.getString("theme", "dark");
        int checkedId = R.id.theme_dark;
        if ("light".equals(saved)) checkedId = R.id.theme_light;
        else if ("midnight_blue".equals(saved)) checkedId = R.id.theme_midnight_blue;
        else if ("forest_green".equals(saved)) checkedId = R.id.theme_forest_green;
        group.check(checkedId);

        group.setOnCheckedChangeListener((g, checkedIdNow) -> {
            String theme = "dark";
            if (checkedIdNow == R.id.theme_light) theme = "light";
            else if (checkedIdNow == R.id.theme_midnight_blue) theme = "midnight_blue";
            else if (checkedIdNow == R.id.theme_forest_green) theme = "forest_green";
            settingsPrefs.edit().putString("theme", theme).apply();
        });
    }

    // ══════════════════════════════════════
    // কিবোর্ড হাইট
    // ══════════════════════════════════════
    private void setupHeightSection() {
        SeekBar seekBar = findViewById(R.id.seek_height);
        tvHeightValue = findViewById(R.id.tv_height_value);
        if (seekBar == null) return;

        // SeekBar রেঞ্জ 0-60 → আসল percent 70-130 (মাঝখানে 100 = ডিফল্ট)
        int savedPercent = settingsPrefs.getInt("height_percent", 100);
        seekBar.setMax(60);
        seekBar.setProgress(savedPercent - 70);
        updateHeightLabel(savedPercent);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int percent = progress + 70;
                updateHeightLabel(percent);
                if (fromUser) settingsPrefs.edit().putInt("height_percent", percent).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void updateHeightLabel(int percent) {
        if (tvHeightValue != null) tvHeightValue.setText(percent + "%");
    }

    // ══════════════════════════════════════
    // কী সাউন্ড ও ভাইব্রেশন
    // ══════════════════════════════════════
    private void setupSoundVibrationSection() {
        Switch switchSound = findViewById(R.id.switch_key_sound);
        if (switchSound != null) {
            switchSound.setChecked(settingsPrefs.getBoolean("key_sound", false));
            switchSound.setOnCheckedChangeListener((CompoundButton btn, boolean checked) ->
                    settingsPrefs.edit().putBoolean("key_sound", checked).apply());
        }

        Switch switchVibration = findViewById(R.id.switch_vibration);
        SeekBar seekVibration = findViewById(R.id.seek_vibration_strength);
        tvVibrationValue = findViewById(R.id.tv_vibration_value);

        boolean vibrationEnabled = settingsPrefs.getBoolean("vibration_enabled", true);
        int vibrationStrength = settingsPrefs.getInt("vibration_strength", 60);

        if (switchVibration != null) {
            switchVibration.setChecked(vibrationEnabled);
            if (seekVibration != null) seekVibration.setEnabled(vibrationEnabled);
            switchVibration.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
                settingsPrefs.edit().putBoolean("vibration_enabled", checked).apply();
                if (seekVibration != null) seekVibration.setEnabled(checked);
            });
        }

        if (seekVibration != null) {
            seekVibration.setMax(100);
            seekVibration.setProgress(vibrationStrength);
            updateVibrationLabel(vibrationStrength);
            seekVibration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    updateVibrationLabel(progress);
                    if (fromUser) settingsPrefs.edit().putInt("vibration_strength", progress).apply();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
    }

    private void updateVibrationLabel(int percent) {
        if (tvVibrationValue != null) tvVibrationValue.setText(percent + "%");
    }

    // ══════════════════════════════════════
    // ডিকশনারি Import / Export
    // ══════════════════════════════════════
    // "word_freq" SharedPreferences-এর "freq" স্ট্রিং ফরম্যাট MyKeyboardService.java-এর
    // loadAdaptiveWords()/saveAdaptiveWords()-এর সাথে হুবহু মেলে (word:count||word:count||...),
    // তাই এখানে সরাসরি সেই একই ফরম্যাটে ফাইলে লেখা/পড়া হচ্ছে।
    private void setupDictionarySection() {
        tvDictCount = findViewById(R.id.tv_dict_count);
        refreshDictCount();

        Button btnExport = findViewById(R.id.btn_export_dict);
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, "bijoy_dictionary_backup.txt");
                startActivityForResult(intent, REQ_EXPORT);
            });
        }

        Button btnImport = findViewById(R.id.btn_import_dict);
        if (btnImport != null) {
            btnImport.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                startActivityForResult(intent, REQ_IMPORT);
            });
        }

        Button btnClear = findViewById(R.id.btn_clear_dict);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                getSharedPreferences(DICT_PREFS, MODE_PRIVATE).edit().remove("freq").apply();
                refreshDictCount();
                Toast.makeText(this, "ডিকশনারি খালি করা হয়েছে", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void refreshDictCount() {
        String raw = getSharedPreferences(DICT_PREFS, MODE_PRIVATE).getString("freq", "");
        int count = 0;
        if (!raw.isEmpty()) {
            for (String pair : raw.split("\\|\\|")) {
                if (!pair.isEmpty()) count++;
            }
        }
        if (tvDictCount != null) tvDictCount.setText(count + "টা শব্দ শেখা আছে");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQ_EXPORT) {
            exportDictionaryTo(uri);
        } else if (requestCode == REQ_IMPORT) {
            importDictionaryFrom(uri);
        }
    }

    private void exportDictionaryTo(Uri uri) {
        String raw = getSharedPreferences(DICT_PREFS, MODE_PRIVATE).getString("freq", "");
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("output stream null");
            os.write(raw.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "ডিকশনারি এক্সপোর্ট সম্পন্ন হয়েছে", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "এক্সপোর্ট ব্যর্থ হয়েছে: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void importDictionaryFrom(Uri uri) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("input stream null");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        } catch (IOException e) {
            Toast.makeText(this, "ইমপোর্ট ব্যর্থ হয়েছে: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        String importedRaw = sb.toString().trim();
        if (importedRaw.isEmpty()) {
            Toast.makeText(this, "ফাইলটা খালি বা ভুল ফরম্যাটে আছে", Toast.LENGTH_SHORT).show();
            return;
        }

        // বিদ্যমান ডিকশনারির সাথে merge করা হচ্ছে (overwrite নয়) — count যোগ হবে
        SharedPreferences dictPrefs = getSharedPreferences(DICT_PREFS, MODE_PRIVATE);
        HashMap<String, Integer> merged = new HashMap<>();
        for (String pair : dictPrefs.getString("freq", "").split("\\|\\|")) {
            addPairToMap(merged, pair);
        }
        int importedCount = 0;
        for (String pair : importedRaw.split("\\|\\|")) {
            if (addPairToMap(merged, pair)) importedCount++;
        }

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            out.append(e.getKey()).append(":").append(e.getValue()).append("||");
        }
        dictPrefs.edit().putString("freq", out.toString()).apply();
        refreshDictCount();
        Toast.makeText(this, importedCount + "টা শব্দ ইমপোর্ট হয়েছে", Toast.LENGTH_SHORT).show();
    }

    private boolean addPairToMap(HashMap<String, Integer> map, String pair) {
        if (pair == null || pair.isEmpty()) return false;
        int sep = pair.lastIndexOf(':');
        if (sep <= 0) return false;
        String word = pair.substring(0, sep);
        try {
            int count = Integer.parseInt(pair.substring(sep + 1));
            Integer existing = map.get(word);
            map.put(word, (existing == null ? 0 : existing) + count);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
