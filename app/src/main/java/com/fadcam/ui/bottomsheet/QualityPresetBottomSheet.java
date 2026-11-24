package com.fadcam.ui.bottomsheet;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.StreamCustomSettings;
import com.fadcam.streaming.model.StreamQuality;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet for selecting stream quality preset.
 * Allows user to choose between LOW, MEDIUM, HIGH, and ULTRA quality.
 */
public class QualityPresetBottomSheet extends BottomSheetDialogFragment {
    
    private LinearLayout rowLow, rowMedium, rowHigh, rowUltra, rowCustom;
    private ImageView checkLow, checkMedium, checkHigh, checkUltra, checkCustom;
    private TextView textCustomSummary;
    
    private StreamQuality.Preset currentPreset;
    private StreamQuality.Preset selectedPreset;
    private OnQualitySelectedListener listener;
    
    /**
     * Factory method to create new instance.
     */
    public static QualityPresetBottomSheet newInstance(StreamQuality.Preset currentPreset) {
        QualityPresetBottomSheet fragment = new QualityPresetBottomSheet();
        Bundle args = new Bundle();
        args.putString("current_preset", currentPreset.name());
        fragment.setArguments(args);
        return fragment;
    }
    
    /**
     * Callback interface for quality selection.
     */
    public interface OnQualitySelectedListener {
        void onQualitySelected(StreamQuality.Preset preset);
    }
    
    /**
     * Set callback listener.
     */
    public void setOnQualitySelectedListener(OnQualitySelectedListener listener) {
        this.listener = listener;
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_quality_preset, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Get current preset from arguments
        Bundle args = getArguments();
        if (args != null) {
            String presetName = args.getString("current_preset", "HIGH");
            currentPreset = StreamQuality.Preset.valueOf(presetName);
        } else {
            currentPreset = StreamQuality.Preset.HIGH;
        }
        
        selectedPreset = currentPreset;
        
        // Initialize views
        rowLow = view.findViewById(R.id.rowLow);
        rowMedium = view.findViewById(R.id.rowMedium);
        rowHigh = view.findViewById(R.id.rowHigh);
        rowUltra = view.findViewById(R.id.rowUltra);
        checkLow = view.findViewById(R.id.checkLow);
        checkMedium = view.findViewById(R.id.checkMedium);
        checkHigh = view.findViewById(R.id.checkHigh);
        checkUltra = view.findViewById(R.id.checkUltra);
        rowCustom = view.findViewById(R.id.rowCustom);
        checkCustom = view.findViewById(R.id.checkCustom);
        textCustomSummary = view.findViewById(R.id.textCustomSummary);
        if (textCustomSummary != null) {
            textCustomSummary.setText(StreamCustomSettings.summary(requireContext()));
        }
        
        // Show current selection
        updateSelection();
        
        // Set up row click listeners
        rowLow.setOnClickListener(v -> selectPreset(StreamQuality.Preset.LOW));
        rowMedium.setOnClickListener(v -> selectPreset(StreamQuality.Preset.MEDIUM));
        rowHigh.setOnClickListener(v -> selectPreset(StreamQuality.Preset.HIGH));
        rowUltra.setOnClickListener(v -> selectPreset(StreamQuality.Preset.ULTRA));
        if (rowCustom != null) {
            rowCustom.setOnClickListener(v -> showCustomDialog());
        }
    }
    
    private void selectPreset(StreamQuality.Preset preset) {
        selectedPreset = preset;
        updateSelection();
        
        // Check if preset changed
        if (selectedPreset == currentPreset) {
            Toast.makeText(getContext(), "Quality already set to " + selectedPreset.getDisplayName(), Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }
        
        // Apply new preset
        if (listener != null) {
            listener.onQualitySelected(selectedPreset);
        }
        
        // Update in RemoteStreamManager
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        manager.setStreamQuality(selectedPreset, requireContext());
        
        // Only show "restart recording" if recording is active
        if (manager.isRecording()) {
            Toast.makeText(getContext(), 
                "Quality updated to " + selectedPreset.getDisplayName() + ". Restart recording to apply.", 
                Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getContext(), 
                "Quality updated to " + selectedPreset.getDisplayName() + ".", 
                Toast.LENGTH_SHORT).show();
        }
        
        dismiss();
    }
    
    /** Manual stream settings: resolution, FPS cap and bitrate. */
    private void showCustomDialog() {
        final android.content.Context ctx = requireContext();
        final float density = ctx.getResources().getDisplayMetrics().density;
        final int pad = (int) (20 * density);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView resLabel = new TextView(ctx);
        resLabel.setText("Resolution");
        box.addView(resLabel);
        final String[] resNames = {"Same as recording", "640x360", "854x480", "1280x720", "1920x1080"};
        final int[][] resValues = {{0, 0}, {640, 360}, {854, 480}, {1280, 720}, {1920, 1080}};
        final Spinner resSpinner = new Spinner(ctx);
        resSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, resNames));
        int curW = StreamCustomSettings.getWidth(ctx);
        int curH = StreamCustomSettings.getHeight(ctx);
        for (int i = 0; i < resValues.length; i++) {
            if (resValues[i][0] == curW && resValues[i][1] == curH) {
                resSpinner.setSelection(i);
                break;
            }
        }
        box.addView(resSpinner);

        TextView fpsLabel = new TextView(ctx);
        fpsLabel.setText("FPS cap (" + StreamCustomSettings.MIN_FPS + "-" + StreamCustomSettings.MAX_FPS + ")");
        fpsLabel.setPadding(0, pad / 2, 0, 0);
        box.addView(fpsLabel);
        final EditText fpsInput = new EditText(ctx);
        fpsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        fpsInput.setText(String.valueOf(StreamCustomSettings.getFps(ctx)));
        box.addView(fpsInput);

        TextView brLabel = new TextView(ctx);
        brLabel.setText("Bitrate in kbps (" + StreamCustomSettings.MIN_BITRATE_KBPS + "-"
                + StreamCustomSettings.MAX_BITRATE_KBPS + ")");
        brLabel.setPadding(0, pad / 2, 0, 0);
        box.addView(brLabel);
        final EditText bitrateInput = new EditText(ctx);
        bitrateInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        bitrateInput.setText(String.valueOf(StreamCustomSettings.getBitrateBps(ctx) / 1000));
        box.addView(bitrateInput);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.stream_custom_title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int fps;
                    int kbps;
                    try {
                        fps = Integer.parseInt(fpsInput.getText().toString().trim());
                        kbps = Integer.parseInt(bitrateInput.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(ctx, "Enter valid numbers", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (fps < StreamCustomSettings.MIN_FPS || fps > StreamCustomSettings.MAX_FPS
                            || kbps < StreamCustomSettings.MIN_BITRATE_KBPS
                            || kbps > StreamCustomSettings.MAX_BITRATE_KBPS) {
                        Toast.makeText(ctx, "Value out of range", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int[] res = resValues[Math.max(0, resSpinner.getSelectedItemPosition())];
                    StreamCustomSettings.save(ctx, kbps, fps, res[0], res[1]);
                    RemoteStreamManager.getInstance().setStreamQuality(StreamQuality.Preset.CUSTOM, ctx);
                    if (listener != null) listener.onQualitySelected(StreamQuality.Preset.CUSTOM);
                    Toast.makeText(ctx, "Custom stream settings saved. Restart recording to apply.",
                            Toast.LENGTH_LONG).show();
                    dismiss();
                })
                .show();
    }

    private void updateSelection() {
        // Hide all checkmarks
        checkLow.setVisibility(View.GONE);
        checkMedium.setVisibility(View.GONE);
        checkHigh.setVisibility(View.GONE);
        checkUltra.setVisibility(View.GONE);
        if (checkCustom != null) checkCustom.setVisibility(View.GONE);
        
        // Show selected checkmark
        switch (selectedPreset) {
            case LOW:
                checkLow.setVisibility(View.VISIBLE);
                break;
            case MEDIUM:
                checkMedium.setVisibility(View.VISIBLE);
                break;
            case HIGH:
                checkHigh.setVisibility(View.VISIBLE);
                break;
            case ULTRA:
                checkUltra.setVisibility(View.VISIBLE);
                break;
            case CUSTOM:
                if (checkCustom != null) checkCustom.setVisibility(View.VISIBLE);
                break;
        }
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            com.fadcam.util.SystemBarUtil.setNavigationBarColor(dialog.getWindow(), android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                dialog.getWindow().setNavigationBarContrastEnforced(false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                com.fadcam.util.SystemBarUtil.setNavigationBarIconsLight(dialog.getWindow(), false);
            }
        }
        return dialog;
    }
    
}
