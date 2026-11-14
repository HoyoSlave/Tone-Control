package com.example.tonecontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    public static final String KEY_SELECTED_PRESET_BOTH = "selected_preset_5band_name";
    public static final String KEY_SELECTED_PRESET_LEFT = "selected_preset_5band_name_l";
    public static final String KEY_SELECTED_PRESET_RIGHT = "selected_preset_5band_name_r";
    public static final String KEY_SELECTED_CHANNEL_MODE = "selected_channel_mode";

    public static final int CHANNEL_MODE_BOTH = 0;
    public static final int CHANNEL_MODE_LEFT = 1;
    public static final int CHANNEL_MODE_RIGHT = 2;

    public static final int BAND_COUNT = 5;
    public static final String[] BAND_KEYS = new String[]{
            "lowest_db",
            "low_db",
            "mid_db",
            "high_db",
            "highest_db"
    };

    public static final String[] BAND_KEYS_LEFT = new String[]{
            "lowest_db_l",
            "low_db_l",
            "mid_db_l",
            "high_db_l",
            "highest_db_l"
    };

    public static final String[] BAND_KEYS_RIGHT = new String[]{
            "lowest_db_r",
            "low_db_r",
            "mid_db_r",
            "high_db_r",
            "highest_db_r"
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

    private RadioGroup radioGroupChannel;
    private RadioButton radioChannelBoth;
    private RadioButton radioChannelLeft;
    private RadioButton radioChannelRight;

    private Spinner spinnerPresets;
    private Button btnSaveTemplate;
    private Button btnDeleteTemplate;

    private final SeekBar[] seekBars = new SeekBar[BAND_COUNT];
    private final EditText[] editTexts = new EditText[BAND_COUNT];
    private final boolean[] isUpdatingBand = new boolean[BAND_COUNT];
    private boolean isApplyingPreset = false;
    private boolean isSwitchingChannel = false;

    private int currentChannelMode = CHANNEL_MODE_BOTH;
    private SharedPreferences prefs;

    private static class ToneTemplate {
        final String name;
        final int[] gains;
        final int[] gainsL;
        final int[] gainsR;
        final boolean hasSeparateLR;
        final boolean builtIn;

        ToneTemplate(String name, int[] gains, boolean builtIn) {
            this(name, gains, gains, gains, false, builtIn);
        }

        ToneTemplate(String name, int[] gains, int[] gainsL, int[] gainsR, boolean hasSeparateLR, boolean builtIn) {
            this.name = name;
            this.gains = new int[BAND_COUNT];
            this.gainsL = new int[BAND_COUNT];
            this.gainsR = new int[BAND_COUNT];
            for (int i = 0; i < BAND_COUNT; i++) {
                int base = (gains != null && i < gains.length) ? clampDb(gains[i]) : 0;
                this.gains[i] = base;
                this.gainsL[i] = (gainsL != null && i < gainsL.length) ? clampDb(gainsL[i]) : base;
                this.gainsR[i] = (gainsR != null && i < gainsR.length) ? clampDb(gainsR[i]) : base;
            }
            this.hasSeparateLR = hasSeparateLR;
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

        radioGroupChannel = findViewById(R.id.radio_group_channel);
        radioChannelBoth = findViewById(R.id.radio_channel_both);
        radioChannelLeft = findViewById(R.id.radio_channel_left);
        radioChannelRight = findViewById(R.id.radio_channel_right);

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

        ensureChannelPrefsInitialized();
        loadTemplates();
        setupChannelControls();
        setupPresetControls();
        setupBandControls();
        restoreSavedValues();
        requestBatteryOptimizationExemptionIfNeeded();
        applyAndEnsureBackgroundService();
    }

    private void ensureChannelPrefsInitialized() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (int b = 0; b < BAND_COUNT; b++) {
            int baseVal = clampDb(prefs.getInt(BAND_KEYS[b], 0));
            if (!prefs.contains(BAND_KEYS_LEFT[b])) {
                editor.putInt(BAND_KEYS_LEFT[b], baseVal);
                changed = true;
            }
            if (!prefs.contains(BAND_KEYS_RIGHT[b])) {
                editor.putInt(BAND_KEYS_RIGHT[b], baseVal);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    private void setupChannelControls() {
        currentChannelMode = prefs.getInt(KEY_SELECTED_CHANNEL_MODE, CHANNEL_MODE_BOTH);
        isSwitchingChannel = true;
        if (currentChannelMode == CHANNEL_MODE_LEFT) {
            radioChannelLeft.setChecked(true);
        } else if (currentChannelMode == CHANNEL_MODE_RIGHT) {
            radioChannelRight.setChecked(true);
        } else {
            currentChannelMode = CHANNEL_MODE_BOTH;
            radioChannelBoth.setChecked(true);
        }
        isSwitchingChannel = false;

        radioGroupChannel.setOnCheckedChangeListener((group, checkedId) -> {
            if (isSwitchingChannel) return;
            if (checkedId == R.id.radio_channel_left) {
                currentChannelMode = CHANNEL_MODE_LEFT;
            } else if (checkedId == R.id.radio_channel_right) {
                currentChannelMode = CHANNEL_MODE_RIGHT;
            } else {
                currentChannelMode = CHANNEL_MODE_BOTH;
            }
            prefs.edit().putInt(KEY_SELECTED_CHANNEL_MODE, currentChannelMode).apply();
            refreshUiForCurrentChannel();
        });
    }

    private String getPresetKeyForCurrentMode() {
        if (currentChannelMode == CHANNEL_MODE_LEFT) {
            return KEY_SELECTED_PRESET_LEFT;
        } else if (currentChannelMode == CHANNEL_MODE_RIGHT) {
            return KEY_SELECTED_PRESET_RIGHT;
        } else {
            return KEY_SELECTED_PRESET_BOTH;
        }
    }

    private int getSavedBandForCurrentMode(int bandIndex) {
        if (currentChannelMode == CHANNEL_MODE_LEFT) {
            int fallback = prefs.getInt(BAND_KEYS[bandIndex], 0);
            return clampDb(prefs.getInt(BAND_KEYS_LEFT[bandIndex], fallback));
        } else if (currentChannelMode == CHANNEL_MODE_RIGHT) {
            int fallback = prefs.getInt(BAND_KEYS[bandIndex], 0);
            return clampDb(prefs.getInt(BAND_KEYS_RIGHT[bandIndex], fallback));
        } else {
            int left = prefs.getInt(BAND_KEYS_LEFT[bandIndex], prefs.getInt(BAND_KEYS[bandIndex], 0));
            return clampDb(prefs.getInt(BAND_KEYS[bandIndex], left));
        }
    }

    private void refreshUiForCurrentChannel() {
        String presetKey = getPresetKeyForCurrentMode();
        String fallbackPreset = prefs.getString(KEY_SELECTED_PRESET_BOTH, "Neutral");
        String savedPreset = prefs.getString(presetKey, fallbackPreset);
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

        for (int b = 0; b < BAND_COUNT; b++) {
            int savedVal = getSavedBandForCurrentMode(b);
            isUpdatingBand[b] = true;
            seekBars[b].setProgress(savedVal + OFFSET);
            String text = String.valueOf(savedVal);
            editTexts[b].setText(text);
            editTexts[b].setSelection(text.length());
            isUpdatingBand[b] = false;
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
                    int[] gainsL = new int[BAND_COUNT];
                    int[] gainsR = new int[BAND_COUNT];
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

                    JSONArray gainsLArr = obj.optJSONArray("gains_l");
                    JSONArray gainsRArr = obj.optJSONArray("gains_r");
                    boolean hasLR = (gainsLArr != null && gainsRArr != null);
                    for (int b = 0; b < BAND_COUNT; b++) {
                        gainsL[b] = (gainsLArr != null) ? clampDb(gainsLArr.optInt(b, gains[b])) : gains[b];
                        gainsR[b] = (gainsRArr != null) ? clampDb(gainsRArr.optInt(b, gains[b])) : gains[b];
                    }

                    templates.add(new ToneTemplate(name, gains, gainsL, gainsR, hasLR, false));
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
                    JSONArray gainsLArr = new JSONArray();
                    JSONArray gainsRArr = new JSONArray();
                    for (int b = 0; b < BAND_COUNT; b++) {
                        gainsArr.put(t.gains[b]);
                        gainsLArr.put(t.gainsL[b]);
                        gainsRArr.put(t.gainsR[b]);
                    }
                    obj.put("gains", gainsArr);
                    obj.put("gains_l", gainsLArr);
                    obj.put("gains_r", gainsRArr);
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
                    SharedPreferences.Editor editor = prefs.edit();
                    if (currentChannelMode == CHANNEL_MODE_BOTH) {
                        editor.putString(KEY_SELECTED_PRESET_BOTH, selected.name);
                        editor.putString(KEY_SELECTED_PRESET_LEFT, selected.name);
                        editor.putString(KEY_SELECTED_PRESET_RIGHT, selected.name);
                    } else if (currentChannelMode == CHANNEL_MODE_LEFT) {
                        editor.putString(KEY_SELECTED_PRESET_LEFT, selected.name);
                    } else {
                        editor.putString(KEY_SELECTED_PRESET_RIGHT, selected.name);
                    }
                    editor.apply();
                    applyTemplateForCurrentMode(selected);
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
                    int[] currentGainsL = new int[BAND_COUNT];
                    int[] currentGainsR = new int[BAND_COUNT];
                    for (int b = 0; b < BAND_COUNT; b++) {
                        int sliderVal = clampDb(seekBars[b].getProgress() - OFFSET);
                        currentGains[b] = sliderVal;
                        if (currentChannelMode == CHANNEL_MODE_BOTH) {
                            currentGainsL[b] = sliderVal;
                            currentGainsR[b] = sliderVal;
                        } else if (currentChannelMode == CHANNEL_MODE_LEFT) {
                            currentGainsL[b] = sliderVal;
                            currentGainsR[b] = clampDb(prefs.getInt(BAND_KEYS_RIGHT[b], prefs.getInt(BAND_KEYS[b], 0)));
                        } else {
                            currentGainsL[b] = clampDb(prefs.getInt(BAND_KEYS_LEFT[b], prefs.getInt(BAND_KEYS[b], 0)));
                            currentGainsR[b] = sliderVal;
                        }
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
                        System.arraycopy(currentGainsL, 0, existing.gainsL, 0, BAND_COUNT);
                        System.arraycopy(currentGainsR, 0, existing.gainsR, 0, BAND_COUNT);
                    } else {
                        templates.add(new ToneTemplate(name, currentGains, currentGainsL, currentGainsR, true, false));
                        existingIndex = templates.size() - 1;
                    }

                    saveCustomTemplates();
                    refreshTemplateNames();
                    isApplyingPreset = true;
                    spinnerPresets.setSelection(existingIndex);
                    prefs.edit().putString(getPresetKeyForCurrentMode(), templates.get(existingIndex).name).apply();
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
        prefs.edit().putString(getPresetKeyForCurrentMode(), fallback.name).apply();
        applyTemplateForCurrentMode(fallback);
    }

    private void applyTemplateForCurrentMode(ToneTemplate template) {
        SharedPreferences.Editor editor = prefs.edit();
        for (int b = 0; b < BAND_COUNT; b++) {
            int uiVal;
            if (currentChannelMode == CHANNEL_MODE_LEFT) {
                int valL = clampDb(template.hasSeparateLR ? template.gainsL[b] : template.gains[b]);
                editor.putInt(BAND_KEYS_LEFT[b], valL);
                uiVal = valL;
            } else if (currentChannelMode == CHANNEL_MODE_RIGHT) {
                int valR = clampDb(template.hasSeparateLR ? template.gainsR[b] : template.gains[b]);
                editor.putInt(BAND_KEYS_RIGHT[b], valR);
                uiVal = valR;
            } else {
                int valBoth = clampDb(template.gains[b]);
                int valL = clampDb(template.hasSeparateLR ? template.gainsL[b] : valBoth);
                int valR = clampDb(template.hasSeparateLR ? template.gainsR[b] : valBoth);
                editor.putInt(BAND_KEYS[b], valBoth);
                editor.putInt(BAND_KEYS_LEFT[b], valL);
                editor.putInt(BAND_KEYS_RIGHT[b], valR);
                uiVal = valBoth;
            }

            isUpdatingBand[b] = true;
            seekBars[b].setProgress(uiVal + OFFSET);
            String text = String.valueOf(uiVal);
            editTexts[b].setText(text);
            editTexts[b].setSelection(text.length());
            isUpdatingBand[b] = false;
        }
        editor.apply();
        applyAndEnsureBackgroundService();
    }

    private void restoreSavedValues() {
        refreshUiForCurrentChannel();
    }

    private void saveAndApplyBand(int bandIndex, int dbValue) {
        int clamped = clampDb(dbValue);
        SharedPreferences.Editor editor = prefs.edit();
        if (currentChannelMode == CHANNEL_MODE_LEFT) {
            editor.putInt(BAND_KEYS_LEFT[bandIndex], clamped);
        } else if (currentChannelMode == CHANNEL_MODE_RIGHT) {
            editor.putInt(BAND_KEYS_RIGHT[bandIndex], clamped);
        } else {
            editor.putInt(BAND_KEYS[bandIndex], clamped);
            editor.putInt(BAND_KEYS_LEFT[bandIndex], clamped);
            editor.putInt(BAND_KEYS_RIGHT[bandIndex], clamped);
        }
        editor.apply();
        applyAndEnsureBackgroundService();
    }

    private void applyAndEnsureBackgroundService() {
        ToneControlService.applyFromPreferences(this);
        ToneControlService.startOrUpdateService(this, null);
    }

    private void setupBandControls() {
        for (int b = 0; b < BAND_COUNT; b++) {
            final int index = b;
            seekBars[index].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (isUpdatingBand[index]) return;
                    int dbValue = clampDb(progress - OFFSET);
                    saveAndApplyBand(index, dbValue);
                    isUpdatingBand[index] = true;
                    String text = String.valueOf(dbValue);
                    editTexts[index].setText(text);
                    editTexts[index].setSelection(text.length());
                    isUpdatingBand[index] = false;
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
