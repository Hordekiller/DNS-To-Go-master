package com.hololo.app.dnschanger.settings;

import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.content.Context;
import android.content.SharedPreferences;
import com.hololo.app.dnschanger.utils.locale.LocaleHelper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.MenuItem;
import android.widget.FrameLayout;

import com.hololo.app.dnschanger.R;

import butterknife.BindView;
import butterknife.ButterKnife;


public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.fragment_container)
    FrameLayout fragmentContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ButterKnife.bind(this);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        getFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new SettingsFragment())
                .commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                break;
        }
        return super.onOptionsItemSelected(item);
    }


    public static class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.preference_layout);
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key.equals("language")) {
                String lang = sharedPreferences.getString(key, "en");
                LocaleHelper.setLocale(getActivity(), lang);
                getActivity().recreate();
            }
        }
    }

}