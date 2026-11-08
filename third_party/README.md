# Third-party components bundled in Tasin Shares

| Component | Version | Licence | Source | Used for |
|---|---|---|---|---|
| scrcpy-server | 5.0.1 | Apache-2.0 (`scrcpy/LICENSE`) | https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1 (SHA-256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`, matches the release's `install_release.sh`) | Screen video, TV audio capture, mouse and keyboard injection. Copied into `app/src/main/assets/scrcpy-server.jar`, run as the shell user through the app's own ADB connection. |
| jMuxer | 2.1.4 | MIT | https://www.npmjs.com/package/jmuxer | Plays the raw H.264 stream in the browser (`app/src/main/assets/jmuxer.min.js`). |
| NanoHTTPD + websocket | 2.3.1 | BSD-3-Clause | Maven Central | Web server and WebSocket. |
| Apache FtpServer | 1.2.0 | Apache-2.0 | Maven Central | FTP server. |
| ZXing core | 3.5.3 | Apache-2.0 | Maven Central | The QR code with the web address on the TV screen. |
| Geist (SemiBold, as outlines) | 1.x | SIL OFL 1.1 | https://github.com/vercel/geist-font | The "Tasin Shares" lettering in the TV banner (`app/src/main/res/drawable/banner.xml`). |
