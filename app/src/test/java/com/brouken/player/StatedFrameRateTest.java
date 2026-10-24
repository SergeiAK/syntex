package com.brouken.player;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;

import org.junit.Test;

/**
 * The frame rate from the parameter sets, against what ffmpeg's trace_headers reads there. Samples are
 * passed in with -PmediaTestDir=...
 *
 * <p>H.264: {@code 1080i-fields.ts} (see InterlacedTsFieldsTest; field pictures, time_scale 50, tick
 * 1); {@code 1080i-25-H264.mkv} — kodi.wiki's "1080i-25-H264.mkv" (frame_mbs_only 0, time_scale 50);
 * {@code FPS_test_1080p<rate>_L4.x.mkv} — each of kodi.wiki's frame rate tests (progressive; 23.976 is
 * 48000/1001 and 59.94 120000/1001).
 *
 * <p>HEVC: {@code hevc-tears-23976.ts}, {@code hevc-phantom-5994.ts} — 188·20000 bytes of Samsung's UHD
 * demos "Samsung UHD_ENT_Tears_Of_Steel.ts" (VPS and VUI 108000000/4504500) and "Samsung
 * SUHD_Phantom_Flex.ts" (108000000/1801800); {@code hevc-bbb-no-vui.ts} — the same of "bbb_1080p_c.ts"
 * (no VPS timing, no VUI); {@code hevc-astra-25.ts} — the same of kodi.wiki's "(HDR HEVC 10-bit 25fps)
 * Astra DVB Sample.ts" (3840×2160 Main10 from the satellite, VPS and VUI 25/1); {@code hevc-jellyfish-2997.mkv} — "jellyfish-120-mbps-4k-uhd-hevc-10bit.mkv"
 * (VUI only, 2997/100); {@code hevc-dv7-24.mkv} — "Sample DV7.mkv" (24000/1000); {@code
 * hevc-hdr10-23976.mkv} — "09. BitStream@Audio-formats_test_HDR10_23.976.mkv" (VUI only, 24000/1001);
 * {@code hevc-rext-444-1fps.mp4} — Chromium's "quick-brown-fox-1280x720-hevc-rext-10bit-444-no-audio.mp4"
 * (4:4:4, VUI 1/1: a rate out of range, so none).
 *
 * <p>More from the test sets (K:\testVideo): {@code h264-deinterlace-25i.m2ts} — 192·20000 bytes of
 * "test/Deinterlace.m2ts" (1440×1080i, High with a scaling matrix in the SPS, 50/1); {@code
 * h264-truehd-2997i.m2ts} — the same of "dolby_truehd_channel_check_lossless.m2ts" (1080i, 60000/1001);
 * Chromium's {@code bear-1280x720_ptswraparound.ts} (5994/100), {@code extra-nalu.ts} (60/1) and {@code
 * hls-bear0.ts} — "hls/bear0.ts" (60000/1001); {@code hevc-avsync-2398.mov}, {@code hevc-avsync-50.mov}
 * — kodi.wiki's PhotoJoseph A/V sync "…23,98_HEVC_1080p…" and "…50,00_HEVC_2160p…" (four short-term
 * sets and scaling lists enabled in the SPS, a VUI without timing, no VPS timing either).
 *
 * <p>The Matroska and MOV ones are the first seconds remuxed ({@code ffmpeg -t … -map 0:v:0 -c copy}): a cut
 * head sends the extractor to Cues past its end, and only CodecPrivate is looked at, which a copy leaves
 * as it was. None of the real HEVC files has short-term reference picture sets, scaling lists, PCM,
 * long-term pictures, sub-layers or field_seq_flag in its SPS; those are covered by a hand-built SPS.
 */
public class StatedFrameRateTest {

    static {
        ExtractorHarness.prepareJvm();
    }

    @Test
    public void h264InterlacedTsStates25() throws IOException {
        assertEquals(25f, rateOf(ts(), "1080i-fields.ts"), 0.001f);
    }

    @Test
    public void h264InterlacedMkvStates25() throws IOException {
        assertEquals(25f, rateOf(mkv(), "1080i-25-H264.mkv"), 0.001f);
    }

    @Test
    public void h264ProgressiveRates() throws IOException {
        assertEquals(23.976f, rateOf(mkv(), "FPS_test_1080p23.976_L4.1.mkv"), 0.001f);
        assertEquals(24f, rateOf(mkv(), "FPS_test_1080p24_L4.1.mkv"), 0.001f);
        assertEquals(25f, rateOf(mkv(), "FPS_test_1080p25_L4.1.mkv"), 0.001f);
        assertEquals(50f, rateOf(mkv(), "FPS_test_1080p50_L4.2.mkv"), 0.001f);
        assertEquals(59.94f, rateOf(mkv(), "FPS_test_1080p59.94_L4.2.mkv"), 0.001f);
        assertEquals(60f, rateOf(mkv(), "FPS_test_1080p60_L4.2.mkv"), 0.001f);
    }

    @Test
    public void hevcTs() throws IOException {
        assertEquals(23.976f, rateOf(ts(), "hevc-tears-23976.ts"), 0.001f);
        assertEquals(59.94f, rateOf(ts(), "hevc-phantom-5994.ts"), 0.001f);
        assertEquals(Format.NO_VALUE, rateOf(ts(), "hevc-bbb-no-vui.ts"), 0f);
        // Broadcast: DVB-S2 capture, one short-term set in the SPS, VPS and VUI both 25/1.
        assertEquals(25f, rateOf(ts(), "hevc-astra-25.ts"), 0.001f);
    }

    @Test
    public void hevcMkvAndMp4() throws IOException {
        assertEquals(29.97f, rateOf(mkv(), "hevc-jellyfish-2997.mkv"), 0.001f);
        // Dolby Vision on the HEVC decoder: the player passes the decoder's mime, not the track's.
        assertEquals(24f, rateOf(mkv(), "hevc-dv7-24.mkv", MimeTypes.VIDEO_H265), 0.001f);
        assertEquals(23.976f, rateOf(mkv(), "hevc-hdr10-23976.mkv"), 0.001f);
        assertEquals(Format.NO_VALUE,
                rateOf(new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), "hevc-rext-444-1fps.mp4"), 0f);
    }

    @Test
    public void h264InterlacedM2ts() throws IOException {
        assertEquals(25f, rateOf(m2ts(), "h264-deinterlace-25i.m2ts"), 0.001f);
        assertEquals(29.97f, rateOf(m2ts(), "h264-truehd-2997i.m2ts"), 0.001f);
    }

    @Test
    public void h264ChromiumTs() throws IOException {
        assertEquals(29.97f, rateOf(ts(), "bear-1280x720_ptswraparound.ts"), 0.001f);
        assertEquals(30f, rateOf(ts(), "extra-nalu.ts"), 0.001f);
        assertEquals(29.97f, rateOf(ts(), "hls-bear0.ts"), 0.001f);
    }

    /**
     * No rate stated anywhere, and the reading has to get there honestly: through four short-term sets
     * to a VUI that is present and has no timing.
     */
    @Test
    public void hevcAvSyncStatesNothing() throws IOException {
        for (String name : new String[]{"hevc-avsync-2398.mov", "hevc-avsync-50.mov"}) {
            final Format format = formatOf(new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), name);
            assertEquals(name, Format.NO_VALUE,
                    StatedFrameRate.of(format.sampleMimeType, format.initializationData), 0f);
            final byte[] data = format.initializationData.get(0);
            final int sps = hevcNal(data, 33);
            final StatedFrameRate.H265Vui vui = StatedFrameRate.h265Sps(data, sps + 2, nalEnd(data, sps));
            assertEquals(name, true, vui.present);
            assertEquals(name, Format.NO_VALUE, vui.rate, 0f);
        }
    }

    /** Every branch the real files skip, then field_seq_flag: 50 fields a second, 25 frames. */
    @Test
    public void hevcHandBuiltSpsWithEverything() {
        assertEquals(25f, StatedFrameRate.of(MimeTypes.VIDEO_H265,
                Collections.singletonList(hevcSps(true, true))), 0.001f);
        assertEquals(50f, StatedFrameRate.of(MimeTypes.VIDEO_H265,
                Collections.singletonList(hevcSps(false, true))), 0.001f);
        assertEquals(Format.NO_VALUE, StatedFrameRate.of(MimeTypes.VIDEO_H265,
                Collections.singletonList(hevcSps(false, false))), 0f);
    }

    /**
     * Media3's own SPS reader walks the same sets, long-term pictures and VUI up to the colour
     * description: reading back what was written there checks the hand-built SPS against an
     * implementation that is not this one.
     */
    @Test
    public void hevcHandBuiltSpsReadsBackInMedia3() {
        final byte[] sps = hevcSps(true, true);
        final androidx.media3.container.NalUnitUtil.H265SpsData data =
                androidx.media3.container.NalUnitUtil.parseH265SpsNalUnit(sps, 4, sps.length, null);
        assertEquals(1920, data.width);
        // 1088 cropped by 8, and doubled: with field_seq_flag Media3 reports the frame two fields make.
        assertEquals(2160, data.height);
        assertEquals(4f / 3f, data.pixelWidthHeightRatio, 0.0001f);
        assertEquals(C.COLOR_SPACE_BT709, data.colorSpace);
        assertEquals(C.COLOR_TRANSFER_SDR, data.colorTransfer);
        assertEquals(C.COLOR_RANGE_LIMITED, data.colorRange);
    }

    /** An SPS without VUI timing falls back on the VPS's, whichever comes first in the array. */
    @Test
    public void hevcVpsAnswersWhenTheSpsDoesNot() {
        final ByteArrayOutputStream both = new ByteArrayOutputStream();
        final byte[] vps = hevcVps();
        final byte[] sps = hevcSps(false, false);
        both.write(vps, 0, vps.length);
        both.write(sps, 0, sps.length);
        assertEquals(23.976f, StatedFrameRate.of(MimeTypes.VIDEO_H265,
                Collections.singletonList(both.toByteArray())), 0.001f);
        // And the SPS wins when it has timing of its own.
        final ByteArrayOutputStream timed = new ByteArrayOutputStream();
        final byte[] spsTimed = hevcSps(false, true);
        timed.write(vps, 0, vps.length);
        timed.write(spsTimed, 0, spsTimed.length);
        assertEquals(50f, StatedFrameRate.of(MimeTypes.VIDEO_H265,
                Collections.singletonList(timed.toByteArray())), 0.001f);
    }

    @Test
    public void nothingStatedNothingSaid() {
        assertEquals(Format.NO_VALUE, StatedFrameRate.of(MimeTypes.VIDEO_H264, Collections.emptyList()), 0f);
        // An SPS cut off after the profile: no rate rather than an exception.
        assertEquals(Format.NO_VALUE, StatedFrameRate.of(MimeTypes.VIDEO_H264, Collections.singletonList(
                new byte[]{0, 0, 0, 1, 0x67, 0x64})), 0f);
        // Not a codec this reads.
        assertEquals(Format.NO_VALUE, StatedFrameRate.of(MimeTypes.VIDEO_AV1, Collections.singletonList(
                new byte[]{0, 0, 0, 1, 0x67, 0x64})), 0f);
    }

    private static TsExtractor ts() {
        return new TsExtractor(new DefaultSubtitleParserFactory());
    }

    private static MatroskaExtractor mkv() {
        return new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0);
    }

    private static float rateOf(Extractor extractor, String name) throws IOException {
        return rateOf(extractor, name, null);
    }

    private static M2tsExtractor m2ts() {
        final ExtractorsFactory none = () -> new Extractor[0];
        return new M2tsExtractor.Factory(none, new DefaultSubtitleParserFactory(),
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
                1500 * TsExtractor.TS_PACKET_SIZE).create();
    }

    private static Format formatOf(Extractor extractor, String name) throws IOException {
        final ExtractorHarness.Result result =
                ExtractorHarness.readSamples(extractor, ExtractorHarness.testFile(name), 10);
        return result.track(C.TRACK_TYPE_VIDEO).format;
    }

    /** Where the header of the first HEVC NAL unit of {@code type} is, after its start code. */
    private static int hevcNal(byte[] data, int type) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && ((data[i + 3] >> 1) & 0x3f) == type) {
                return i + 3;
            }
        }
        throw new AssertionError("no NAL unit of type " + type);
    }

    private static int nalEnd(byte[] data, int start) {
        for (int i = start; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || data[i + 2] == 0)) return i;
        }
        return data.length;
    }

    private static float rateOf(Extractor extractor, String name, String codecMime) throws IOException {
        final Format format = formatOf(extractor, name);
        return StatedFrameRate.of(codecMime != null ? codecMime : format.sampleMimeType,
                format.initializationData);
    }

    /**
     * An HEVC SPS written field by field from H.265 7.3.2.2, with sub-layers, scaling list data, PCM,
     * five short-term sets (all but the first predicted), two long-term pictures and a full VUI, its
     * timing 50/1. Set 3 has a delta that lands on the current picture, which only the
     * derivation of H.265 7-61 drops.
     */
    private static byte[] hevcSps(boolean fieldSeq, boolean timing) {
        return hevcSps(fieldSeq, timing, 31);
    }

    /** {@code parts}: 1 sub-layer, 2 scaling lists, 4 PCM, 8 short-term sets, 16 long-term pictures. */
    private static byte[] hevcSps(boolean fieldSeq, boolean timing, int parts) {
        final boolean sub = (parts & 1) != 0;
        final Bits w = new Bits();
        w.u(4, 0); // sps_video_parameter_set_id
        w.u(3, sub ? 1 : 0); // sps_max_sub_layers_minus1
        w.u(1, 1); // sps_temporal_id_nesting_flag
        // profile_tier_level(1, 1)
        w.u(8, 0x01); // profile space, tier, profile idc (Main)
        w.u(32, 0x60000000); // compatibility flags
        w.u(4, 0b1001); // progressive, interlaced, non-packed, frame-only
        w.u(32, 0);
        w.u(11, 0); // 43 reserved bits
        w.u(1, 0); // inbld / reserved
        w.u(8, 120); // general_level_idc
        if (sub) {
            w.u(1, 1); // sub_layer_profile_present_flag[0]
            w.u(1, 1); // sub_layer_level_present_flag[0]
            for (int i = 1; i < 8; i++) w.u(2, 0); // reserved_zero_2bits
            w.u(32, 0x01600000);
            w.u(32, 0x90000000);
            w.u(24, 0); // sub-layer profile: 88 bits
            w.u(8, 93); // sub_layer_level_idc
        }
        w.ue(0); // sps_seq_parameter_set_id
        w.ue(1); // chroma_format_idc
        w.ue(1920);
        w.ue(1088);
        w.u(1, 1); // conformance_window_flag
        w.ue(0);
        w.ue(0);
        w.ue(0);
        w.ue(4);
        w.ue(0); // bit_depth_luma_minus8
        w.ue(0);
        w.ue(4); // log2_max_pic_order_cnt_lsb_minus4: lsb is 8 bits
        w.u(1, 1); // sps_sub_layer_ordering_info_present_flag
        for (int i = 0; i < (sub ? 2 : 1); i++) {
            w.ue(4);
            w.ue(2);
            w.ue(0);
        }
        w.ue(0);
        w.ue(3);
        w.ue(0);
        w.ue(3);
        w.ue(1);
        w.ue(1);
        w.u(1, (parts & 2) != 0 ? 1 : 0); // scaling_list_enabled_flag
        if ((parts & 2) != 0) w.u(1, 1); // sps_scaling_list_data_present_flag
        if ((parts & 2) != 0) for (int sizeId = 0; sizeId < 4; sizeId++) {
            for (int matrixId = 0; matrixId < 6; matrixId += sizeId == 3 ? 3 : 1) {
                final boolean explicit = matrixId == 0;
                w.u(1, explicit ? 1 : 0); // scaling_list_pred_mode_flag
                if (!explicit) {
                    w.ue(1); // scaling_list_pred_matrix_id_delta
                } else {
                    final int coefNum = Math.min(64, 1 << (4 + (sizeId << 1)));
                    if (sizeId > 1) w.se(-3);
                    for (int i = 0; i < coefNum; i++) w.se(i % 3 - 1);
                }
            }
        }
        w.u(1, 1); // amp_enabled_flag
        w.u(1, 1); // sample_adaptive_offset_enabled_flag
        w.u(1, (parts & 4) != 0 ? 1 : 0); // pcm_enabled_flag
        if ((parts & 4) != 0) {
            w.u(4, 7);
            w.u(4, 7);
            w.ue(0);
            w.ue(1);
            w.u(1, 0);
        }
        w.ue((parts & 8) != 0 ? 5 : 0); // num_short_term_ref_pic_sets
        if ((parts & 8) != 0) {
            // Set 0: deltas -1, -3 | +1.
            w.ue(2); // num_negative_pics
            w.ue(1); // num_positive_pics
            w.ue(0); w.u(1, 1);
            w.ue(1); w.u(1, 1);
            w.ue(0); w.u(1, 0);
            // Set 1 from set 0, deltaRps -1: entries -2, -4, 0, and -1 itself; the 0 is not kept.
            // Result: -1, -2, -4 | none.
            w.u(1, 1); // inter_ref_pic_set_prediction_flag
            w.u(1, 1); // delta_rps_sign: negative
            w.ue(0); // abs_delta_rps_minus1
            w.u(1, 1); // j0 used
            w.u(1, 0); w.u(1, 1); // j1 not used, use_delta
            w.u(1, 0); w.u(1, 0); // j2 not used, not kept
            w.u(1, 1); // j3 (deltaRps) used
            // Set 2 from set 1, deltaRps +2: entries +1, 0, -2, and +2. Result: -2 | +1, +2.
            w.u(1, 1);
            w.u(1, 0);
            w.ue(1);
            w.u(1, 1);
            w.u(1, 0); w.u(1, 0);
            w.u(1, 0); w.u(1, 1);
            w.u(1, 1);
            // Set 3 from set 2, deltaRps -1: entries -3, 0, +1, and -1. The 0 is marked use_delta and
            // is still not a picture: result -1, -3 | +1, three, where counting the flags says four.
            w.u(1, 1);
            w.u(1, 1);
            w.ue(0);
            w.u(1, 1);
            w.u(1, 0); w.u(1, 1);
            w.u(1, 1);
            w.u(1, 1);
            // Set 4 from set 3: four entries (j = 0..3), all used.
            w.u(1, 1);
            w.u(1, 0);
            w.ue(0);
            for (int k = 0; k < 4; k++) w.u(1, 1);
        }
        w.u(1, (parts & 16) != 0 ? 1 : 0); // long_term_ref_pics_present_flag
        if ((parts & 16) != 0) {
            w.ue(2);
            w.u(8, 17); w.u(1, 1); // lt_ref_pic_poc_lsb_sps (8 bits), used flag
            w.u(8, 200); w.u(1, 0);
        }
        w.u(1, 1); // sps_temporal_mvp_enabled_flag
        w.u(1, 1); // strong_intra_smoothing_enabled_flag
        w.u(1, 1); // vui_parameters_present_flag
        w.u(1, 1); // aspect_ratio_info_present_flag
        w.u(8, 255); // Extended_SAR
        w.u(16, 4);
        w.u(16, 3);
        w.u(1, 1); // overscan_info_present_flag
        w.u(1, 0);
        w.u(1, 1); // video_signal_type_present_flag
        w.u(3, 5);
        w.u(1, 0);
        w.u(1, 1); // colour_description_present_flag
        w.u(8, 1);
        w.u(8, 1);
        w.u(8, 1);
        w.u(1, 1); // chroma_loc_info_present_flag
        w.ue(0);
        w.ue(0);
        w.u(1, 0); // neutral_chroma_indication_flag
        w.u(1, fieldSeq ? 1 : 0); // field_seq_flag
        w.u(1, 1); // frame_field_info_present_flag
        w.u(1, 1); // default_display_window_flag
        w.ue(0);
        w.ue(0);
        w.ue(0);
        w.ue(8);
        w.u(1, timing ? 1 : 0); // vui_timing_info_present_flag
        if (timing) {
            w.u(32, 1);
            w.u(32, 50);
            w.u(1, 0); // vui_poc_proportional_to_timing_flag
            w.u(1, 0); // vui_hrd_parameters_present_flag
        }
        w.u(1, 0); // bitstream_restriction_flag
        w.u(1, 0); // sps_extension_present_flag
        w.u(1, 1); // rbsp_stop_one_bit
        return w.nal(new byte[]{0x42, 0x01});
    }

    /** An HEVC VPS from H.265 7.3.2.1, one sub-layer above the base, two layer sets, timing 24000/1001. */
    private static byte[] hevcVps() {
        final Bits w = new Bits();
        w.u(4, 0); // vps_video_parameter_set_id
        w.u(1, 1); // vps_base_layer_internal_flag
        w.u(1, 1); // vps_base_layer_available_flag
        w.u(6, 0); // vps_max_layers_minus1
        w.u(3, 1); // vps_max_sub_layers_minus1
        w.u(1, 1); // vps_temporal_id_nesting_flag
        w.u(16, 0xffff);
        w.u(8, 0x01);
        w.u(32, 0x60000000);
        w.u(4, 0b1001);
        w.u(32, 0);
        w.u(12, 0);
        w.u(8, 120);
        w.u(1, 0); // sub_layer_profile_present_flag[0]
        w.u(1, 1); // sub_layer_level_present_flag[0]
        for (int i = 1; i < 8; i++) w.u(2, 0);
        w.u(8, 93);
        w.u(1, 0); // vps_sub_layer_ordering_info_present_flag: the last sub-layer only
        w.ue(4);
        w.ue(2);
        w.ue(0);
        w.u(6, 2); // vps_max_layer_id
        w.ue(1); // vps_num_layer_sets_minus1: one set of three flags
        w.u(3, 0b101);
        w.u(1, 1); // vps_timing_info_present_flag
        w.u(32, 1001);
        w.u(32, 24000);
        w.u(1, 0); // vps_poc_proportional_to_timing_flag
        w.ue(0); // vps_num_hrd_parameters
        w.u(1, 0); // vps_extension_flag
        w.u(1, 1); // rbsp_stop_one_bit
        return w.nal(new byte[]{0x40, 0x01});
    }

    /** MSB-first bit writer with exp-Golomb codes and emulation prevention on output. */
    private static final class Bits {
        private final ByteArrayOutputStream rbsp = new ByteArrayOutputStream();
        private int current;
        private int count;

        void u(int bits, long value) {
            for (int i = bits - 1; i >= 0; i--) {
                current = (current << 1) | (int) ((value >> i) & 1);
                if (++count == 8) {
                    rbsp.write(current);
                    current = 0;
                    count = 0;
                }
            }
        }

        void ue(int value) {
            final long v = value + 1L;
            final int length = 64 - Long.numberOfLeadingZeros(v);
            u(length - 1, 0);
            u(length, v);
        }

        void se(int value) {
            ue(value > 0 ? 2 * value - 1 : -2 * value);
        }

        byte[] nal(byte[] header) {
            while (count != 0) u(1, 0);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(header, 0, header.length);
            int zeros = 0;
            for (byte b : rbsp.toByteArray()) {
                if (zeros == 2 && (b & 0xff) <= 3) {
                    out.write(3);
                    zeros = 0;
                }
                out.write(b);
                zeros = b == 0 ? zeros + 1 : 0;
            }
            return out.toByteArray();
        }
    }
}
