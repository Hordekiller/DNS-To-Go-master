package com.hololo.app.dnschanger.about;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import android.view.MenuItem;
import android.widget.TextView;

import com.hololo.app.dnschanger.BuildConfig;
import com.hololo.app.dnschanger.R;
import com.hololo.app.dnschanger.utils.locale.LocaleHelper;
import com.google.android.material.card.MaterialCardView;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.versionText)
    TextView versionText;
    @BindView(R.id.githubCard)
    MaterialCardView githubCard;
    @BindView(R.id.emailCard)
    MaterialCardView emailCard;
    @BindView(R.id.websiteCard)
    MaterialCardView websiteCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        ButterKnife.bind(this);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }
        
        versionText.setText("Version " + BuildConfig.VERSION_NAME);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @OnClick(R.id.githubCard)
    public void onGithubClick() {
        openUrl("https://github.com/Hordekiller");
    }

    @OnClick(R.id.emailCard)
    public void onEmailClick() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:info@catus.ir"));
        intent.putExtra(Intent.EXTRA_SUBJECT, "Star DNS Support");
        startActivity(Intent.createChooser(intent, getString(R.string.send_mail)));
    }

    @OnClick(R.id.websiteCard)
    public void onWebsiteClick() {
        openUrl("https://catus.ir/");
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        startActivity(intent);
    }
}
