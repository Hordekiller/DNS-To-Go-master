package com.hololo.app.dnschanger.model;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.Keep;
import com.google.gson.annotations.SerializedName;
import java.util.List;

@Keep
public class DNSModel implements Parcelable {
    @SerializedName("name")
    private String name = "";
    @SerializedName("firstDNS")
    private String firstDns = "";
    @SerializedName("secondDNS")
    private String secondDns = "";
    @SerializedName("ipv6")
    private String ipv6 = "";
    @SerializedName("category")
    private String category = "";
    @SerializedName("description")
    private String description = "";
    @SerializedName("features")
    private List<String> features = new java.util.ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFirstDns() { return firstDns; }
    public void setFirstDns(String firstDns) { this.firstDns = firstDns; }

    public String getSecondDns() { return secondDns; }
    public void setSecondDns(String secondDns) { this.secondDns = secondDns; }

    public String getIpv6() { return ipv6; }
    public void setIpv6(String ipv6) { this.ipv6 = ipv6; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features; }

    public DNSModel() {}

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeString(this.firstDns);
        dest.writeString(this.secondDns);
        dest.writeString(this.ipv6);
        dest.writeString(this.category);
        dest.writeString(this.description);
        dest.writeStringList(this.features);
    }

    protected DNSModel(Parcel in) {
        this.name = in.readString();
        this.firstDns = in.readString();
        this.secondDns = in.readString();
        this.ipv6 = in.readString();
        this.category = in.readString();
        this.description = in.readString();
        this.features = in.createStringArrayList();
    }

    public static final Creator<DNSModel> CREATOR = new Creator<DNSModel>() {
        @Override
        public DNSModel createFromParcel(Parcel source) { return new DNSModel(source); }
        @Override
        public DNSModel[] newArray(int size) { return new DNSModel[size]; }
    };
}
