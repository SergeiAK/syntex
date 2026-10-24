package android.util;

import java.util.Arrays;

/**
 * A working LongSparseArray for JVM tests, for the same reason as {@link SparseArray}: the android.jar
 * stub answers every call with a default, and MatroskaExtractor keeps its chapters in one — under the
 * stub they are put nowhere and never reach the track format. Keys sorted, as on the device.
 */
public class LongSparseArray<E> {

    private long[] keys = new long[8];
    private Object[] values = new Object[8];
    private int size;

    public LongSparseArray() {
    }

    public LongSparseArray(int initialCapacity) {
    }

    public E get(long key) {
        return get(key, null);
    }

    @SuppressWarnings("unchecked")
    public E get(long key, E valueIfKeyNotFound) {
        final int i = Arrays.binarySearch(keys, 0, size, key);
        return i < 0 ? valueIfKeyNotFound : (E) values[i];
    }

    public void put(long key, E value) {
        int i = Arrays.binarySearch(keys, 0, size, key);
        if (i >= 0) {
            values[i] = value;
            return;
        }
        i = ~i;
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        System.arraycopy(keys, i, keys, i + 1, size - i);
        System.arraycopy(values, i, values, i + 1, size - i);
        keys[i] = key;
        values[i] = value;
        size++;
    }

    public void append(long key, E value) {
        put(key, value);
    }

    public void remove(long key) {
        delete(key);
    }

    public void delete(long key) {
        final int i = Arrays.binarySearch(keys, 0, size, key);
        if (i >= 0) removeAt(i);
    }

    public void removeAt(int index) {
        System.arraycopy(keys, index + 1, keys, index, size - index - 1);
        System.arraycopy(values, index + 1, values, index, size - index - 1);
        size--;
        values[size] = null;
    }

    public int size() {
        return size;
    }

    public long keyAt(int index) {
        return keys[index];
    }

    @SuppressWarnings("unchecked")
    public E valueAt(int index) {
        return (E) values[index];
    }

    public void setValueAt(int index, E value) {
        values[index] = value;
    }

    public int indexOfKey(long key) {
        final int i = Arrays.binarySearch(keys, 0, size, key);
        return i < 0 ? -1 : i;
    }

    public void clear() {
        Arrays.fill(values, 0, size, null);
        size = 0;
    }
}
