package com.hololo.app.dnschanger.dnschanger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.utils.RxBus;
import com.hololo.app.dnschanger.utils.event.GetServiceInfo;
import com.hololo.app.dnschanger.utils.event.ServiceInfo;
import com.hololo.app.dnschanger.utils.event.StartEvent;
import com.hololo.app.dnschanger.utils.event.StopEvent;

import javax.inject.Inject;

import io.reactivex.functions.Consumer;

import static com.hololo.app.dnschanger.dnschanger.DNSService.DNS_MODEL;

import android.preference.PreferenceManager;

class DNSPresenter {

    static final int SERVICE_OPEN = 1;
    static final int SERVICE_CLOSE = 0;

    private IDNSView view;
    private RxBus rxBus;
    private Context context;

    @Inject
    public DNSPresenter(IDNSView view, RxBus rxBus, Context context) {
        this.view = view;
        this.rxBus = rxBus;
        this.context = context;

        subscribe();
    }

    private void subscribe() {
        rxBus.getEvents().subscribe(new Consumer<Object>() {
            @Override
            public void accept(Object o) throws Exception {
                if (o instanceof StartEvent) {
                    view.changeStatus(SERVICE_OPEN);
                } else if (o instanceof StopEvent) {
                    view.changeStatus(SERVICE_CLOSE);
                } else if (o instanceof ServiceInfo) {
                    view.setServiceInfo(((ServiceInfo) o).getModel());
                }
            }
        });
    }

    void stopService() {
        rxBus.sendEvent(new StopEvent());
    }

    void startService(DNSModel dnsModel) {
        Intent intent = new Intent(context, DNSService.class);
        intent.putExtra(DNS_MODEL, dnsModel);

        view.setServiceInfo(dnsModel);
        ContextCompat.startForegroundService(context, intent);
    }

    public boolean isWorking() {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("isStarted", false);
    }

    void getServiceStatus() {
        if (isWorking()) {
            getServiceInfo();
            view.changeStatus(SERVICE_OPEN);
        } else {
            view.changeStatus(SERVICE_CLOSE);
        }
    }

    public void getServiceInfo() {
        rxBus.sendEvent(new GetServiceInfo());
    }
}