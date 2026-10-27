# Third-party notices

Syntex's own code is under the MIT licence (see [LICENSE](LICENSE)). The APK also carries the code below, each
part under its own licence. Every release ships this file next to the APK.

## The base

| Project | Licence | |
|---|---|---|
| [Just (Video) Player](https://github.com/moneytoo/Player) by Marcel Dopita | Unlicense | the code Syntex is built on |
| [Just+ Player](https://github.com/just-plus-player/just-plus-player) 2.0.11 by @levende | Unlicense | the same, from its last published source |
| [DoubleTapPlayerView](https://github.com/vkay94/DoubleTapPlayerView) by vkay94 | MIT | `doubletapplayerview/`, inherited from Just Player |

## Media3 and its decoders

| Project | Licence | |
|---|---|---|
| [AndroidX Media3](https://github.com/androidx/media) 1.11.0 | Apache 2.0 | the player; `app/libs/lib-exoplayer-release.aar`, `lib-ui-release.aar` and the Maven modules |
| Media3 AVI and MPEG-PS extractors | Apache 2.0 | copies in `com.brouken.player.avi` and `com.brouken.player.ps`, modified; each file keeps its header and says what was changed |
| [dav1d](https://code.videolan.org/videolan/dav1d) 1.5.4 | BSD 2-Clause | AV1 decoder in `lib-decoder-av1-release.aar`, rebuilt here — see [app/libs/README.md](app/libs/README.md) |
| [cpu_features](https://github.com/google/cpu_features) | Apache 2.0 | part of the same AV1 decoder |
| [libiamf](https://github.com/AOMediaCodec/libiamf) | BSD 3-Clause Clear | IAMF audio decoder in `lib-decoder-iamf-release.aar` |
| [Fraunhofer FDK MPEG-H decoder](https://github.com/Fraunhofer-IIS/mpeghdec) | Software License for The Fraunhofer FDK MPEG-H Software | MPEG-H audio decoder in `lib-decoder-mpegh-release.aar`. The licence grants no patent rights |

## FFmpeg

[FFmpeg](https://ffmpeg.org) is under the GNU Lesser General Public License, version 2.1 or later. It is in the
APK twice, both built without `--enable-gpl` and `--enable-nonfree`:

- **Audio** — `lib-decoder-ffmpeg-release.aar`, Media3's FFmpeg extension, as published upstream; its decoders are
  listed in [app/libs/README.md](app/libs/README.md), its build in Media3's
  [decoder_ffmpeg README](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).
- **Video** — `libffmpegvideoJNI.so`, FFmpeg [n7.1.5](https://git.ffmpeg.org/gitweb/ffmpeg.git/tag/refs/tags/n7.1.5),
  linked statically. [ffvideo/build.sh](ffvideo/build.sh) and the JNI source beside it are everything needed to
  rebuild it, with this version of FFmpeg or another, and to put it back into the APK.

`com.brouken.player.avi.Mpeg4VopReorderer` follows FFmpeg's `mpeg4_unpack_bframes` bitstream filter and its
`genpts` logic.

The licence text: <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.

## Libraries

| Project | Licence |
|---|---|
| [libdovi](https://github.com/quietvoid/dovi_tool) through [ExoplayerHdrUtils](https://github.com/suyash192/ExoplayerHdrUtils) 0.3.0 | MIT |
| [smbj](https://github.com/hierynomus/smbj) 0.14.0 | Apache 2.0 |
| [dcerpc](https://mvnrepository.com/artifact/com.rapid7.client/dcerpc) 0.12.0 (Rapid7) | BSD 3-Clause |
| [OkHttp](https://github.com/square/okhttp) 5.5.0 | Apache 2.0 |
| [chardet4j](https://github.com/sigpwned/chardet4j) 78.1.0 | Apache 2.0 |
| [Glide](https://github.com/bumptech/glide) 4.16.0 | BSD 2-Clause (with parts under Apache 2.0) |
| [ZXing](https://github.com/zxing/zxing) core 3.5.4 | Apache 2.0 |
| [Sentry for Android](https://github.com/getsentry/sentry-java) 8.16.0 | MIT |
| [Material Components](https://github.com/material-components/material-components-android) 1.14.0 | Apache 2.0 |
| AndroidX AppCompat, RecyclerView, Preference, DocumentFile, CoordinatorLayout | Apache 2.0 |

The full texts: [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0), [MIT](https://opensource.org/licenses/MIT),
[BSD 2-Clause](https://opensource.org/licenses/BSD-2-Clause), [BSD 3-Clause](https://opensource.org/licenses/BSD-3-Clause),
[BSD 3-Clause Clear](https://spdx.org/licenses/BSD-3-Clause-Clear.html),
[Fraunhofer FDK MPEG-H](https://github.com/Fraunhofer-IIS/mpeghdec/blob/main/LICENSE.txt).
