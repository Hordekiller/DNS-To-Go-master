package com.hololo.app.dnschanger;

import android.app.Application;

import com.hololo.app.dnschanger.di.component.ApplicationComponent;
import com.hololo.app.dnschanger.di.component.DaggerApplicationComponent;
import com.hololo.app.dnschanger.di.module.ApplicationModule;

import android.content.Context;
import com.hololo.app.dnschanger.utils.locale.LocaleHelper;
import timber.log.Timber;

public class DNSChangerApp extends Application {
    private static ApplicationComponent applicationComponent;

    public static ApplicationComponent getApplicationComponent() {
        return applicationComponent;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.onAttach(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();

        //di
        applicationComponent = DaggerApplicationComponent.builder()
                .applicationModule(new ApplicationModule(this))
                .build();


        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
        
        // Plant a tree that saves logs to LogManager
        Timber.plant(new Timber.Tree() {
            @Override
            protected void log(int priority, String tag, String message, Throwable t) {
                String level = "INFO";
                switch (priority) {
                    case android.util.Log.VERBOSE: level = "VERBOSE"; break;
                    case android.util.Log.DEBUG: level = "DEBUG"; break;
                    case android.util.Log.INFO: level = "INFO"; break;
                    case android.util.Log.WARN: level = "WARN"; break;
                    case android.util.Log.ERROR: level = "ERROR"; break;
                    case android.util.Log.ASSERT: level = "ASSERT"; break;
                }
                
                String logMessage = level + "/" + (tag != null ? tag : "App") + ": " + message;
                if (t != null) {
                    logMessage += "\n" + android.util.Log.getStackTraceString(t);
                }
                
                com.hololo.app.dnschanger.utils.LogManager.addLog(getApplicationContext(), logMessage);
            }
        });
    }
}
