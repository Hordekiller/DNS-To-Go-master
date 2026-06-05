package com.hololo.app.dnschanger.model;

import androidx.annotation.Keep;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

@Keep
public class DNSModelJSON {

    @SerializedName("modelList")
    @Expose
    private List<DNSModel> modelList = new java.util.ArrayList<>();

    public DNSModelJSON() {
    }

    public List<DNSModel> getModelList() {
        return modelList;
    }

    public void setModelList(List<DNSModel> modelList) {
        this.modelList = modelList;
    }
}
