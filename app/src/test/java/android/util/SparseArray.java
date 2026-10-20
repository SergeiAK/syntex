package android.util;

import java.util.Arrays;

/**
 * A working SparseArray for JVM tests. The android.jar stub answers every call with a default
 * (returnDefaultValues), so a Media3 extractor that keeps its readers in one — TsExtractor, PsExtractor,
 * H264Reader — silently finds nothing. Keys sorted, as on the device: keyAt/valueAt walk in key order.
 */
public class SparseArray<E> {

    private int[] keys = new int[8];
    private Object[] values = new Object[8];
    private int size;

    public SparseArray() {
    }

    public SparseArray(int initialCapacity) {
    }

    @SuppressWarnings("unchecked")
    public E get(int key) {
        return get(key, null);
    }

    @SuppressWarnings("unchecked")
    public E get(int key, E valueIfKeyNotFound) {
        final int i = Arrays.binarySearch(keys, 0, size, key);
        return i < 0 ? valueIfKeyNotFound : (E) values[i];
    }

    public void put(int key, E value) {
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

    public void append(int key, E value) {
        put(key, value);
    }

    public void remove(int key) {
        delete(key);
    }

    public void delete(int key) {
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

    public int keyAt(int index) {
        return keys[index];
    }

    @SuppressWarnings("unchecked")
    public E valueAt(int index) {
        return (E) values[index];
    }

    public void setValueAt(int index, E value) {
        values[index] = value;
    }

    public int indexOfKey(int key) {
        final int i = Arrays.binarySearch(keys, 0, size, key);
        return i < 0 ? -1 : i;
    }

    public boolean contains(int key) {
        return indexOfKey(key) >= 0;
    }

    public void clear() {
        Arrays.fill(values, 0, size, null);
        size = 0;
    }
}
