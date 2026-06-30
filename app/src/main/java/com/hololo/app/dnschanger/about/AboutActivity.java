package com.hololo.app.dnschanger.about;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.MenuItem;
import android.widget.TextView;

import com.hololo.app.dnschanger.BuildConfig;
import com.hololo.app.dnschanger.R;
import com.hololo.app.dnschanger.utils.locale.LocaleHelper;
import com.google.android.material.card.MaterialCardView;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    private Toolbar toolbar;
    private TextView versionText;
    private MaterialCardView githubCard;
    private MaterialCardView emailCard;
    private MaterialCardView websiteCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        toolbar = findViewById(R.id.toolbar);
        versionText = findViewById(R.id.versionText);
        githubCard = findViewById(R.id.githubCard);
        emailCard = findViewById(R.id.emailCard);
        websiteCard = findViewById(R.id.websiteCard);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }
        
        versionText.setText("Version " + BuildConfig.VERSION_NAME);

        githubCard.setOnClickListener(v -> openUrl("https://github.com/Hordekiller"));
        emailCard.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:info@catus.ir"));
            intent.putExtra(Intent.EXTRA_SUBJECT, "Star DNS Support");
            startActivity(Intent.createChooser(intent, getString(R.string.send_mail)));
        });
        websiteCard.setOnClickListener(v -> openUrl("https://catus.ir/"));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        startActivity(intent);
    }
}
