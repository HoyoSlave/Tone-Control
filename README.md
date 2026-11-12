# Tone Control (Android)

Minimalist stock Android **Tone Control** application in English featuring:
- **Stock Android UI** (`@android:style/Theme.DeviceDefault`) with no third-party styling.
- **3 Sliders (`-25 dB` to `+25 dB`)**:
  - **Low**: `-25 dB` to `+25 dB`
  - **Mid**: `-25 dB` to `+25 dB`
  - **High**: `-25 dB` to `+25 dB`
- **Built-in Presets & Custom Templates**:
  - Built-in presets: `Neutral` (0, 0, 0), `BassBoost` (Low +10), `VocalBoost` (Mid +10), `TrebleBoost` (High +10).
  - **Save** button to add or update custom templates and **Delete** button to remove custom templates.
- **Keyboard Input Support**: Direct numeric keyboard entry (`EditText`) synced two-way with each `SeekBar`.
- **Persistent State & Background Execution**: Saves Low, Mid, High settings and templates in `SharedPreferences` and runs a foreground audio service (`ToneControlService`) so the tone control remains active even when the app is closed.
- **GitHub Actions CI**: `.github/workflows/build.yml` runs `assembleRelease` automatically on every `push` and uploads the built APK artifact.
