package com.example.tonecontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    public static final String PREFS_NAME = "tone_control_prefs";
    public static final String KEY_LOW_DB = "low_db";
    public static final String KEY_MID_DB = "mid_db";
    public static final String KEY_HIGH_DB = "high_db";
    public static final String KEY_CUSTOM_TEMPLATES = "custom_templates_json";
    public static final String KEY_SELECTED_PRESET = "selected_preset_name";

    public static final int MIN_DB = -25;
    public static final int MAX_DB = 25;
    public static final int OFFSET = 25; // Maps [-25..25] dB to SeekBar [0..50]

    private Spinner spinnerPresets;
    private Button btnSaveTemplate;
    private Button btnDeleteTemplate;

    private SeekBar seekLow;
    private SeekBar seekMid;
    private SeekBar seekHigh;

    private EditText editLowDb;
    private EditText editMidDb;
    private EditText editHighDb;

    private boolean isUpdatingLow = false;
    private boolean isUpdatingMid = false;
    private boolean isUpdatingHigh = false;
    private boolean isApplyingPreset = false;

    private SharedPreferences prefs;

    private static class ToneTemplate {
        final String name;
        int lowDb;
        int midDb;
        int highDb;
        final boolean builtIn;

        ToneTemplate(String name, int lowDb, int midDb, int highDb, boolean builtIn) {
            this.name = name;
            this.lowDb = lowDb;
            this.midDb = midDb;
            this.highDb = highDb;
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

        spinnerPresets = findViewById(R.id.spinner_presets);
        btnSaveTemplate = findViewById(R.id.btn_save_template);
        btnDeleteTemplate = findViewById(R.id.btn_delete_template);

        seekLow = findViewById(R.id.seek_low);
        seekMid = findViewById(R.id.seek_mid);
        seekHigh = findViewById(R.id.seek_high);

        editLowDb = findViewById(R.id.edit_low_db);
        editMidDb = findViewById(R.id.edit_mid_db);
        editHighDb = findViewById(R.id.edit_high_db);

        requestNotificationPermissionIfNeeded();
        loadTemplates();
        setupPresetControls();
        setupBandControls();
        restoreSavedValues();
        startOrUpdateService();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void loadTemplates() {
        templates.clear();
        // Built-in presets
        templates.add(new ToneTemplate("Neutral", 0, 0, 0, true));
        templates.add(new ToneTemplate("BassBoost", 10, 0, 0, true));
        templates.add(new ToneTemplate("VocalBoost", 0, 10, 0, true));
        templates.add(new ToneTemplate("TrebleBoost", 0, 0, 10, true));

        // Load user-saved custom templates
        String rawJson = prefs.getString(KEY_CUSTOM_TEMPLATES, "[]");
        try {
            JSONArray array = new JSONArray(rawJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String name = obj.optString("name", "").trim();
                if (!name.isEmpty()) {
                    int low = clampDb(obj.optInt("low", 0));
                    int mid = clampDb(obj.optInt("mid", 0));
                    int high = clampDb(obj.optInt("high", 0));
                    templates.add(new ToneTemplate(name, low, mid, high, false));
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
                    obj.put("low", t.lowDb);
                    obj.put("mid", t.midDb);
                    obj.put("high", t.highDb);
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
                    applyTemplateValues(selected.lowDb, selected.midDb, selected.highDb);
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
                        Toast.makeText(this, "Template name cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int currentLow = clampDb(seekLow.getProgress() - OFFSET);
                    int currentMid = clampDb(seekMid.getProgress() - OFFSET);
                    int currentHigh = clampDb(seekHigh.getProgress() - OFFSET);

                    // Check if template with same name exists
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
                            Toast.makeText(this, "Cannot overwrite built-in preset", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        existing.lowDb = currentLow;
                        existing.midDb = currentMid;
                        existing.highDb = currentHigh;
                    } else {
                        templates.add(new ToneTemplate(name, currentLow, currentMid, currentHigh, false));
                        existingIndex = templates.size() - 1;
                    }

                    saveCustomTemplates();
                    refreshTemplateNames();
                    isApplyingPreset = true;
                    spinnerPresets.setSelection(existingIndex);
                    prefs.edit().putString(KEY_SELECTED_PRESET, templates.get(existingIndex).name).apply();
                    isApplyingPreset = false;
                    Toast.makeText(this, "Template saved: " + name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void deleteSelectedTemplate() {
        int pos = spinnerPresets.getSelectedItemPosition();
        if (pos < 0 || pos >= templates.size()) return;

        ToneTemplate selected = templates.get(pos);
        if (selected.builtIn) {
            Toast.makeText(this, "Cannot delete built-in preset", Toast.LENGTH_SHORT).show();
            return;
        }

        String removedName = selected.name;
        templates.remove(pos);
        saveCustomTemplates();
        refreshTemplateNames();

        int newPos = Math.max(0, Math.min(pos, templates.size() - 1));
        isApplyingPreset = true;
        spinnerPresets.setSelection(newPos);
        isApplyingPreset = false;

        ToneTemplate fallback = templates.get(newPos);
        prefs.edit().putString(KEY_SELECTED_PRESET, fallback.name).apply();
        applyTemplateValues(fallback.lowDb, fallback.midDb, fallback.highDb);

        Toast.makeText(this, "Template deleted: " + removedName, Toast.LENGTH_SHORT).show();
    }

    private void applyTemplateValues(int low, int mid, int high) {
        int cLow = clampDb(low);
        int cMid = clampDb(mid);
        int cHigh = clampDb(high);

        isUpdatingLow = true;
        seekLow.setProgress(cLow + OFFSET);
        String lowText = String.valueOf(cLow);
        editLowDb.setText(lowText);
        editLowDb.setSelection(lowText.length());
        isUpdatingLow = false;

        isUpdatingMid = true;
        seekMid.setProgress(cMid + OFFSET);
        String midText = String.valueOf(cMid);
        editMidDb.setText(midText);
        editMidDb.setSelection(midText.length());
        isUpdatingMid = false;

        isUpdatingHigh = true;
        seekHigh.setProgress(cHigh + OFFSET);
        String highText = String.valueOf(cHigh);
        editHighDb.setText(highText);
        editHighDb.setSelection(highText.length());
        isUpdatingHigh = false;

        prefs.edit()
                .putInt(KEY_LOW_DB, cLow)
                .putInt(KEY_MID_DB, cMid)
                .putInt(KEY_HIGH_DB, cHigh)
                .apply();

        startOrUpdateService();
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

        int savedLow = clampDb(prefs.getInt(KEY_LOW_DB, 0));
        int savedMid = clampDb(prefs.getInt(KEY_MID_DB, 0));
        int savedHigh = clampDb(prefs.getInt(KEY_HIGH_DB, 0));

        isUpdatingLow = true;
        seekLow.setProgress(savedLow + OFFSET);
        String lowText = String.valueOf(savedLow);
        editLowDb.setText(lowText);
        editLowDb.setSelection(lowText.length());
        isUpdatingLow = false;

        isUpdatingMid = true;
        seekMid.setProgress(savedMid + OFFSET);
        String midText = String.valueOf(savedMid);
        editMidDb.setText(midText);
        editMidDb.setSelection(midText.length());
        isUpdatingMid = false;

        isUpdatingHigh = true;
        seekHigh.setProgress(savedHigh + OFFSET);
        String highText = String.valueOf(savedHigh);
        editHighDb.setText(highText);
        editHighDb.setSelection(highText.length());
        isUpdatingHigh = false;
    }

    private void saveAndApplyBand(String key, int dbValue) {
        prefs.edit().putInt(key, dbValue).apply();
        startOrUpdateService();
    }

    private void startOrUpdateService() {
        Intent serviceIntent = new Intent(this, ToneControlService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {}
    }

    private void setupBandControls() {
        // Low
        seekLow.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int dbValue = clampDb(progress - OFFSET);
                saveAndApplyBand(KEY_LOW_DB, dbValue);
                if (!isUpdatingLow) {
                    isUpdatingLow = true;
                    String text = String.valueOf(dbValue);
                    editLowDb.setText(text);
                    editLowDb.setSelection(text.length());
                    isUpdatingLow = false;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        editLowDb.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isUpdatingLow) return;
                Integer parsed = parseDbInput(s.toString());
                if (parsed != null) {
                    int clamped = clampDb(parsed);
                    isUpdatingLow = true;
                    seekLow.setProgress(clamped + OFFSET);
                    saveAndApplyBand(KEY_LOW_DB, clamped);
                    isUpdatingLow = false;
                }
            }
        });

        editLowDb.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                normalizeInputField(editLowDb, seekLow, KEY_LOW_DB);
                editLowDb.clearFocus();
            }
            return false;
        });

        editLowDb.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                normalizeInputField(editLowDb, seekLow, KEY_LOW_DB);
            }
        });

        // Mid
        seekMid.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int dbValue = clampDb(progress - OFFSET);
                saveAndApplyBand(KEY_MID_DB, dbValue);
                if (!isUpdatingMid) {
                    isUpdatingMid = true;
                    String text = String.valueOf(dbValue);
                    editMidDb.setText(text);
                    editMidDb.setSelection(text.length());
                    isUpdatingMid = false;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        editMidDb.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isUpdatingMid) return;
                Integer parsed = parseDbInput(s.toString());
                if (parsed != null) {
                    int clamped = clampDb(parsed);
                    isUpdatingMid = true;
                    seekMid.setProgress(clamped + OFFSET);
                    saveAndApplyBand(KEY_MID_DB, clamped);
                    isUpdatingMid = false;
                }
            }
        });

        editMidDb.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                normalizeInputField(editMidDb, seekMid, KEY_MID_DB);
                editMidDb.clearFocus();
            }
            return false;
        });

        editMidDb.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                normalizeInputField(editMidDb, seekMid, KEY_MID_DB);
            }
        });

        // High
        seekHigh.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int dbValue = clampDb(progress - OFFSET);
                saveAndApplyBand(KEY_HIGH_DB, dbValue);
                if (!isUpdatingHigh) {
                    isUpdatingHigh = true;
                    String text = String.valueOf(dbValue);
                    editHighDb.setText(text);
                    editHighDb.setSelection(text.length());
                    isUpdatingHigh = false;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        editHighDb.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isUpdatingHigh) return;
                Integer parsed = parseDbInput(s.toString());
                if (parsed != null) {
                    int clamped = clampDb(parsed);
                    isUpdatingHigh = true;
                    seekHigh.setProgress(clamped + OFFSET);
                    saveAndApplyBand(KEY_HIGH_DB, clamped);
                    isUpdatingHigh = false;
                }
            }
        });

        editHighDb.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                normalizeInputField(editHighDb, seekHigh, KEY_HIGH_DB);
                editHighDb.clearFocus();
            }
            return false;
        });

        editHighDb.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                normalizeInputField(editHighDb, seekHigh, KEY_HIGH_DB);
            }
        });
    }

    private void normalizeInputField(EditText editText, SeekBar seekBar, String key) {
        Integer parsed = parseDbInput(editText.getText().toString());
        int dbValue = (parsed != null) ? clampDb(parsed) : (seekBar.getProgress() - OFFSET);
        seekBar.setProgress(dbValue + OFFSET);
        String formatted = String.valueOf(dbValue);
        editText.setText(formatted);
        editText.setSelection(formatted.length());
        saveAndApplyBand(key, dbValue);
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
