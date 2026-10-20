package com.oplus.ocs.base.common;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Wire-compatible stand-in for the OPPO OCS SDK's feature entry. The capability service writes a
 * list of these by class name ahead of the pen binder, so the name and field order must match for
 * Folio to read past them (see PenWritingFeedback). Kept from R8 in proguard-rules.pro.
 */
public final class Feature implements Parcelable {
    public final String name;
    public final long version;

    private Feature(Parcel in) {
        name = in.readString();
        version = in.readLong();
    }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(name);
        out.writeLong(version);
    }

    public static final Creator<Feature> CREATOR = new Creator<Feature>() {
        @Override public Feature createFromParcel(Parcel in) { return new Feature(in); }
        @Override public Feature[] newArray(int size) { return new Feature[size]; }
    };
}
