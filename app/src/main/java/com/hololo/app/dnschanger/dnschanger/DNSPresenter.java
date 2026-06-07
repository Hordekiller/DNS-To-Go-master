package com.hololo.app.dnschanger.dnschanger;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.utils.RxBus;
import com.hololo.app.dnschanger.utils.event.GetServiceInfo;
import com.hololo.app.dnschanger.utils.event.ServiceInfo;
import com.hololo.app.dnschanger.utils.event.StartEvent;
import com.hololo.app.dnschanger.utils.event.StopEvent;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;

class DNSPresenter {

    static final int SERVICE_OPEN = 1;
    static final int SERVICE_CLOSE = 0;

    private final IDNSView view;
    private final RxBus rxBus;
    private final Context context;
    private Disposable subscriber;

    @Inject
    public DNSPresenter(IDNSView view, RxBus rxBus, Context context) {
        this.view = view;
        this.rxBus = rxBus;
        this.context = context;

        subscribe();
    }

    private void subscribe() {
        subscriber = rxBus.getEvents().subscribe(o -> {
            if (o instanceof StartEvent) {
                view.changeStatus(SERVICE_OPEN);
            } else if (o instanceof StopEvent) {
                view.changeStatus(SERVICE_CLOSE);
            } else if (o instanceof ServiceInfo serviceInfo) {
                view.setServiceInfo(serviceInfo.getModel());
            }
        });
    }

    void onDestroy() {
        if (subscriber != null) {
            subscriber.dispose();
        }
    }

    void stopService() {
        rxBus.sendEvent(new StopEvent());
    }

    void startService(DNSModel dnsModel) {
        Intent intent = new Intent(context, DNSService.class);
        intent.putExtra(DNSService.DNS_MODEL, dnsModel);

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

    public io.reactivex.Observable<Object> getEvents() {
        return rxBus.getEvents();
    }
}
