package com.example.tonecontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    public static final String PREFS_NAME = "tone_control_prefs";
    public static final String KEY_CUSTOM_TEMPLATES = "custom_templates_5band_json";
    public static final String KEY_SELECTED_PRESET = "selected_preset_5band_name";

    public static final int BAND_COUNT = 5;
    public static final String[] BAND_KEYS = new String[]{
            "lowest_db",
            "low_db",
            "mid_db",
            "high_db",
            "highest_db"
    };

    public static final float[] BAND_CUTOFF_HZ = new float[]{
            60.0f,
            256.0f,
            1095.0f,
            4680.0f,
            20000.0f
    };

    public static final int MIN_DB = -16;
    public static final int MAX_DB = 16;
    public static final int OFFSET = 16; // Maps [-16..16] dB to SeekBar [0..32]
    public static final int SEEK_MAX = MAX_DB - MIN_DB; // 32

    private TextView textSessionStatus;
    private Button btnRescanSessions;
    private Spinner spinnerPresets;
    private Button btnSaveTemplate;
    private Button btnDeleteTemplate;

    private final SeekBar[] seekBars = new SeekBar[BAND_COUNT];
    private final EditText[] editTexts = new EditText[BAND_COUNT];
    private final boolean[] isUpdatingBand = new boolean[BAND_COUNT];
    private boolean isApplyingPreset = false;

    private SharedPreferences prefs;

    private final BroadcastReceiver sessionUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshDiagnosticStatus();
        }
    };

    private static class ToneTemplate {
        final String name;
        final int[] gains;
        final boolean builtIn;

        ToneTemplate(String name, int[] gains, boolean builtIn) {
            this.name = name;
            this.gains = new int[BAND_COUNT];
            for (int i = 0; i < BAND_COUNT; i++) {
                this.gains[i] = (gains != null && i < gains.length) ? clampDb(gains[i]) : 0;
            }
            this.builtIn = builtIn;
        }
    }

    private final List<ToneTemplate> templates = new ArrayList<>();
    private final List<String> templateNames = new ArrayList<>();
    private ArrayAdapter<String> spinnerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        textSessionStatus = findViewById(R.id.text_session_status);
        btnRescanSessions = findViewById(R.id.btn_rescan_sessions);
        spinnerPresets = findViewById(R.id.spinner_presets);
        btnSaveTemplate = findViewById(R.id.btn_save_template);
        btnDeleteTemplate = findViewById(R.id.btn_delete_template);

        int[] seekIds = new int[]{
                R.id.seek_lowest,
                R.id.seek_low,
                R.id.seek_mid,
                R.id.seek_high,
                R.id.seek_highest
        };

        int[] editIds = new int[]{
                R.id.edit_lowest_db,
                R.id.edit_low_db,
                R.id.edit_mid_db,
                R.id.edit_high_db,
                R.id.edit_highest_db
        };

        for (int i = 0; i < BAND_COUNT; i++) {
            seekBars[i] = findViewById(seekIds[i]);
            seekBars[i].setMax(SEEK_MAX);
            editTexts[i] = findViewById(editIds[i]);
        }

        btnRescanSessions.setOnClickListener(v -> {
            ToneControlService.triggerActiveSessionScan(this);
            v.postDelayed(this::refreshDiagnosticStatus, 400);
        });

        loadTemplates();
        setupPresetControls();
        setupBandControls();
        restoreSavedValues();
        requestBatteryOptimizationExemptionIfNeeded();
        applyAndEnsureBackgroundService();
        refreshDiagnosticStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ToneControlService.ACTION_SESSIONS_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sessionUpdateReceiver, filter);
        }
        ToneControlService.triggerActiveSessionScan(this);
        refreshDiagnosticStatus();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(sessionUpdateReceiver);
        } catch (Exception ignored) {}
        super.onPause();
    }

    private void refreshDiagnosticStatus() {
        if (textSessionStatus != null) {
            textSessionStatus.setText(ToneControlService.getDiagnosticStatus(this));
        }
    }

    private void requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    private void loadTemplates() {
        templates.clear();
        templates.add(new ToneTemplate("Neutral", new int[]{0, 0, 0, 0, 0}, true));
        templates.add(new ToneTemplate("FixMuddy", new int[]{0, -4, 0, 0, 0}, true));
        templates.add(new ToneTemplate("FixSharp", new int[]{0, 0, 0, -4, 0}, true));
        templates.add(new ToneTemplate("FixBoth", new int[]{0, -4, 0, -4, 0}, true));

        String rawJson = prefs.getString(KEY_CUSTOM_TEMPLATES, "[]");
        try {
            JSONArray array = new JSONArray(rawJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String name = obj.optString("name", "").trim();
                if (!name.isEmpty()) {
                    int[] gains = new int[BAND_COUNT];
                    JSONArray gainsArr = obj.optJSONArray("gains");
                    if (gainsArr != null) {
                        for (int b = 0; b < BAND_COUNT; b++) {
                            gains[b] = clampDb(gainsArr.optInt(b, 0));
                        }
                    } else {
                        if (BAND_COUNT >= 3) {
                            gains[0] = clampDb(obj.optInt("low", 0));
                            gains[1] = clampDb(obj.optInt("mid", 0));
                            gains[2] = clampDb(obj.optInt("high", 0));
                        }
                    }
                    templates.add(new ToneTemplate(name, gains, false));
                }
            }
        } catch (JSONException ignored) {}

        refreshTemplateNames();
    }

    private void saveCustomTemplates() {
        JSONArray array = new JSONArray();
        for (ToneTemplate t : templates) {
            if (!t.builtIn) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("name", t.name);
                    JSONArray gainsArr = new JSONArray();
                    for (int b = 0; b < BAND_COUNT; b++) {
                        gainsArr.put(t.gains[b]);
                    }
                    obj.put("gains", gainsArr);
                    array.put(obj);
                } catch (JSONException ignored) {}
            }
        }
        prefs.edit().putString(KEY_CUSTOM_TEMPLATES, array.toString()).apply();
    }

    private void refreshTemplateNames() {
        templateNames.clear();
        for (ToneTemplate t : templates) {
            templateNames.add(t.name);
        }
        if (spinnerAdapter != null) {
            spinnerAdapter.notifyDataSetChanged();
        }
    }

    private void setupPresetControls() {
        spinnerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                templateNames
        );
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPresets.setAdapter(spinnerAdapter);

        spinnerPresets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isApplyingPreset) return;
                if (position >= 0 && position < templates.size()) {
                    ToneTemplate selected = templates.get(position);
                    prefs.edit().putString(KEY_SELECTED_PRESET, selected.name).apply();
                    applyTemplateValues(selected.gains);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnSaveTemplate.setOnClickListener(v -> showSaveTemplateDialog());
        btnDeleteTemplate.setOnClickListener(v -> deleteSelectedTemplate());
    }

    private void showSaveTemplateDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.dialog_save_hint);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_save_title)
                .setView(input)
                .setPositiveButton(R.string.save_template, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }

                    int[] currentGains = new int[BAND_COUNT];
                    for (int b = 0; b < BAND_COUNT; b++) {
                        currentGains[b] = clampDb(seekBars[b].getProgress() - OFFSET);
                    }

                    int existingIndex = -1;
                    for (int i = 0; i < templates.size(); i++) {
                        if (templates.get(i).name.equalsIgnoreCase(name)) {
                            existingIndex = i;
                            break;
                        }
                    }

                    if (existingIndex >= 0) {
                        ToneTemplate existing = templates.get(existingIndex);
                        if (existing.builtIn) {
                            return;
                        }
                        System.arraycopy(currentGains, 0, existing.gains, 0, BAND_COUNT);
                    } else {
                        templates.add(new ToneTemplate(name, currentGains, false));
                        existingIndex = templates.size() - 1;
                    }

                    saveCustomTemplates();
                    refreshTemplateNames();
                    isApplyingPreset = true;
                    spinnerPresets.setSelection(existingIndex);
                    prefs.edit().putString(KEY_SELECTED_PRESET, templates.get(existingIndex).name).apply();
                    isApplyingPreset = false;
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void deleteSelectedTemplate() {
        int pos = spinnerPresets.getSelectedItemPosition();
        if (pos < 0 || pos >= templates.size()) return;

        ToneTemplate selected = templates.get(pos);
        if (selected.builtIn) {
            return;
        }

        templates.remove(pos);
        saveCustomTemplates();
        refreshTemplateNames();

        int newPos = Math.max(0, Math.min(pos, templates.size() - 1));
        isApplyingPreset = true;
        spinnerPresets.setSelection(newPos);
        isApplyingPreset = false;

        ToneTemplate fallback = templates.get(newPos);
        prefs.edit().putString(KEY_SELECTED_PRESET, fallback.name).apply();
        applyTemplateValues(fallback.gains);
    }

    private void applyTemplateValues(int[] gains) {
        SharedPreferences.Editor editor = prefs.edit();
        for (int b = 0; b < BAND_COUNT; b++) {
            int clamped = clampDb(gains[b]);
            isUpdatingBand[b] = true;
            seekBars[b].setProgress(clamped + OFFSET);
            String text = String.valueOf(clamped);
            editTexts[b].setText(text);
            editTexts[b].setSelection(text.length());
            isUpdatingBand[b] = false;
            editor.putInt(BAND_KEYS[b], clamped);
        }
        editor.apply();
        applyAndEnsureBackgroundService();
    }

    private void restoreSavedValues() {
        String savedPreset = prefs.getString(KEY_SELECTED_PRESET, "Neutral");
        int presetIdx = 0;
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).name.equals(savedPreset)) {
                presetIdx = i;
                break;
            }
        }

        isApplyingPreset = true;
        spinnerPresets.setSelection(presetIdx, false);
        isApplyingPreset = false;

        SharedPreferences.Editor editor = prefs.edit();
        for (int b = 0; b < BAND_COUNT; b++) {
            int savedVal = clampDb(prefs.getInt(BAND_KEYS[b], 0));
            editor.putInt(BAND_KEYS[b], savedVal);
            isUpdatingBand[b] = true;
            seekBars[b].setProgress(savedVal + OFFSET);
            String text = String.valueOf(savedVal);
            editTexts[b].setText(text);
            editTexts[b].setSelection(text.length());
            isUpdatingBand[b] = false;
        }
        editor.apply();
    }

    private void saveAndApplyBand(int bandIndex, int dbValue) {
        prefs.edit().putInt(BAND_KEYS[bandIndex], clampDb(dbValue)).apply();
        applyAndEnsureBackgroundService();
    }

    private void applyAndEnsureBackgroundService() {
        ToneControlService.applyFromPreferences(this);
        ToneControlService.startOrUpdateService(this, null);
        refreshDiagnosticStatus();
    }

    private void setupBandControls() {
        for (int b = 0; b < BAND_COUNT; b++) {
            final int index = b;
            seekBars[index].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int dbValue = clampDb(progress - OFFSET);
                    saveAndApplyBand(index, dbValue);
                    if (!isUpdatingBand[index]) {
                        isUpdatingBand[index] = true;
                        String text = String.valueOf(dbValue);
                        editTexts[index].setText(text);
                        editTexts[index].setSelection(text.length());
                        isUpdatingBand[index] = false;
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            editTexts[index].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    if (isUpdatingBand[index]) return;
                    Integer parsed = parseDbInput(s.toString());
                    if (parsed != null) {
                        int clamped = clampDb(parsed);
                        isUpdatingBand[index] = true;
                        seekBars[index].setProgress(clamped + OFFSET);
                        if (clamped != parsed) {
                            String clampedText = String.valueOf(clamped);
                            editTexts[index].setText(clampedText);
                            editTexts[index].setSelection(clampedText.length());
                        }
                        saveAndApplyBand(index, clamped);
                        isUpdatingBand[index] = false;
                    }
                }
            });

            editTexts[index].setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    normalizeInputField(index);
                    editTexts[index].clearFocus();
                }
                return false;
            });

            editTexts[index].setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    normalizeInputField(index);
                }
            });
        }
    }

    private void normalizeInputField(int index) {
        Integer parsed = parseDbInput(editTexts[index].getText().toString());
        int dbValue = (parsed != null) ? clampDb(parsed) : clampDb(seekBars[index].getProgress() - OFFSET);
        isUpdatingBand[index] = true;
        seekBars[index].setProgress(dbValue + OFFSET);
        String formatted = String.valueOf(dbValue);
        editTexts[index].setText(formatted);
        editTexts[index].setSelection(formatted.length());
        isUpdatingBand[index] = false;
        saveAndApplyBand(index, dbValue);
    }

    private Integer parseDbInput(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("-") || trimmed.equals("+")) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int clampDb(int value) {
        return Math.max(MIN_DB, Math.min(MAX_DB, value));
    }
}
