# AR Reader

A free, offline, all-in-one document reader for Android. PDF, EPUB, Word, Excel,
PowerPoint, comics, text and images in one app, with no ads and no account.
Kotlin + Jetpack Compose + Material 3.

## Download

**[Get the latest APK](https://github.com/ABDURREHMAN629/ar-reader/releases/latest)**

Open the APK on your phone to install it. Android will ask you to allow installing
from that source, which is normal for an app that did not come from the Play Store.

Requires Android 8.0 (API 26) or newer.

## Formats

| Type | Extensions |
|---|---|
| PDF | `.pdf` |
| E-books | `.epub` |
| Word | `.docx` |
| Excel | `.xlsx` |
| PowerPoint | `.pptx`, `.ppsx` |
| Comics | `.cbz`, `.cbr` |
| Text | `.txt`, `.md` |
| Images | common image formats |

Old binary Office files (`.doc`, `.xls`, `.ppt`) are not supported yet.

## Features

**Reading**
- PDF pinch-zoom, diagonal panning, full-screen mode, printing, and go-to-page
- Each document reopens at the same page, and PDFs also keep their zoom
- Search inside text, Markdown, EPUB and Word/PowerPoint documents
- Reading themes per document: Light, Sepia, Night, Black, Console
- Brightness control inside the app, independent of the system setting
- Password-protected PDF, Word, Excel and PowerPoint files (passwords are never saved;
  protected PDFs need Android 15 or newer)
- Set as an "Open with" app: each document gets its own Recents card, and Back returns
  you straight to the app you came from (WhatsApp, for example)

**Library**
- Finds documents across your storage automatically, or only in folders you choose
- Search, format filters, sorting, grid and list views, and folder browsing
- Continue reading, History and Saved (favourites)
- Rename, share, and multi-select delete

**Tools**
- Image to PDF, Text to PDF, and Scan to PDF with Enhance, Grayscale and B&W filters
- Edit PDF: freehand pen and highlighter
- Compress images
- Change an image's background
- Convert images between JPG, PNG, WebP, HEIC, AVIF, GIF, BMP, TIFF, ICO, TGA, PSD and PDF

**Look and feel**
- Light, dark and AMOLED themes
- Several accent colours, plus Material You dynamic colour

## Privacy

The app does **not** have the `INTERNET` permission, so it cannot send your documents
anywhere. There is no account, no analytics and no server.

| Permission | Why |
|---|---|
| All files access (`MANAGE_EXTERNAL_STORAGE`) | Optional. Lets the library find documents across your storage. Without it, you pick folders yourself. |
| Read storage (`READ_EXTERNAL_STORAGE`) | Only on Android 12 and older. |

The Change Background tool uses ML Kit, and Google Play services downloads its model once
the first time you use it. That download is done by Play services, not by this app.

## Build it yourself

You need Android Studio (or JDK 17+ and the Android SDK, with API 37 installed).

```bash
./gradlew :app:assembleRelease
```

The APK comes out at `app/build/outputs/apk/release/app-release.apk`.

The Gradle `rootProject` name and the `com.ahif.lumen` package still use the app's
earlier working name, Lumen Reader.
