package android.util;

/** A working SparseBooleanArray for JVM tests; see {@link SparseArray}. */
public class SparseBooleanArray {

    private final SparseArray<Boolean> map = new SparseArray<>();

    public boolean get(int key) {
        return get(key, false);
    }

    public boolean get(int key, boolean valueIfKeyNotFound) {
        return map.get(key, valueIfKeyNotFound);
    }

    public void put(int key, boolean value) {
        map.put(key, value);
    }

    public void delete(int key) {
        map.delete(key);
    }

    public int size() {
        return map.size();
    }

    public int keyAt(int index) {
        return map.keyAt(index);
    }

    public boolean valueAt(int index) {
        return map.valueAt(index);
    }

    public int indexOfKey(int key) {
        return map.indexOfKey(key);
    }

    public void clear() {
        map.clear();
    }
}
