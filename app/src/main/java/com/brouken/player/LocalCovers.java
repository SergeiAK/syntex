package com.brouken.player;

import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * The cover of a Matroska file on the device itself, ahead of Glide's own frame of it. A file without
 * one fails here, and Glide goes on to the next loader for the same address - the frame, as before.
 * The network's files have theirs in {@link NetworkThumbs}, on the same reads as their frames.
 */
final class LocalCovers {

    private LocalCovers() {
    }

    static final class Factory implements ModelLoaderFactory<Uri, Bitmap> {

        @NonNull
        @Override
        public ModelLoader<Uri, Bitmap> build(@NonNull final MultiModelLoaderFactory multi) {
            return new Loader();
        }

        @Override
        public void teardown() {
        }
    }

    private static final class Loader implements ModelLoader<Uri, Bitmap> {

        @Nullable
        @Override
        public LoadData<Bitmap> buildLoadData(@NonNull final Uri uri, final int width,
                                              final int height, @NonNull final Options options) {
            return new LoadData<>(new ObjectKey("cover " + uri + " " + width + "x" + height),
                    new Fetcher(uri, width, height));
        }

        @Override
        public boolean handles(@NonNull final Uri uri) {
            return "file".equals(uri.getScheme()) && MkvCover.speaks(uri.getLastPathSegment());
        }
    }

    private static final class Fetcher implements DataFetcher<Bitmap> {

        private final Uri uri;
        private final int width;
        private final int height;

        Fetcher(final Uri uri, final int width, final int height) {
            this.uri = uri;
            this.width = Math.max(1, Math.min(width, 640));
            this.height = Math.max(1, Math.min(height, 360));
        }

        @Override
        public void loadData(@NonNull final Priority priority,
                             @NonNull final DataCallback<? super Bitmap> callback) {
            final String path = uri.getPath();
            if (path == null) {
                callback.onLoadFailed(new IOException("no path: " + uri));
                return;
            }
            try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
                final MkvCover.Input input = (position, buffer, offset, size) -> {
                    file.seek(position);
                    return file.read(buffer, offset, size);
                };
                final MkvCover.Found found = MkvCover.find(input);
                final Bitmap cover = found == null ? null : NetworkThumbs.decodePicture(
                        NetworkThumbs.readFully(input, found.offset, found.size), width, height);
                if (cover == null) {
                    callback.onLoadFailed(new IOException("no cover in " + uri));
                } else {
                    callback.onDataReady(cover);
                }
            } catch (final IOException e) {
                callback.onLoadFailed(e);
            }
        }

        @Override
        public void cleanup() {
        }

        @Override
        public void cancel() {
        }

        @NonNull
        @Override
        public Class<Bitmap> getDataClass() {
            return Bitmap.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }
}
