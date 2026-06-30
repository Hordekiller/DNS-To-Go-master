package com.hololo.app.dnschanger.model;

import androidx.annotation.Keep;

@Keep
public enum DnsType {
    DOH,
    DOT,
    PLAIN_UDP,
    PLAIN_TCP
}
