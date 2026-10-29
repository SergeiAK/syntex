package com.brouken.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Keeps a subtitle found online beside the video it was found for, as {@code Film.ru.srt}: from then
 * on it is a sidecar like any other (SubtitleUtils.findSubtitles) and the next watch needs no search.
 *
 * <p>Never writes over a file. A name already taken gets the next number in the form the sidecar
 * reader already tells apart — {@code Film.ru2.srt}, shown as "Russian 2" — and a file that already
 * holds exactly these bytes is reported as saved instead of being written twice.
 */
final class SubtitleSave {

    /** How the save came out, for the one line the viewer is told. */
    static final class Result {
        /** The file name written, or the one found already holding the same subtitle. */
        final String name;
        final boolean already;

        Result(final String name, final boolean already) {
            this.name = name;
            this.already = already;
        }
    }

    /** Sidecars past this many in one language are not a real case, and the loop needs an end. */
    private static final int MAX_NUMBER = 99;

    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})([,.])(\\d{3})");

    private SubtitleSave() {
    }

    /**
     * The name to write under: {@code base.lang.srt}, else {@code base.lang2.srt} and on, the first
     * one not in {@code taken}. A machine translation keeps its {@code .auto.} marker, which is what
     * labels it as one when it is read back.
     *
     * @param taken the names already in the folder, compared without case — a share on Windows
     *              would refuse {@code Film.RU.srt} beside {@code Film.ru.srt} anyway
     * @return null when every number up to {@link #MAX_NUMBER} is taken
     */
    @Nullable
    static String freeName(final String base, final String language, final boolean machine,
                           final Set<String> taken) {
        for (int n = 1; n <= MAX_NUMBER; n++) {
            final String name = nameFor(base, language, machine, n);
            if (!taken.contains(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
        return null;
    }

    /** Number 1 is written without a number, as the first file of a language always was. */
    static String nameFor(final String base, final String language, final boolean machine, final int n) {
        return base + (machine ? ".auto." : ".") + language + (n > 1 ? String.valueOf(n) : "") + ".srt";
    }

    /**
     * Moves every timestamp by the offset the viewer set, so the sync they found is what is kept: the
     * offset itself lasts only the session. SRT and WebVTT stamps ({@code 00:01:02,345} and
     * {@code 00:01:02.345}); a stamp moved before zero stops at zero.
     */
    static String shifted(final String text, final long offsetMs) {
        if (offsetMs == 0) {
            return text;
        }
        final Matcher m = TIME.matcher(text);
        final StringBuffer out = new StringBuffer(text.length());
        while (m.find()) {
            final long ms = Long.parseLong(m.group(1)) * 3_600_000L
                    + Long.parseLong(m.group(2)) * 60_000L
                    + Long.parseLong(m.group(3)) * 1_000L
                    + Long.parseLong(m.group(5));
            m.appendReplacement(out, Matcher.quoteReplacement(
                    stamp(Math.max(0, ms + offsetMs), m.group(4))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String stamp(final long ms, final String separator) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", ms / 3_600_000L,
                ms / 60_000L % 60, ms / 1_000L % 60, separator, ms % 1_000L);
    }

    /**
     * Writes {@code content} into {@code dir}, the folder of {@code video}. Blocking — the network
     * cases are a round trip or two, so off the main thread.
     */
    static Result save(final Context context, final DocumentFile video, final DocumentFile dir,
                       final String language, final boolean machine, final byte[] content)
            throws IOException {
        final String base = SubtitleUtils.getFileBaseName(video.getName());
        final DocumentFile[] files = dir.listFiles();
        final Set<String> taken = new HashSet<>();
        for (final DocumentFile file : files) {
            final String name = file.getName();
            if (name != null) {
                taken.add(name.toLowerCase(Locale.ROOT));
            }
        }
        // The same subtitle saved before, under any of the names this one could have been given. Only
        // read when the length already matches, so the usual case costs nothing beyond the listing.
        for (int n = 1; n <= MAX_NUMBER; n++) {
            final String name = nameFor(base, language, machine, n);
            if (!taken.contains(name.toLowerCase(Locale.ROOT))) {
                break;
            }
            final DocumentFile existing = find(files, name);
            if (existing != null && existing.length() == content.length
                    && Arrays.equals(read(context, existing.getUri()), content)) {
                return new Result(existing.getName(), true);
            }
        }
        // The listing can be a moment old; a writer that got there in between makes the create refuse,
        // and the next number is tried.
        while (true) {
            final String name = freeName(base, language, machine, taken);
            if (name == null) {
                throw new IOException("No free name for " + base + "." + language);
            }
            if (create(context, video, dir, name, content)) {
                return new Result(name, false);
            }
            taken.add(name.toLowerCase(Locale.ROOT));
        }
    }

    @Nullable
    private static DocumentFile find(final DocumentFile[] files, final String name) {
        for (final DocumentFile file : files) {
            if (name.equalsIgnoreCase(file.getName())) {
                return file;
            }
        }
        return null;
    }

    /** @return false when the name turned out to be taken */
    private static boolean create(final Context context, final DocumentFile video,
                                  final DocumentFile dir, final String name, final byte[] content)
            throws IOException {
        final Uri videoUri = video.getUri();
        final String scheme = videoUri.getScheme();
        if ("file".equals(scheme)) {
            final File target = new File(new File(videoUri.getPath()).getParentFile(), name);
            if (!target.createNewFile()) {
                return false;
            }
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(content);
            }
            return true;
        }
        if (SmbSessions.SCHEME.equals(scheme)) {
            return SmbSessions.create(context, sibling(videoUri, name), content);
        }
        if (DavFiles.speaks(videoUri)) {
            return DavFiles.create(context, sibling(videoUri, name), content);
        }
        // A storage tree granted through the picker. The provider would rename a clash to "name (1)"
        // rather than refuse it, so the listing above is the only check here.
        final DocumentFile target = dir.createFile("application/octet-stream", name);
        if (target == null) {
            throw new IOException("Folder refused " + name);
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(target.getUri())) {
            if (out == null) {
                throw new IOException("No stream for " + target.getUri());
            }
            out.write(content);
        }
        return true;
    }

    /** The address of {@code name} in the same folder as {@code uri}. */
    static Uri sibling(final Uri uri, final String name) {
        final List<String> segments = uri.getPathSegments();
        final Uri.Builder builder = uri.buildUpon().path("");
        for (int i = 0; i < segments.size() - 1; i++) {
            builder.appendPath(segments.get(i));
        }
        return builder.appendPath(name).build();
    }

    private static byte[] read(final Context context, final Uri uri) throws IOException {
        if (SmbSessions.SCHEME.equals(uri.getScheme())) {
            try (com.hierynomus.smbj.share.File file = SmbSessions.open(context, uri);
                 InputStream in = file.getInputStream()) {
                return readAll(in);
            }
        }
        if (DavFiles.speaks(uri)) {
            final Request.Builder request = new Request.Builder().url(DavFiles.http(uri).toString());
            final String authorization = NetworkFiles.authorization(context, uri);
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            try (Response response = PlayerActivity.MEDIA_HTTP_CLIENT.newCall(request.build()).execute()) {
                final ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    throw new IOException("GET " + response.code() + " for " + uri.getLastPathSegment());
                }
                return body.bytes();
            }
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("No stream for " + uri);
            }
            return readAll(in);
        }
    }

    static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
