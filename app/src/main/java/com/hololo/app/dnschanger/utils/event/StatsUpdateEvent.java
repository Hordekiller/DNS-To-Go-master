package com.hololo.app.dnschanger.utils.event;

public class StatsUpdateEvent {
    public final long total;
    public final long blocked;

    public StatsUpdateEvent(long total, long blocked) {
        this.total = total;
        this.blocked = blocked;
    }
}
