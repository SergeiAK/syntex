package android.os;

import java.util.HashMap;
import java.util.Map;

/**
 * A working Bundle for JVM tests: the android.jar stub answers get() with null (returnDefaultValues), so
 * a request read through it is always empty. Only what PlaylistRequest uses — any value under a key, read
 * back as it was put; see android.util.SparseArray for the same trick.
 */
public class Bundle implements Parcelable {

    private final Map<String, Object> values = new HashMap<>();

    public Object get(String key) {
        return values.get(key);
    }

    /** Any value, typed or not: the senders this stands in for put whatever they like. */
    public Bundle put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }
}
