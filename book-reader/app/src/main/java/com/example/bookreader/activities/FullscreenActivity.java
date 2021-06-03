package com.example.bookreader.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.widget.Toolbar;

import com.github.axet.androidlibrary.activities.AppCompatFullscreenThemeActivity;
import com.example.bookreader.R;
import com.example.bookreader.app.BookApplication;

import java.util.List;

public class FullscreenActivity extends AppCompatFullscreenThemeActivity {
    public Toolbar toolbar;

    public interface FullscreenListener {
        void onFullscreenChanged(boolean f);

        void onUserInteraction();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_bar_main);
        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public int getAppTheme() {
        return BookApplication.getTheme(this, R.style.AppThemeLight_NoActionBar, R.style.AppThemeDark_NoActionBar);
    }

    @Override
    public int getAppThemePopup() {
        return BookApplication.getTheme(this, R.style.AppThemeLight_PopupOverlay, R.style.AppThemeDark_PopupOverlay);
    }

    @SuppressLint({"InlinedApi", "RestrictedApi"})
    @Override
    public void setFullscreen(boolean b) {
        super.setFullscreen(b);
        FragmentManager fm = getSupportFragmentManager();
        List<Fragment> ff = fm.getFragments();
        if (ff != null) {
            for (Fragment f : ff) {
                if (f instanceof FullscreenActivity.FullscreenListener)
                    ((FullscreenActivity.FullscreenListener) f).onFullscreenChanged(b);
            }
        }
    }

    @Override
    public void hideSystemUI() {
        super.hideSystemUI();
        setFitsSystemWindows(this, false);
    }

    @Override
    public void showSystemUI() {
        super.showSystemUI();
        setFitsSystemWindows(this, true);
    }

    @SuppressLint("RestrictedApi")
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        FragmentManager fm = getSupportFragmentManager();
        List<Fragment> ff = fm.getFragments();
        if (ff != null) {
            for (Fragment f : ff) {
                if (f instanceof FullscreenListener)
                    ((FullscreenListener) f).onUserInteraction();
            }
        }
    }
}
