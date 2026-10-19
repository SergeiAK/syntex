package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;

import java.util.Locale;

/**
 * The container's short name for the statistics card: from what the extractor says it read, sharpened
 * by the file's own extension where that names a member of the same family — Media3 reads a .mov and an
 * .mp4 alike, and an M2TS is a TS to it. The extension alone never overrides the extractor: a file named
 * .avi that turns out to be Matroska is MKV.
 */
final class ContainerName {

    private ContainerName() {
    }

    @Nullable
    static String of(@Nullable String containerMimeType, @Nullable String fileName) {
        final int dot = fileName != null ? fileName.lastIndexOf('.') : -1;
        final String extension = dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(Locale.US) : null;
        if (containerMimeType == null) {
            // Nothing from the extractor: the extension is the best there is, if it looks like one.
            return extension != null && extension.length() <= 4 ? extension.toUpperCase(Locale.US) : null;
        }
        final String family;
        final String[] members;
        switch (containerMimeType) {
            case MimeTypes.VIDEO_MATROSKA:
            case MimeTypes.AUDIO_MATROSKA:
                family = "MKV";
                members = new String[]{"mkv", "mk3d", "mka"};
                break;
            case MimeTypes.VIDEO_WEBM:
            case MimeTypes.AUDIO_WEBM:
                family = "WebM";
                members = new String[]{"webm"};
                break;
            case MimeTypes.VIDEO_MP4:
            case MimeTypes.AUDIO_MP4:
            case "video/quicktime":
                family = "MP4";
                members = new String[]{"mp4", "m4v", "mov", "3gp"};
                break;
            case MimeTypes.VIDEO_MP2T:
                family = "TS";
                members = new String[]{"ts", "m2ts", "mts", "tp", "trp"};
                break;
            case MimeTypes.VIDEO_PS:
                family = "MPEG-PS";
                members = new String[]{"mpg", "mpeg", "vob"};
                break;
            case MimeTypes.VIDEO_FLV:
                family = "FLV";
                members = new String[]{"flv"};
                break;
            case MimeTypes.VIDEO_AVI:
                family = "AVI";
                members = new String[]{"avi", "divx"};
                break;
            case MimeTypes.APPLICATION_M3U8:
                return "HLS";
            case MimeTypes.APPLICATION_MPD:
                return "DASH";
            default:
                return containerMimeType.substring(containerMimeType.lastIndexOf('/') + 1)
                        .toUpperCase(Locale.US);
        }
        if (extension != null) {
            for (String member : members) {
                if (member.equals(extension)) {
                    return extension.toUpperCase(Locale.US);
                }
            }
        }
        return family;
    }
}
