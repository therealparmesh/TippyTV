# TippyTV

Watch live TV from Jellyfin, Xtream, or an M3U playlist on Amazon Fire TV and Android TV. Search a large lineup by name or number, browse the programme guide, or keep a second channel playing in the corner.

## Install

Download the signed APK from [Releases](https://github.com/therealparmesh/TippyTV/releases/latest). TippyTV supports ARMv7 and ARM64 devices running Android 6.0 or later.

If your TV is connected through Android Debug Bridge (ADB), install or update it with:

```sh
adb install -r tippytv-1.1.0.apk
```

Open TippyTV and add your first profile. On later launches, the app opens the last profile you used.

When a saved lineup is available, you can browse and tune channels while the TV checks for changes. The first load without a saved lineup shows a loading message. If the source is unavailable, a playlist has no playable entries, or Jellyfin sends incomplete channel pages, the last complete lineup stays available. A successful refresh removes channels that are gone. If Jellyfin rejects your sign-in or revokes Live TV access, the TV clears that saved lineup. Programme listings load as you browse and stay in memory for the session.

## Connect a source

| Source | What you need |
| --- | --- |
| Jellyfin | A server address and an account with Live TV access. Choose **Quick Connect** to approve sign-in on another device, or enter your password. |
| Xtream | A profile name, your provider’s base URL (including any port or path), username, and password. TippyTV reads the live-channel API and falls back to the M3U export if needed. |
| Playlist | A profile name, an M3U URL, and optionally an XMLTV guide URL. For a GitHub Gist, paste its page URL and select the playlist and guide files. |

On the **Add a profile** screen, choose **Set up with your phone**, **Jellyfin**, **Xtream**, or **M3U playlist**. To switch or add another source later, open **Profile → Switch profile**.

### Set up with your phone

Choose **Set up with your phone → Continue**, then scan the QR code on the same Wi-Fi network. Enter your details and select **Continue on TV**. Finish any approval or file selection on the TV.

The TV hosts the HTTPS page. First-time certificate setup needs internet access and may take several minutes. The QR code is valid for 15 minutes. Submitting, canceling, leaving the app, or losing the TV’s local address closes the listener. Certificate keys stay on the TV. [Lancert terms](https://lancert.dev/terms) · [Let’s Encrypt agreements](https://letsencrypt.org/repository/)

## Use the remote

| To… | Use… |
| --- | --- |
| Watch a channel | Press **Select** for the preview. Press it again for full screen. |
| Show controls or leave full screen | Press a direction or **Select** for controls. Press **Back** for the browser. |
| Pause or resume | Press **Play/Pause**, or choose **Pause** or **Resume live**. |
| Find a channel | Use **Search** for names or numbers and **Jump** for letters or list endpoints. Press **Left** from the list for **−50** and **+50**. Skip repeatedly, then press **Up** to focus the destination. |
| Save a favorite | Hold **Select** and choose **Add favorite**. Switch between **Favorites** and **All channels** to change views and clear the search. |
| Read the guide | Open **Guide**. **Earlier** and **Later** move three hours at a time. **Now** returns to the current window. |
| Watch two channels | Hold **Select** on a second channel and choose **Play in inset**. **Swap** changes which picture has audio. **Close inset** releases the second stream. |

Jellyfin saves favorites to your server account. On cold start, Jellyfin favorites reflect the last saved lineup until the server responds. Xtream and playlist favorites belong to their profile on the TV. Switching profiles preserves them. Removing a playlist or Xtream profile deletes its local favorites.

Playlist and Xtream favorites follow the provider's channel identity. If the provider renames a channel, or changes its URL without a unique guide ID, you may need to favorite it again.

**Profile → Filter low-quality content** is on by default across profiles on this TV. It uses provider flags and groups, including Jellyfin genres and tags. Turning it off restores hidden channels and their saved favorites. Channel names have no effect on the filter.

## Troubleshooting

- **Phone setup page won’t open.** Put both devices on the same local network. Guest Wi-Fi isolation, a VPN, or DNS rebinding protection can prevent the phone from reaching the TV. Remote setup is also available.
- **Server won’t connect.** Check the address and port, and grant local network access if prompted. A Jellyfin `.local` hostname requires multicast DNS. For Xtream, enter the base address, such as `https://tv.example:8443`.
- **The lineup loads from scratch.** Each profile has its own encrypted channel cache. The TV loads from the source after sign-out, profile removal, Android cache eviction, or when a compressed lineup exceeds 8 MiB.
- **Channels load but the guide is empty.** Check that listings cover the selected time. An Xtream channel’s `epg_channel_id` or M3U entry’s `tvg-id` must match the XMLTV channel ID. If an XMLTV programme omits its end time, TippyTV uses the next programme’s start time on that channel. It skips the programme if neither time is available.
- **Playback stops.** Choose **Try again**. For Jellyfin, **Try a compatible stream** requests H.264/AAC conversion if your server permits it. Xtream and M3U use the provider’s stream directly.

## Development

For build requirements, test commands, and the commit convention, see [Contributing](CONTRIBUTING.md).

## License

TippyTV’s original source code is available under the [MIT License](LICENSE). The APK includes GPL-enabled mpv and FFmpeg libraries, so the combined application is distributed under [GPLv3](app/src/main/assets/licenses/GPL-3.0.txt). Third-party components retain their own notices. See the [native dependency notes](third_party/mpv/README.md) and [Dracula CSS license](phone/Dracula-LICENSE.txt).
