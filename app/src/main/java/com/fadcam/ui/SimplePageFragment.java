package com.fadcam.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/** Base for settings sub-pages whose body is built in code (see SettingsPageUi). */
public abstract class SimplePageFragment extends Fragment {

    protected abstract String pageTitle();

    protected abstract void buildContent(@NonNull Context ctx, @NonNull LinearLayout content);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_simple_page, container, false);
        TextView title = view.findViewById(R.id.page_title);
        if (title != null) title.setText(pageTitle());
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> {
                if (getActivity() != null) OverlayNavUtil.dismiss(requireActivity());
            });
        }
        LinearLayout content = view.findViewById(R.id.page_content);
        buildContent(requireContext(), content);
        return view;
    }
}
