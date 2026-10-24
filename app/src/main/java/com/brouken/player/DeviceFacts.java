package com.brouken.player;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.text.TextUtils;
import android.view.Display;

import androidx.media3.common.C;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.audio.AudioCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * What the device answers when asked what it can do: the questions a "does not play" report raises
 * first, put to the platform on the device in hand rather than remembered. Each fact is a title and a
 * reading; the settings screen shows them as rows, and the report is the same facts as text.
 *
 * <p>Nothing here is a setting, and nothing is kept: every reading is taken when the screen opens, so a
 * receiver switched on since, or a display mode changed, shows as it is now.
 */
final class DeviceFacts {

    static final class Fact {
        final String title;
        final String value;

        Fact(final String title, final String value) {
            this.title = title;
            this.value = value;
        }
    }

    private DeviceFacts() {
    }

    static List<Fact> device(final Context context) {
        final List<Fact> facts = new ArrayList<>();
        facts.add(new Fact(context.getString(R.string.device_model),
                Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"));
        String android = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        if (Build.VERSION.SECURITY_PATCH != null && !Build.VERSION.SECURITY_PATCH.isEmpty()) {
            android += " · " + Build.VERSION.SECURITY_PATCH;
        }
        facts.add(new Fact("Android", android));
        String cpu = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
        if (Build.VERSION.SDK_INT >= 31 && !Build.UNKNOWN.equals(Build.SOC_MODEL)) {
            cpu = Build.SOC_MODEL + " · " + cpu;
        }
        facts.add(new Fact(context.getString(R.string.device_cpu), cpu));
        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memory);
            // The heap is what the player's buffer is cut to (HeapCappedLoadControl): on a box with
            // 2 GB the large heap, not the RAM, is what decides how far ahead a 4K file can be read.
            facts.add(new Fact(context.getString(R.string.device_memory),
                    context.getString(R.string.device_memory_value, gigabytes(memory.totalMem),
                            am.getLargeMemoryClass())));
        }
        return facts;
    }

    static List<Fact> display(final Context context) {
        final List<Fact> facts = new ArrayList<>();
        final DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        final Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return facts;
        }
        final Display.Mode mode = display.getMode();
        facts.add(new Fact(context.getString(R.string.device_display_mode),
                mode.getPhysicalWidth() + "×" + mode.getPhysicalHeight() + " @ " + hz(mode.getRefreshRate())));
        // The rates the box can switch to without changing the size: what frame rate matching has to
        // work with. Other sizes are listed apart, since switching to one is a different question.
        final TreeSet<Float> rates = new TreeSet<>();
        final TreeSet<String> sizes = new TreeSet<>((a, b) -> Integer.compare(pixels(b), pixels(a)));
        for (Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() == mode.getPhysicalWidth()
                    && m.getPhysicalHeight() == mode.getPhysicalHeight()) {
                rates.add(m.getRefreshRate());
            }
            sizes.add(m.getPhysicalWidth() + "×" + m.getPhysicalHeight());
        }
        final StringBuilder rateList = new StringBuilder();
        for (Float rate : rates) {
            rateList.append(rateList.length() == 0 ? "" : " · ").append(hz(rate));
        }
        facts.add(new Fact(context.getString(R.string.device_display_rates), rateList.toString()));
        if (sizes.size() > 1) {
            facts.add(new Fact(context.getString(R.string.device_display_sizes), TextUtils.join(" · ", sizes)));
        }
        final StringBuilder hdr = new StringBuilder();
        final Display.HdrCapabilities caps = Build.VERSION.SDK_INT >= 24 ? display.getHdrCapabilities() : null;
        if (caps != null) {
            for (int type : caps.getSupportedHdrTypes()) {
                final String name = hdrName(type);
                if (name != null) {
                    hdr.append(hdr.length() == 0 ? "" : " · ").append(name);
                }
            }
        }
        facts.add(new Fact("HDR", hdr.length() == 0 ? context.getString(R.string.device_none) : hdr.toString()));
        return facts;
    }

    static List<Fact> audio(final Context context) {
        final List<Fact> facts = new ArrayList<>();
        final AudioCapabilities caps = AudioCapabilities.getCapabilities(context);
        final int[] codes = {C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_AC4,
                C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2, C.ENCODING_DOLBY_TRUEHD};
        final String[] names = {"AC-3", "E-AC-3", "E-AC-3 JOC (Atmos)", "AC-4", "DTS", "DTS-HD",
                "DTS:X", "TrueHD"};
        final StringBuilder bitstream = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (caps.supportsEncoding(codes[i])) {
                bitstream.append(bitstream.length() == 0 ? "" : " · ").append(names[i]);
            }
        }
        // As the output reports it now: a receiver switched off since the box woke may answer for the
        // television instead. The audio settings can declare more than this, but that is the app's word.
        facts.add(new Fact(context.getString(R.string.device_audio_bitstream),
                bitstream.length() == 0 ? context.getString(R.string.device_audio_pcm_only) : bitstream.toString()));
        facts.add(new Fact(context.getString(R.string.device_audio_channels),
                String.valueOf(caps.getMaxChannelCount())));
        return facts;
    }

    /** One row per codec: the best hardware decoder for it, else whether there is a software one. */
    static List<Fact> decoders(final Context context) {
        final String[][] codecs = {
                {"H.264", "video/avc"},
                {"HEVC", "video/hevc"},
                {"AV1", "video/av01"},
                {"VP9", "video/x-vnd.on2.vp9"},
                {"Dolby Vision", "video/dolby-vision"},
                {"MPEG-2", "video/mpeg2"},
                {"VC-1", "video/wvc1"},
                {"MPEG-4", "video/mp4v-es"},
        };
        final MediaCodecInfo[] infos;
        try {
            infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        final List<Fact> facts = new ArrayList<>();
        for (String[] codec : codecs) {
            facts.add(new Fact(codec[0], decoder(context, infos, codec[1])));
        }
        return facts;
    }

    private static String decoder(final Context context, final MediaCodecInfo[] infos, final String mime) {
        int bestPixels = 0;
        String best = null;
        boolean software = false;
        for (MediaCodecInfo info : infos) {
            if (info.isEncoder() || !handles(info, mime)) {
                continue;
            }
            if (isSoftware(info)) {
                software = true;
                continue;
            }
            final MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = info.getCapabilitiesForType(mime);
            } catch (IllegalArgumentException e) {
                continue;
            }
            final MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
            if (video == null) {
                continue;
            }
            final int w = video.getSupportedWidths().getUpper();
            final int h = video.getSupportedHeights().getUpper();
            if (w * h <= bestPixels) {
                continue;
            }
            bestPixels = w * h;
            final StringBuilder line = new StringBuilder(context.getString(R.string.device_decoder_hw))
                    .append(", ").append(context.getString(R.string.device_decoder_up_to, w + "×" + h));
            final String extra = profiles(mime, caps.profileLevels);
            if (extra != null) {
                line.append(" · ").append(extra);
            }
            best = line.toString();
        }
        if (best != null) {
            return best;
        }
        return context.getString(software ? R.string.device_decoder_sw : R.string.device_none);
    }

    /**
     * What a box's own name for a decoder hides: the 10-bit profile, which is the difference between
     * playing an HDR file and not, and for Dolby Vision the profile numbers, since 5, 7 and 8 are three
     * different answers to "does it play".
     */
    private static String profiles(final String mime, final MediaCodecInfo.CodecProfileLevel[] levels) {
        if (levels == null) {
            return null;
        }
        if ("video/dolby-vision".equals(mime)) {
            final TreeSet<Integer> numbers = new TreeSet<>();
            for (MediaCodecInfo.CodecProfileLevel level : levels) {
                // The constants are one bit each, profile 0 on bit 0: the profile is the bit's position.
                numbers.add(Integer.numberOfTrailingZeros(level.profile));
            }
            if (numbers.isEmpty()) {
                return null;
            }
            final StringBuilder list = new StringBuilder("P");
            boolean first = true;
            for (Integer number : numbers) {
                list.append(first ? "" : ", ").append(number);
                first = false;
            }
            return list.toString();
        }
        final int tenBit;
        switch (mime) {
            case "video/hevc":
                tenBit = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
                break;
            case "video/av01":
                tenBit = MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10;
                break;
            case "video/x-vnd.on2.vp9":
                tenBit = MediaCodecInfo.CodecProfileLevel.VP9Profile2;
                break;
            default:
                return null;
        }
        for (MediaCodecInfo.CodecProfileLevel level : levels) {
            if (level.profile == tenBit) {
                return "10 bit";
            }
        }
        return null;
    }

    static List<Fact> player(final Context context) {
        final List<Fact> facts = new ArrayList<>();
        facts.add(new Fact(context.getString(R.string.app_name), BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ", " + BuildConfig.FLAVOR + ")"));
        facts.add(new Fact("Media3", MediaLibraryInfo.VERSION));
        // The extension decoder is what plays AC3/EAC3/DTS here, and whether it loaded at all is
        // the first question a "no sound" report raises.
        facts.add(new Fact("FFmpeg", FfmpegLibrary.isAvailable()
                ? FfmpegLibrary.getVersion() : context.getString(R.string.device_none)));
        return facts;
    }

    /** Everything above as text, in the shape ErrorActivity already pastes, with the firmware at the end. */
    static String report(final Context context) {
        final StringBuilder sb = new StringBuilder();
        sb.append(BuildConfig.APPLICATION_ID).append('@').append(BuildConfig.VERSION_NAME)
                .append(" (build ").append(BuildConfig.VERSION_CODE).append(", ").append(BuildConfig.FLAVOR)
                .append(BuildConfig.DEBUG ? " debug)" : " release)");
        final List<List<Fact>> groups = new ArrayList<>();
        groups.add(device(context));
        groups.add(display(context));
        groups.add(audio(context));
        groups.add(decoders(context));
        groups.add(player(context));
        for (List<Fact> group : groups) {
            sb.append('\n');
            for (Fact fact : group) {
                sb.append('\n').append(fact.title).append(": ").append(fact.value);
            }
        }
        sb.append("\n\nBuild: ").append(Build.FINGERPRINT);
        return sb.toString();
    }

    private static boolean handles(final MediaCodecInfo info, final String mime) {
        for (String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    /** Google's own decoders, by the names the platform gives them; everything else is the vendor's. */
    private static boolean isSoftware(final MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return !info.isHardwareAccelerated();
        }
        final String name = info.getName();
        return name.startsWith("OMX.google.") || name.startsWith("c2.android.");
    }

    private static String hdrName(final int type) {
        switch (type) {
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION:
                return "Dolby Vision";
            case Display.HdrCapabilities.HDR_TYPE_HDR10:
                return "HDR10";
            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS:
                return "HDR10+";
            case Display.HdrCapabilities.HDR_TYPE_HLG:
                return "HLG";
            default:
                return null;
        }
    }

    private static String hz(final float rate) {
        // 23.976 and 59.94 are what a film needs told apart from 24 and 60; whole rates stay whole.
        final float rounded = Math.round(rate);
        return Math.abs(rate - rounded) < 0.005f
                ? String.format(Locale.ROOT, "%d Hz", (int) rounded)
                : String.format(Locale.ROOT, "%.3f Hz", rate);
    }

    private static int pixels(final String size) {
        final int x = size.indexOf('×');
        try {
            return Integer.parseInt(size.substring(0, x)) * Integer.parseInt(size.substring(x + 1));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String gigabytes(final long bytes) {
        return String.format(Locale.getDefault(), "%.1f", bytes / (1024f * 1024f * 1024f));
    }
}
