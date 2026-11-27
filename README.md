# TippyTV

TippyTV is a live TV player for Amazon Fire TV and Android TV. Bring a Jellyfin server, Xtream account, or M3U playlist. Use the remote to find a channel, see what's on, or keep a second stream in the corner.

## Quick start

On Fire TV, search the Amazon Appstore for **TippyTV**. Open the app and choose a source on **Add a profile**.

To install from GitHub on Fire TV or Android TV, follow the steps below. Use the same installation source for future updates.

1. Download the signed APK from [GitHub Releases](https://github.com/therealparmesh/TippyTV/releases/latest). TippyTV supports ARMv7 and ARM64 devices running Android 6.0 or later.
2. Connect to your TV with Android Debug Bridge (ADB), then install the APK. Replace the example path with the file you downloaded.

   ```sh
   adb install -r "/path/to/downloaded.apk"
   ```

3. Open TippyTV and choose a source on **Add a profile**. The app opens your last-used profile on future launches.

## Connect a source

| Source       | What to enter                                                                                                                              |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Jellyfin     | The server address and an account with Live TV access. Use your password or choose **Quick Connect** to approve sign-in on another device. |
| Xtream       | A profile name, the provider's base URL, username, and password. Include the port or path if your provider supplies one.                   |
| M3U playlist | A profile name, the playlist URL, and an optional XMLTV guide URL. If you paste a GitHub Gist page URL, TippyTV lets you choose the files. |

Use **Profile → Switch profile** to add or open another source. Switching opens the new profile’s full channel list and clears the previous search or category.

### Set up from your phone

Choose **Set up with your phone → Continue** on the TV, then scan the QR code from a phone on the same network. Enter your source details on the phone and select **Continue on TV**. Finish any approval or file selection with the remote.

The TV hosts the HTTPS form and keeps its certificate keys locally. First-time setup needs internet access and may take several minutes. The QR code expires after 15 minutes. [Privacy policy](https://therealparmesh.github.io/TippyTV/privacy.html) · [Lancert terms](https://lancert.dev/terms) · [Let's Encrypt agreements](https://letsencrypt.org/repository/)

## Watch live TV

| To…                       | Use…                                                                                                                                                                                     |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Preview or go full screen | Press **Select** on a channel to preview it. Press it again to go full screen.                                                                                                           |
| Show playback controls    | Press a direction or **Select** in full screen. Press **Back** to return to channels.                                                                                                    |
| Pause or resume           | Press **Play/Pause** on the remote or use **Pause** and **Resume live** on screen.                                                                                                       |
| Browse by category        | Open **Categories** and choose a group. Choose **All channels** to clear the category and search.                                                                                        |
| Browse a long lineup      | From a channel, press **Left** for **−50**, **+50**, and **A–Z**. Use **A–Z** to jump to a letter or either end of the current list. Press **Up** after paging to focus the destination. |
| Save a favorite           | Hold **Select** and choose **Add favorite**. Switch between **Favorites** and **All channels** to change views and clear the search.                                                     |
| Read the guide            | Open **Guide**. **Earlier** and **Later** move three hours at a time. **Now** returns to the current window.                                                                             |
| Watch two channels        | Hold **Select** on the second channel and choose **Play in inset**. **Swap** changes which channel has audio. **Close inset** stops the second stream.                                   |

The channel count shows your active category and search term. **Categories** appears when the provider supplies Xtream categories, M3U groups, or Jellyfin channel genres. Search and **A–Z** work within the current list. **Favorites** shows favorites across the profile. **Back** clears a search first, then returns from Favorites to the list you left. Press **Back** in a category to return to the full lineup.

Jellyfin favorites sync to your server account. Xtream and playlist favorites stay with their profile on this TV. A provider rename or a change to a channel's identity can require adding it again.

With **Profile → Filter low-quality content** enabled, TippyTV hides channels using the provider's flags, groups, genres, or tags. The setting is on by default and applies across profiles on this TV.

## Troubleshooting

- **The TV is refreshing channels.** You can browse and tune from the last saved lineup while TippyTV checks for changes. A failed refresh keeps that lineup available. Returning to the app retries a failed refresh, or you can choose **Profile → Refresh channels**. If an Xtream provider previously supplied categories and its category request fails, you’ll see **Refresh needed** and can keep browsing the saved groups. A successful refresh removes channels that are gone. Signing out, removing the profile, or revoked Jellyfin access clears the saved lineup.
- **Channels load from scratch.** A new profile, an evicted cache, or a lineup too large to save requires a fresh download.
- **The guide is empty.** Check that your provider has listings for the selected time. For Xtream and M3U, the channel's `epg_channel_id` or `tvg-id` must match the XMLTV channel ID. Listings load as you browse and stay in memory while the app runs.
- **The stream stopped.** Use **Try again** in playback. For Jellyfin, hold **Select** on the channel and choose **Try a compatible stream** to request H.264/AAC conversion if your server permits it.
- **The source won't connect.** Check the host and port, and grant local network access if prompted. A Jellyfin `.local` hostname needs multicast DNS on the network.
- **The phone setup page won't open.** Check that both devices are on the same network. Guest network isolation, a VPN, or DNS rebinding protection can block access. Use **Try again** on the TV or finish setup with the remote.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build, test, and release instructions. Report problems through [GitHub Issues](https://github.com/therealparmesh/TippyTV/issues). Keep passwords, tokens, and private playlist URLs out of reports.

## License

TippyTV's original code is under the [MIT License](LICENSE). The APK includes GPL-enabled mpv and FFmpeg, so the combined app is distributed under [GPLv3](app/src/main/assets/licenses/GPL-3.0.txt). [Native playback dependencies](third_party/mpv/README.md) · [Dracula CSS license](phone/Dracula-LICENSE.txt)
